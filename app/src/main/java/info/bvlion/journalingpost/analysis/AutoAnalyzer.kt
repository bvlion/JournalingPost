package info.bvlion.journalingpost.analysis

import info.bvlion.journalingpost.journal.PeriodJournalEntryReader
import info.bvlion.journalingpost.settings.AnalysisIntegration
import info.bvlion.journalingpost.settings.AnalysisIntegrationRepository
import info.bvlion.journalingpost.settings.AutoAnalysisSettingsRepository
import info.bvlion.journalingpost.settings.AutoAnalysisTargetDay
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * 自動解析(Issue #59)の1回分の実行。WorkManagerのWorkerから呼ばれる。
 *
 * 設定の有効/無効・実効[AnalysisIntegration]・対象日(当日/前日)を実行時点で解決し、対象日の
 * JournalEntryを手動解析と同じ解析先へ送って、成功結果を[AnalysisResult]として端末へ保存する。
 * 対象日と期間境界はWorkerが実際に起動したローカル日付・端末timezoneで決める。予約時刻からの
 * 遅延がローカル日付を跨いだ場合や、timezone変更を跨いだ場合の対象日の厳密な扱いは#61で扱う。
 *
 * Hostedの回復し得る一時失敗では[AutoAnalysisOutcome.RETRYABLE_FAILURE]を返す。Workerが2分後の
 * retryを予約し、このクラスは保存した対象期間・JournalEntry snapshotを次回も使う。開始後にSettingsが
 * 変更されても送信先をCustom Webhookへ切り替えずretryを中止する。Custom WebhookとHostedの
 * rate_limitedはretryしない。
 *
 * Hosted自動解析の新規開始は成功・失敗にかかわらず実行日ごとに最大1回。実際にHostedへ送る直前に
 * 実行日を[AutoAnalysisAttemptStore]へ記録し、同じ実行日の別の新規解析は送らない。加えて、対象日が
 * 既に解析済み(同じ日を対象期間とする[AnalysisResult]が存在する)なら送らず、試行済みにもしない。
 */
internal class AutoAnalyzer(
  private val autoAnalysisSettingsRepository: AutoAnalysisSettingsRepository,
  private val analysisIntegrationRepository: AnalysisIntegrationRepository,
  private val periodJournalEntryReader: PeriodJournalEntryReader,
  private val analysisResultReader: AnalysisResultReader,
  private val autoAnalysisAttemptStore: AutoAnalysisAttemptStore,
  private val periodAnalysisRunner: PeriodAnalysisRunner,
  private val hostedPeriodAnalysisRunner: PeriodAnalysisRunner,
  private val currentZoneId: () -> ZoneId = { ZoneId.systemDefault() },
  private val currentDate: () -> LocalDate = { LocalDate.now(currentZoneId()) },
) {
  suspend fun runOnce(isHostedRetry: Boolean = false, hasRetryRemaining: Boolean = true): AutoAnalysisOutcome {
    val settings = autoAnalysisSettingsRepository.autoAnalysisSettings.first()
    if (!settings.enabled) {
      if (isHostedRetry) autoAnalysisAttemptStore.clearHostedRetrySnapshot()
      return AutoAnalysisOutcome.SKIPPED_DISABLED
    }

    val integration = analysisIntegrationRepository.analysisIntegration.first()
    if (isHostedRetry && integration != AnalysisIntegration.HOSTED) {
      autoAnalysisAttemptStore.clearHostedRetrySnapshot()
      return AutoAnalysisOutcome.FAILED
    }
    if (integration == AnalysisIntegration.NONE) return AutoAnalysisOutcome.SKIPPED_NO_INTEGRATION

    val retrySnapshot = if (isHostedRetry) {
      autoAnalysisAttemptStore.hostedRetrySnapshot() ?: return AutoAnalysisOutcome.FAILED
    } else {
      null
    }

    val zoneId = currentZoneId()
    val executionDate = currentDate()
    val targetDay = when (settings.targetDay) {
      AutoAnalysisTargetDay.TODAY -> executionDate
      AutoAnalysisTargetDay.YESTERDAY -> executionDate.minusDays(1)
    }
    val isHosted = isHostedRetry || integration == AnalysisIntegration.HOSTED

    if (isHosted && !isHostedRetry) {
      if (autoAnalysisAttemptStore.lastHostedAttemptDate() == executionDate) {
        return AutoAnalysisOutcome.SKIPPED_ALREADY_ATTEMPTED_TODAY
      }
      if (isAlreadyAnalyzed(targetDay, zoneId)) {
        return AutoAnalysisOutcome.SKIPPED_ALREADY_ANALYZED
      }
    }

    val periodStart = retrySnapshot?.periodStart ?: targetDay.atStartOfDay(zoneId).toInstant()
    val periodEnd = retrySnapshot?.periodEnd ?: targetDay.plusDays(1).atStartOfDay(zoneId).toInstant()
    val analysisDate = retrySnapshot?.analysisDate ?: targetDay

    val entries = retrySnapshot?.entries ?: try {
      periodJournalEntryReader.entriesInPeriod(periodStart, periodEnd)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return AutoAnalysisOutcome.FAILED
    }
    if (entries.isEmpty()) return AutoAnalysisOutcome.SKIPPED_NO_ENTRIES

    if (isHosted && !isHostedRetry) {
      try {
        autoAnalysisAttemptStore.storeHostedRetrySnapshot(
          HostedAutoAnalysisRetrySnapshot(analysisDate, periodStart, periodEnd, entries),
        )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        return AutoAnalysisOutcome.FAILED
      }
      // ここから実際にHostedへ送る。送信前に実行日を記録し、以降の失敗やプロセス終了があっても
      // 同じ実行日に2回目の試行を行わない。
      autoAnalysisAttemptStore.recordHostedAttempt(executionDate)
    }

    val analysisRunner = if (isHosted) hostedPeriodAnalysisRunner else periodAnalysisRunner
    return when (val outcome = analysisRunner.run(periodStart, periodEnd, entries, analysisDate)) {
      is PeriodAnalysisRunner.Outcome.Saved -> {
        if (isHosted) autoAnalysisAttemptStore.clearHostedRetrySnapshot()
        AutoAnalysisOutcome.ANALYZED
      }

      is PeriodAnalysisRunner.Outcome.Failed -> {
        val shouldRetry = isHosted &&
          (
            outcome.failure == PeriodAnalysisOutcome.Failure.NETWORK ||
              outcome.failure == PeriodAnalysisOutcome.Failure.TEMPORARILY_UNAVAILABLE
          )
        if (shouldRetry && hasRetryRemaining) {
          AutoAnalysisOutcome.RETRYABLE_FAILURE
        } else {
          if (isHosted) autoAnalysisAttemptStore.clearHostedRetrySnapshot()
          AutoAnalysisOutcome.FAILED
        }
      }

      PeriodAnalysisRunner.Outcome.SaveFailed -> {
        if (isHosted) autoAnalysisAttemptStore.clearHostedRetrySnapshot()
        AutoAnalysisOutcome.FAILED
      }
    }
  }

  private suspend fun isAlreadyAnalyzed(day: LocalDate, zoneId: ZoneId): Boolean =
    analysisResultReader.observeAll().first()
      .any { it.periodStart.atZone(zoneId).toLocalDate() == day }
}

/** 自動解析1回分の結果。Workerがretryまたは次回の日次実行を予約するために受け取る。 */
internal enum class AutoAnalysisOutcome {
  ANALYZED,
  SKIPPED_DISABLED,
  SKIPPED_NO_INTEGRATION,

  /** Hostedで、その実行日に既に自動解析を試行済み。 */
  SKIPPED_ALREADY_ATTEMPTED_TODAY,

  /** Hostedで、対象日が既に解析済み。 */
  SKIPPED_ALREADY_ANALYZED,

  SKIPPED_NO_ENTRIES,
  RETRYABLE_FAILURE,
  FAILED,
}
