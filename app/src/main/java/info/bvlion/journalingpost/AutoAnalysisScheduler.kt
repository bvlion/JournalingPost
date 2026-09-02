package info.bvlion.journalingpost

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * 自動解析(Issue #59)の実行タイミングをAndroid側で管理する。Serverへtimezone / recurrence /
 * 次回実行時刻は渡さず、FCM / triggerAt / Push予約でも起こさない。
 *
 * 「その時刻ごろ」の1回きりの[AutoAnalysisWorker]を予約し、Workerが実行の最後に翌日以降の直近の
 * 指定時刻へ予約し直すことで日次のrecurrenceを作る。次回時刻は予約のたびに実行時点の端末timezoneで
 * 計算する。予約に使ったtimezoneを覚えておき、アプリ起動時に端末timezoneが変わっていれば予約を
 * 計算し直す(移動でローカル時刻からずれたまま実行され、「当日」対象で日の途中までを解析して
 * その日を解析済みにしてしまうのを防ぐ)。厳密な実行保証ではなく、network制約・省電力・background
 * 実行で前後する。
 */
internal class AutoAnalysisScheduler(
  context: Context,
  private val autoAnalysisSettingsRepository: AutoAnalysisSettingsRepository,
  private val stateDataStore: DataStore<Preferences>,
  private val now: () -> Instant = Instant::now,
  private val currentZoneId: () -> ZoneId = { ZoneId.systemDefault() },
) {
  private val workManager = WorkManager.getInstance(context.applicationContext)

  /**
   * アプリ起動時に呼ぶ。設定が有効なら予約を確保し、予約した時点から端末timezoneが変わっていたら
   * 現在のtimezoneでの指定時刻へ計算し直して置き換える。無効なら予約を解除する。
   */
  suspend fun syncFromSettings() = applySettings(replaceUnconditionally = false)

  /** 設定変更時・Workerの実行後に呼ぶ。次回時刻を現在のtimezoneで計算し直して予約を置き換える。 */
  suspend fun reschedule() = applySettings(replaceUnconditionally = true)

  private suspend fun applySettings(replaceUnconditionally: Boolean) {
    val settings = autoAnalysisSettingsRepository.autoAnalysisSettings.first()
    if (!settings.enabled) {
      workManager.cancelUniqueWork(WORK_NAME)
      clearScheduledZoneId()
      return
    }
    val zoneId = currentZoneId()
    val policy = autoAnalysisWorkPolicy(
      replaceUnconditionally = replaceUnconditionally,
      scheduledZoneId = scheduledZoneId(),
      currentZoneId = zoneId.id,
    )
    val request = OneTimeWorkRequestBuilder<AutoAnalysisWorker>()
      .setInitialDelay(
        nextRunDelay(now(), zoneId, settings.timeOfDay).toMillis(),
        TimeUnit.MILLISECONDS,
      )
      .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
      .build()
    workManager.enqueueUniqueWork(WORK_NAME, policy, request)
    setScheduledZoneId(zoneId.id)
  }

  private suspend fun scheduledZoneId(): String? =
    try {
      stateDataStore.data.first()[KEY_SCHEDULED_ZONE_ID]
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // 読めないときは「変わっている」扱いにして計算し直す(安全側)。
      null
    }

  private suspend fun setScheduledZoneId(zoneId: String) {
    stateDataStore.edit { it[KEY_SCHEDULED_ZONE_ID] = zoneId }
  }

  private suspend fun clearScheduledZoneId() {
    stateDataStore.edit { it.remove(KEY_SCHEDULED_ZONE_ID) }
  }

  companion object {
    const val WORK_NAME = "auto_analysis"
    private val KEY_SCHEDULED_ZONE_ID = stringPreferencesKey("scheduled_zone_id")
  }
}

/**
 * `enqueueUniqueWork` へ渡すpolicy。設定変更・Worker実行後は無条件に置き換える。アプリ起動時の
 * 「予約を確保する」経路では基本は維持([ExistingWorkPolicy.KEEP])するが、予約した時点の
 * timezoneと現在のtimezoneが違えば置き換えて指定時刻へ寄せ直す。
 */
internal fun autoAnalysisWorkPolicy(
  replaceUnconditionally: Boolean,
  scheduledZoneId: String?,
  currentZoneId: String,
): ExistingWorkPolicy =
  if (replaceUnconditionally || scheduledZoneId != currentZoneId) {
    ExistingWorkPolicy.REPLACE
  } else {
    ExistingWorkPolicy.KEEP
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
