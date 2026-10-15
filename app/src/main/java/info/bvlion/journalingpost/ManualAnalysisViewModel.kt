package info.bvlion.journalingpost

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import info.bvlion.journalingpost.analysis.AnalysisExecutionRepository
import info.bvlion.journalingpost.analysis.ManualAnalysisAvailability
import info.bvlion.journalingpost.analysis.ManualAnalysisEligibility
import info.bvlion.journalingpost.analysis.PeriodAnalysisOutcome
import info.bvlion.journalingpost.analysis.PeriodAnalysisRunner
import info.bvlion.journalingpost.hosted.HostedCredentialsRepository
import info.bvlion.journalingpost.hosted.sha256Hex
import info.bvlion.journalingpost.journal.JournalEntryReader
import info.bvlion.journalingpost.journal.PeriodJournalEntryReader
import info.bvlion.journalingpost.settings.AnalysisIntegrationRepository
import info.bvlion.journalingpost.time.CalendarDate
import info.bvlion.journalingpost.time.CurrentDateProvider
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class ManualAnalysisViewModel(
  private val analysisIntegrationRepository: AnalysisIntegrationRepository,
  private val journalEntryReader: () -> JournalEntryReader,
  private val periodJournalEntryReader: () -> PeriodJournalEntryReader,
  private val periodAnalysisRunner: () -> PeriodAnalysisRunner,
  private val analysisExecutionRepository: () -> AnalysisExecutionRepository,
  private val hostedCredentialsRepository: () -> HostedCredentialsRepository,
  private val currentDateProvider: CurrentDateProvider,
) : ViewModel() {
  private val _state = MutableStateFlow(ManualAnalysisState())
  val state: StateFlow<ManualAnalysisState> = _state.asStateFlow()
  private var nextNotificationId = 0L

  // 通常起動では実行状態だけを購読できる。記録・成功済み日の読み取りは解析入口の表示時に開始する。
  val eligibility: StateFlow<ManualAnalysisEligibility> = flow {
    emitAll(combine(
      analysisIntegrationRepository.analysisIntegration,
      journalEntryReader().observeAll(),
      analysisExecutionRepository().state,
      currentDateProvider.state,
      state.map { it.runningDay }.distinctUntilChanged(),
    ) { integration, entries, executionState, calendarDate, runningDay ->
      ManualAnalysisEligibility.Ready(
        calendarDate = calendarDate,
        integration = integration,
        recordedDays = entries.mapTo(mutableSetOf()) { it.timestamp.atZone(calendarDate.zoneId).toLocalDate() },
        hostedSuccessfulDays = executionState.hostedSuccessfulDays,
        runningDay = runningDay,
      )
    })
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ManualAnalysisEligibility.Checking)

  /** 共有の解析中・更新前の表示からの要求は受け付けず、通知も追加しない。 */
  fun analyze(day: LocalDate, displayedCalendarDate: CalendarDate) {
    if (state.value.isRunning) return
    if (displayedCalendarDate != currentDateProvider.refresh()) return
    // 開始後は日時の更新があっても対象期間を変更しない。
    val periodStart = day.atStartOfDay(displayedCalendarDate.zoneId).toInstant()
    val periodEnd = day.plusDays(1).atStartOfDay(displayedCalendarDate.zoneId).toInstant()
    _state.update { it.copy(runningDay = day) }
    viewModelScope.launch {
      try {
        val result = checkAndRunAnalysis(day, periodStart, periodEnd, displayedCalendarDate)
        val notification = ManualAnalysisNotification(++nextNotificationId, result)
        // 開始可否の確認で終了した要求も、失敗した要求も、表示側が消費するまでは結果を残す。
        _state.update { it.copy(runningDay = null, notifications = it.notifications + notification) }
      } finally {
        _state.update { it.copy(runningDay = null) }
      }
    }
  }

  fun notificationShown(id: Long) {
    _state.update { it.copy(notifications = it.notifications.filterNot { notification -> notification.id == id }) }
  }

  private suspend fun checkAndRunAnalysis(
    day: LocalDate,
    periodStart: Instant,
    periodEnd: Instant,
    calendarDate: CalendarDate,
  ): AnalysisRunResult {
    val entries = try {
      periodJournalEntryReader().entriesInPeriod(periodStart, periodEnd)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return AnalysisRunResult.Failed(PeriodAnalysisOutcome.Failure.LOCAL_READ, day)
    }
    // 正常に読み取って0件だった場合は開始条件を満たさないだけで、読み取りや解析の失敗ではない。
    if (entries.isEmpty()) return AnalysisRunResult.Rejected(ManualAnalysisAvailability.NO_ENTRIES, day)

    // 全件購読による表示用判定に依存せず、固定した期間の実データと最新設定で開始条件を確認する。
    val availability = try {
      ManualAnalysisEligibility.Ready(
        calendarDate = calendarDate,
        integration = analysisIntegrationRepository.analysisIntegration.first(),
        recordedDays = setOf(day),
        hostedSuccessfulDays = analysisExecutionRepository().state.first().hostedSuccessfulDays,
        // 同時実行は受付で抑止済み。受付済みの要求自身を「同日を解析中」として拒否しない。
        runningDay = null,
      ).availabilityFor(day)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return AnalysisRunResult.Failed(PeriodAnalysisOutcome.Failure.LOCAL_READ, day)
    }
    if (!availability.isAvailable) return AnalysisRunResult.Rejected(availability, day)

    // 対象期間・解析日時・本文はいずれも解析先responseの値を保存元にする。保存と、
    // 端末保存確定のretry stateを持つanalyzerへの通知は[PeriodAnalysisRunner]へ閉じている。
    return when (val outcome = periodAnalysisRunner().run(periodStart, periodEnd, entries, day)) {
      is PeriodAnalysisRunner.Outcome.Saved -> AnalysisRunResult.Succeeded(outcome.savedResultId)
      is PeriodAnalysisRunner.Outcome.Failed -> {
        val supportId = if (outcome.failure == PeriodAnalysisOutcome.Failure.RATE_LIMITED) {
          try {
            hostedCredentialsRepository().apiKey()?.sha256Hex()
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            null
          }
        } else {
          null
        }
        AnalysisRunResult.Failed(outcome.failure, day, supportId)
      }
      PeriodAnalysisRunner.Outcome.SaveFailed -> AnalysisRunResult.Failed(failure = null, day = day)
    }
  }
}

