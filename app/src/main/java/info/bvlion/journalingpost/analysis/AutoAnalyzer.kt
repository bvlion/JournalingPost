package info.bvlion.journalingpost.analysis

import info.bvlion.journalingpost.journal.PeriodJournalEntryReader
import info.bvlion.journalingpost.settings.AnalysisIntegration
import info.bvlion.journalingpost.settings.AnalysisIntegrationRepository
import info.bvlion.journalingpost.settings.AutoAnalysisSettingsRepository
import info.bvlion.journalingpost.settings.AutoAnalysisTargetDay
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * 自動解析(Issue #59)の1回分の実行。WorkManagerのWorkerから呼ばれる。
 *
 * 設定の有効/無効・実効[AnalysisIntegration]・対象日(当日/前日)を実行時点で解決し、対象日の
 * JournalEntryを手動解析と同じ解析先へ送って、成功結果を[AnalysisResult]として端末へ保存する。
 * 対象日と期間境界はWorkerが実際に起動したローカル日付・端末timezoneで決める。予約時刻からの
 * 遅延がローカル日付を跨いだ場合や、timezone変更を跨いだ場合の対象日の厳密な扱いは#61で扱う。
 *
 * Hostedの回復し得る一時失敗だけは、同じ対象期間のまま15秒間隔で最大2回再試行する。Serverの
 * retry delivery buffer(30分)内に十分収まり、analysis_in_progressのRetry-Afterにも合わせた間隔である。
 * Custom WebhookとHostedのrate_limitedは再試行しない。
 *
 * Hostedの自動解析は成功・失敗にかかわらず実行日ごとに最大1回。実際にHostedへ送る直前に実行日を
 * [AutoAnalysisAttemptStore]へ記録し、同じ実行日の2回目以降は送らない。加えて、対象日が既に解析済み
 * (同じ日を対象期間とする[AnalysisResult]が存在する)なら送らず、試行済みにもしない。
 */
internal class AutoAnalyzer(
  private val autoAnalysisSettingsRepository: AutoAnalysisSettingsRepository,
  private val analysisIntegrationRepository: AnalysisIntegrationRepository,
  private val periodJournalEntryReader: PeriodJournalEntryReader,
  private val analysisResultReader: AnalysisResultReader,
  private val autoAnalysisAttemptStore: AutoAnalysisAttemptStore,
  private val periodAnalysisRunner: PeriodAnalysisRunner,
  private val currentZoneId: () -> ZoneId = { ZoneId.systemDefault() },
  private val currentDate: () -> LocalDate = { LocalDate.now(currentZoneId()) },
  private val waitBeforeRetry: suspend () -> Unit = { delay(HOSTED_RETRY_DELAY_MILLIS) },
) {
  suspend fun runOnce(): AutoAnalysisOutcome {
    val settings = autoAnalysisSettingsRepository.autoAnalysisSettings.first()
    if (!settings.enabled) return AutoAnalysisOutcome.SKIPPED_DISABLED

    val integration = analysisIntegrationRepository.analysisIntegration.first()
    if (integration == AnalysisIntegration.NONE) return AutoAnalysisOutcome.SKIPPED_NO_INTEGRATION

    val zoneId = currentZoneId()
    val executionDate = currentDate()
    val targetDay = when (settings.targetDay) {
      AutoAnalysisTargetDay.TODAY -> executionDate
      AutoAnalysisTargetDay.YESTERDAY -> executionDate.minusDays(1)
    }
    val isHosted = integration == AnalysisIntegration.HOSTED

    if (isHosted) {
      if (autoAnalysisAttemptStore.lastHostedAttemptDate() == executionDate) {
        return AutoAnalysisOutcome.SKIPPED_ALREADY_ATTEMPTED_TODAY
      }
      if (isAlreadyAnalyzed(targetDay, zoneId)) {
        return AutoAnalysisOutcome.SKIPPED_ALREADY_ANALYZED
      }
    }

    val periodStart = targetDay.atStartOfDay(zoneId).toInstant()
    val periodEnd = targetDay.plusDays(1).atStartOfDay(zoneId).toInstant()

    val entries = try {
      periodJournalEntryReader.entriesInPeriod(periodStart, periodEnd)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return AutoAnalysisOutcome.FAILED
    }
    if (entries.isEmpty()) return AutoAnalysisOutcome.SKIPPED_NO_ENTRIES

    if (isHosted) {
      // ここから実際にHostedへ送る。送信前に実行日を記録し、以降の失敗やプロセス終了があっても
      // 同じ実行日に2回目の試行を行わない。
      autoAnalysisAttemptStore.recordHostedAttempt(executionDate)
    }

    var retryCount = 0
    while (true) {
      when (val outcome = periodAnalysisRunner.run(periodStart, periodEnd, entries)) {
        is PeriodAnalysisRunner.Outcome.Saved -> return AutoAnalysisOutcome.ANALYZED
        is PeriodAnalysisRunner.Outcome.Failed -> {
          val shouldRetry = isHosted &&
            (
              outcome.failure == PeriodAnalysisOutcome.Failure.NETWORK ||
                outcome.failure == PeriodAnalysisOutcome.Failure.TEMPORARILY_UNAVAILABLE
            ) && retryCount < HOSTED_RETRY_COUNT
          if (!shouldRetry) return AutoAnalysisOutcome.FAILED
          retryCount++
          waitBeforeRetry()
        }
        PeriodAnalysisRunner.Outcome.SaveFailed -> return AutoAnalysisOutcome.FAILED
      }
    }
  }

  private suspend fun isAlreadyAnalyzed(day: LocalDate, zoneId: ZoneId): Boolean =
    analysisResultReader.observeAll().first()
      .any { it.periodStart.atZone(zoneId).toLocalDate() == day }

  private companion object {
    const val HOSTED_RETRY_COUNT = 2
    const val HOSTED_RETRY_DELAY_MILLIS = 15_000L
  }
}

/** 自動解析1回分の結果。短時間retryも含めた最終結果をWorkerが記録目的でだけ受け取る。 */
internal enum class AutoAnalysisOutcome {
  ANALYZED,
  SKIPPED_DISABLED,
  SKIPPED_NO_INTEGRATION,

  /** Hostedで、その実行日に既に自動解析を試行済み。 */
  SKIPPED_ALREADY_ATTEMPTED_TODAY,

  /** Hostedで、対象日が既に解析済み。 */
  SKIPPED_ALREADY_ANALYZED,

  SKIPPED_NO_ENTRIES,
  FAILED,
}
