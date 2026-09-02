package info.bvlion.journalingpost.analysis

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * 自動解析(Issue #59)の実行状態。設定([info.bvlion.journalingpost.settings.AutoAnalysisSettings])
 * とは別の、runtimeで更新される値をまとめて持つ。
 *
 * - Hostedを最後に試行した実行日: Hostedの自動解析は成功・失敗にかかわらず実行日ごとに最大1回。
 * - 予約に使った端末timezone: 予約後にtimezoneが変わった場合、その予約は指定ローカル時刻から
 *   ずれている。予約の立て直し判定と、Worker実行時に「ずれた予約では解析しない」判定に使う。
 *
 * 日付・timezoneは端末timezoneでのカレンダー日 / [java.time.ZoneId.getId]。
 */
internal interface AutoAnalysisStateStore {
  suspend fun lastHostedAttemptDate(): LocalDate?

  suspend fun recordHostedAttempt(date: LocalDate)

  /** 直近の予約に使った端末timezoneのid。まだ予約していなければnull。 */
  suspend fun scheduledZoneId(): String?

  suspend fun setScheduledZoneId(zoneId: String)

  suspend fun clearScheduledZoneId()
}

internal class DataStoreAutoAnalysisStateStore(
  private val dataStore: DataStore<Preferences>,
) : AutoAnalysisStateStore {
  override suspend fun lastHostedAttemptDate(): LocalDate? =
    read { it[KEY_LAST_HOSTED_ATTEMPT_EPOCH_DAY]?.let(LocalDate::ofEpochDay) }

  override suspend fun recordHostedAttempt(date: LocalDate) {
    dataStore.edit { it[KEY_LAST_HOSTED_ATTEMPT_EPOCH_DAY] = date.toEpochDay() }
  }

  override suspend fun scheduledZoneId(): String? = read { it[KEY_SCHEDULED_ZONE_ID] }

  override suspend fun setScheduledZoneId(zoneId: String) {
    dataStore.edit { it[KEY_SCHEDULED_ZONE_ID] = zoneId }
  }

  override suspend fun clearScheduledZoneId() {
    dataStore.edit { it.remove(KEY_SCHEDULED_ZONE_ID) }
  }

  /**
   * 読めないときはnull(未記録扱い)に倒す。Hosted試行日は「未試行」、予約timezoneは「不明」となり、
   * どちらも安全側(重複送信を避ける / 予約を立て直す)へ寄る。
   */
  private suspend inline fun <T> read(crossinline select: (Preferences) -> T?): T? =
    try {
      select(dataStore.data.first())
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      null
    }

  private companion object {
    val KEY_LAST_HOSTED_ATTEMPT_EPOCH_DAY = longPreferencesKey("last_hosted_auto_analysis_attempt_epoch_day")
    val KEY_SCHEDULED_ZONE_ID = stringPreferencesKey("scheduled_zone_id")
  }
}
