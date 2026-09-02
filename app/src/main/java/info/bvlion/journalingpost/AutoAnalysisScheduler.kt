package info.bvlion.journalingpost

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import info.bvlion.journalingpost.settings.AutoAnalysisSettingsRepository
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/**
 * 自動解析(Issue #59)の実行タイミングをAndroid側で管理する。Serverへtimezone / recurrence /
 * 次回実行時刻は渡さず、FCM / triggerAt / Push予約でも起こさない。
 *
 * 「その時刻ごろ」の1回きりの[AutoAnalysisWorker]を予約し、Workerが実行の最後に翌日以降の直近の
 * 指定時刻へ予約し直すことで日次のrecurrenceを作る。次回時刻は予約のたびに実行時点の端末timezoneで
 * 計算する。厳密な実行保証ではなく、network制約・省電力・background実行で前後する。
 *
 * 予約後に端末timezoneが変わると、次にWorkerが起動して予約し直すまでは、その1回の予約は
 * 旧timezoneで計算した時刻のまま残る。この場合の対象日の扱いは#61で扱う。
 */
internal class AutoAnalysisScheduler(
  context: Context,
  private val autoAnalysisSettingsRepository: AutoAnalysisSettingsRepository,
  private val now: () -> Instant = Instant::now,
  private val currentZoneId: () -> ZoneId = { ZoneId.systemDefault() },
) {
  private val workManager = WorkManager.getInstance(context.applicationContext)

  /**
   * アプリ起動時に呼ぶ。設定が有効なら予約が無ければ作り(既存の予約は時刻を計算し直さない)、
   * 無効なら予約を解除する。
   */
  suspend fun syncFromSettings() = applySettings(ExistingWorkPolicy.KEEP)

  /** 設定変更時・Workerの実行後に呼ぶ。次回時刻を計算し直して予約を置き換える。無効なら解除する。 */
  suspend fun reschedule() = applySettings(ExistingWorkPolicy.REPLACE)

  private suspend fun applySettings(policy: ExistingWorkPolicy) {
    val settings = autoAnalysisSettingsRepository.autoAnalysisSettings.first()
    if (!settings.enabled) {
      workManager.cancelUniqueWork(WORK_NAME)
      return
    }
    val request = OneTimeWorkRequestBuilder<AutoAnalysisWorker>()
      .setInitialDelay(
        nextRunDelay(now(), currentZoneId(), settings.timeOfDay).toMillis(),
        TimeUnit.MILLISECONDS,
      )
      .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
      .build()
    workManager.enqueueUniqueWork(WORK_NAME, policy, request)
  }

  companion object {
    const val WORK_NAME = "auto_analysis"
  }
}

/**
 * [from]以降で最初に訪れる[timeOfDay](端末timezone)までの待ち時間。ちょうど一致する場合は翌日にする
 * (「実行の最後に次回を予約する」経路で当日へ戻らないため)。
 */
internal fun nextRunDelay(from: Instant, zoneId: ZoneId, timeOfDay: LocalTime): Duration {
  val now = from.atZone(zoneId)
  var next = now.toLocalDate().atTime(timeOfDay).atZone(zoneId)
  if (!next.isAfter(now)) next = next.plusDays(1)
  return Duration.between(now, next)
}
