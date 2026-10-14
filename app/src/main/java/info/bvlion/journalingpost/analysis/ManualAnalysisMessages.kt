package info.bvlion.journalingpost.analysis

import android.content.res.Resources
import androidx.annotation.StringRes
import info.bvlion.journalingpost.AnalysisRunResult
import info.bvlion.journalingpost.R
import java.time.format.DateTimeFormatter
import java.util.Locale

private val analysisDayFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.JAPAN)

@StringRes
internal fun ManualAnalysisAvailability.unavailableReasonResource(): Int? = when (this) {
  ManualAnalysisAvailability.CHECKING -> R.string.journal_history_analysis_loading
  ManualAnalysisAvailability.AVAILABLE -> null
  ManualAnalysisAvailability.NO_ENTRIES -> R.string.journal_history_analysis_no_entries
  ManualAnalysisAvailability.INTEGRATION_UNAVAILABLE -> R.string.analysis_failure_integration_unavailable
  ManualAnalysisAvailability.ALREADY_COMPLETED -> R.string.journal_history_analysis_already_completed
  ManualAnalysisAvailability.OUTSIDE_PERIOD -> R.string.journal_history_analysis_outside_period
  ManualAnalysisAvailability.RUNNING_DAY -> R.string.journal_history_analysis_running_day
  ManualAnalysisAvailability.RUNNING_OTHER_DAY -> R.string.journal_history_analysis_running_other_day
}

/** 対象日をSnackbar側で持ち直さず、失敗結果に含まれる日をそのまま文言へ入れる。 */
internal fun Resources.failureMessage(failed: AnalysisRunResult.Failed): String = when (failed.failure) {
  PeriodAnalysisOutcome.Failure.WEBHOOK_UNAVAILABLE -> getString(R.string.analysis_failure_webhook_unavailable)
  PeriodAnalysisOutcome.Failure.INTEGRATION_UNAVAILABLE ->
    getString(R.string.analysis_failure_integration_unavailable)

  PeriodAnalysisOutcome.Failure.NO_ENTRIES ->
    getString(R.string.analysis_failure_no_entries, failed.day.format(analysisDayFormatter))

  PeriodAnalysisOutcome.Failure.LOCAL_READ -> getString(R.string.analysis_failure_local_read)
  PeriodAnalysisOutcome.Failure.NETWORK -> getString(R.string.analysis_failure_network)
  PeriodAnalysisOutcome.Failure.SERVER_ERROR -> getString(R.string.analysis_failure_server_error)
  PeriodAnalysisOutcome.Failure.RATE_LIMITED -> getString(R.string.analysis_failure_rate_limited)
  PeriodAnalysisOutcome.Failure.TEMPORARILY_UNAVAILABLE ->
    getString(R.string.analysis_failure_temporarily_unavailable)

  PeriodAnalysisOutcome.Failure.INVALID_RESPONSE -> getString(R.string.analysis_failure_invalid_response)
  null -> getString(R.string.analysis_failure_save)
}
