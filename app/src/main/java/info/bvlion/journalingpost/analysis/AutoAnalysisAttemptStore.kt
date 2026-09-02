package info.bvlion.journalingpost.analysis

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Hostedの自動解析を「その実行日に既に試行したか」を覚えておく(Issue #59)。
 *
 * Hostedの自動解析は成功・失敗にかかわらず実行日ごとに最大1回。対象日のAnalysisResult有無だけでは、
 * 解析が失敗した日や、成功後に対象日を切り替えた日に、設定変更→再予約で同じ実行日の2回目の試行が
 * 起きてしまう。実行日そのものを記録して判定する。
 *
 * 日付は端末timezoneでのカレンダー日。Custom Webhookの自動解析にはこの上限が無いため記録しない。
 */
internal interface AutoAnalysisAttemptStore {
  /** 最後にHosted自動解析を試行した実行日。まだ無ければnull。 */
  suspend fun lastHostedAttemptDate(): LocalDate?

  suspend fun recordHostedAttempt(date: LocalDate)
}

internal class DataStoreAutoAnalysisAttemptStore(
  private val dataStore: DataStore<Preferences>,
) : AutoAnalysisAttemptStore {
  override suspend fun lastHostedAttemptDate(): LocalDate? =
    try {
      dataStore.data.first()[KEY_LAST_HOSTED_ATTEMPT_EPOCH_DAY]?.let(LocalDate::ofEpochDay)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // 読めないときは「未試行」に倒す。安全側(送信を止める)ではなく、
      // 自動解析が一度も走らなくなる方を避ける。重複は次段のIdempotency-Keyでも軽減される。
      null
    }

  override suspend fun recordHostedAttempt(date: LocalDate) {
    dataStore.edit { it[KEY_LAST_HOSTED_ATTEMPT_EPOCH_DAY] = date.toEpochDay() }
  }

  private companion object {
    val KEY_LAST_HOSTED_ATTEMPT_EPOCH_DAY = longPreferencesKey("last_hosted_auto_analysis_attempt_epoch_day")
  }
}
