package info.bvlion.journalingpost.analysis

import info.bvlion.journalingpost.settings.AnalysisIntegration
import info.bvlion.journalingpost.time.CalendarDate
import java.time.LocalDate

enum class ManualAnalysisAvailability {
  CHECKING,
  AVAILABLE,
  NO_ENTRIES,
  INTEGRATION_UNAVAILABLE,
  ALREADY_COMPLETED,
  OUTSIDE_PERIOD,
  RUNNING_DAY,
  RUNNING_OTHER_DAY;

  val isAvailable: Boolean get() = this == AVAILABLE
}

sealed interface ManualAnalysisEligibility {
  data object Checking : ManualAnalysisEligibility

  data class Ready(
    val calendarDate: CalendarDate,
    val integration: AnalysisIntegration,
    val recordedDays: Set<LocalDate>,
    val hostedSuccessfulDays: Set<LocalDate>,
    val runningDay: LocalDate?,
  ) : ManualAnalysisEligibility {
    fun availabilityFor(day: LocalDate): ManualAnalysisAvailability = when {
      runningDay == day -> ManualAnalysisAvailability.RUNNING_DAY
      day !in recordedDays -> ManualAnalysisAvailability.NO_ENTRIES
      integration == AnalysisIntegration.NONE -> ManualAnalysisAvailability.INTEGRATION_UNAVAILABLE
      integration == AnalysisIntegration.HOSTED && day in hostedSuccessfulDays ->
        ManualAnalysisAvailability.ALREADY_COMPLETED
      integration == AnalysisIntegration.HOSTED &&
        (day >= calendarDate.today || day < calendarDate.today.minusDays(6)) ->
        ManualAnalysisAvailability.OUTSIDE_PERIOD
      runningDay != null -> ManualAnalysisAvailability.RUNNING_OTHER_DAY
      else -> ManualAnalysisAvailability.AVAILABLE
    }

    // 実行中も日付候補は維持し、実行ボタンを共有の実行状態で無効にする。
    val selectableDays: Set<LocalDate>
      get() {
        val idle = copy(runningDay = null)
        return recordedDays.filterTo(mutableSetOf()) { idle.availabilityFor(it).isAvailable }
      }

    val hasAvailableIntegration: Boolean get() = integration != AnalysisIntegration.NONE
  }
}
