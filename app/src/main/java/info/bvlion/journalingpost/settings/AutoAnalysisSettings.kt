package info.bvlion.journalingpost.settings

import java.time.LocalTime

/**
 * 自動解析(Issue #59)の設定。開始主体はAndroidで、実行時刻・対象日・recurrenceはすべて端末側で管理する。
 * Serverへtimezone / recurrence / 次回実行時刻は渡さない。
 *
 * [timeOfDay]は端末timezoneでの「その時刻ごろ」に解釈する。厳密な実行保証ではなく、background実行や
 * 省電力・通信状態で前後することを許容する。
 */
data class AutoAnalysisSettings(
  val enabled: Boolean,
  val timeOfDay: LocalTime,
  val targetDay: AutoAnalysisTargetDay,
) {
  companion object {
    val DEFAULT = AutoAnalysisSettings(
      enabled = false,
      timeOfDay = LocalTime.of(8, 0),
      targetDay = AutoAnalysisTargetDay.YESTERDAY,
    )
  }
}

/** 自動解析の対象日。1日の中の時間範囲は指定しない(日単位)。 */
enum class AutoAnalysisTargetDay {
  /** 実行日と同じカレンダー日。 */
  TODAY,

  /** 実行日の前日。 */
  YESTERDAY,
}
