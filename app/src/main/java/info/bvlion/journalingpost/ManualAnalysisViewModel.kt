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

  fun analyze(day: LocalDate, displayedDate: CalendarDate) {
    if (state.value.isRunning || displayedDate != currentDateProvider.refresh()) return
    // 開始後は日時の更新があっても対象期間を変更しない。
    val periodStart = day.atStartOfDay(displayedDate.zoneId).toInstant()
    val periodEnd = day.plusDays(1).atStartOfDay(displayedDate.zoneId).toInstant()
    _state.update { it.copy(runningDay = day) }
    viewModelScope.launch {
      try {
        val result = runAnalysis(day, periodStart, periodEnd, displayedDate)
        val notification = ManualAnalysisNotification(++nextNotificationId, result)
        _state.update { it.copy(runningDay = null, notifications = it.notifications + notification) }
      } finally {
        _state.update { it.copy(runningDay = null) }
      }
    }
  }

  fun notificationShown(id: Long) {
    _state.update { it.copy(notifications = it.notifications.filterNot { notification -> notification.id == id }) }
  }

  private suspend fun runAnalysis(
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
    if (entries.isEmpty()) return AnalysisRunResult.Failed(PeriodAnalysisOutcome.Failure.NO_ENTRIES, day)

    val availability = try {
      ManualAnalysisEligibility.Ready(
        calendarDate = calendarDate,
        integration = analysisIntegrationRepository.analysisIntegration.first(),
        recordedDays = setOf(day),
        hostedSuccessfulDays = analysisExecutionRepository().state.first().hostedSuccessfulDays,
        runningDay = null,
      ).availabilityFor(day)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return AnalysisRunResult.Failed(PeriodAnalysisOutcome.Failure.LOCAL_READ, day)
    }
    if (!availability.isAvailable) return AnalysisRunResult.Rejected(availability)

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
      PeriodAnalysisRunner.Outcome.SaveFailed -> AnalysisRunResult.Failed(null, day)
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

sealed interface AnalysisRunResult {
  data class Rejected(val availability: ManualAnalysisAvailability) : AnalysisRunResult

  /**
   * 解析が成功しAnalysisResultを保存した。[savedResultId]は保存した行のid。
   */
  data class Succeeded(val savedResultId: Long) : AnalysisRunResult

  /**
   * 失敗した解析実行。[day]は実行対象に選んだ日で、対象日を含むメッセージ(NO_ENTRIES等)を
   * 失敗理由と同じ結果から組み立てられるように持つ。[failure]がnullなのは、解析自体は成功したが
   * AnalysisResultの端末保存に失敗した場合。
   */
  data class Failed(
    val failure: PeriodAnalysisOutcome.Failure?,
    val day: LocalDate,
    val supportId: String? = null,
  ) : AnalysisRunResult
}
