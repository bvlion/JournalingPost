package info.bvlion.journalingpost.analysis

import info.bvlion.journalingpost.settings.AnalysisIntegration
import info.bvlion.journalingpost.time.CalendarDate
import java.time.LocalDate

/**
 * 特定の1日について、手動解析を今開始できるかを表す判定結果。
 * [ManualAnalysisEligibility]は判定に使う情報の読み込み状態とスナップショットを保持し、
 * この型はその情報を対象日に当てはめた結果だけを表す。解析要求の実行結果とは異なる。
 */
enum class ManualAnalysisAvailability {
  /** 必要な情報の初回読み込み前、または履歴と判定の日時基準がまだ揃っていない。 */
  CHECKING,

  /** 開始条件を満たす。解析先の受付や解析の成功まで保証するものではない。 */
  AVAILABLE,

  /** 対象日の記録がない。読み取り自体が失敗した状態ではない。 */
  NO_ENTRIES,

  /** 実効設定が「使用しない」で、利用できる解析先がない。 */
  INTEGRATION_UNAVAILABLE,

  /** Hostedで成功済み。保存結果を削除しても対象日の再解析はできない。 */
  ALREADY_COMPLETED,

  /** Hostedの対象期間である昨日から6日前までに含まれない。 */
  OUTSIDE_PERIOD,

  /** 対象日自身の手動解析が進行中。開始後に他の条件が変わってもこの状態を示す。 */
  RUNNING_DAY,

  /** 対象日は開始条件を満たすが、別の日の手動解析が終わるまで開始できない。 */
  RUNNING_OTHER_DAY;

  val isAvailable: Boolean get() = this == AVAILABLE
}

/** 可否判定に必要な情報。Readyは情報が揃ったことを示し、実行可能とは限らない。 */
sealed interface ManualAnalysisEligibility {
  data object Checking : ManualAnalysisEligibility

  data class Ready(
    val calendarDate: CalendarDate,
    val integration: AnalysisIntegration,
    val recordedDays: Set<LocalDate>,
    val hostedSuccessfulDays: Set<LocalDate>,
    val runningDay: LocalDate?,
  ) : ManualAnalysisEligibility {
    // 同日の進行状況を最優先する。それ以外は記録なし、解析先なし、Hosted成功済み、期間外の順に
    // 案内し、別日の解析終了後も解消しない理由を「別の日を解析中」で隠さない。
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