internal data class ManualAnalysisState(
  val runningDay: LocalDate? = null,
  val notifications: List<ManualAnalysisNotification> = emptyList(),
) {
  val isRunning: Boolean get() = runningDay != null
}

internal data class ManualAnalysisNotification(val id: Long, val result: AnalysisRunResult)

/** 受け付けた手動解析要求の結果。送信しなかった要求も含め、Activityの通知が完了するまで保持する。 */
sealed interface AnalysisRunResult {
  /**
   * 入力の読み取りは成功したが、記録なし・解析先なし・Hostedの制限により解析開始前に終了した。
   * [day]は記録なしの通知にも対象日を含めるために保持する。解析先への送信・結果保存は行っていない。
   */
  data class Rejected(val availability: ManualAnalysisAvailability, val day: LocalDate) : AnalysisRunResult

  /**
   * 解析が成功しAnalysisResultを保存した。[savedResultId]は保存した行のid。
   */
  data class Succeeded(val savedResultId: Long) : AnalysisRunResult

  /**
   * 読み取りに失敗して開始条件を確認できなかったか、共有の解析処理が失敗を返した場合。
   * HTTP送信済みを意味しない。[PeriodAnalysisOutcome.Failure]は手動・自動で共有する解析側の契約で、
   * 送信前の拒否理由も含むため、その結果はここへ引き継ぐ。
   * [failure]がnullの場合だけは、解析後の端末保存に失敗しており、保存失敗の通知に使う。
   */
  data class Failed(
    val failure: PeriodAnalysisOutcome.Failure?,
    val day: LocalDate,
    val supportId: String? = null,
  ) : AnalysisRunResult
}
