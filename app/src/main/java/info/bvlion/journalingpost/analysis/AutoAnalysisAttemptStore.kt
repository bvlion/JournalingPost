package info.bvlion.journalingpost.analysis

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import info.bvlion.journalingpost.journal.JournalEntry
import info.bvlion.journalingpost.journal.JournalSource
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Hostedの自動解析を「その実行日に既に試行したか」を覚えておく(Issue #59)。
 *
 * Hosted自動解析の新規開始は成功・失敗にかかわらず実行日ごとに最大1回。対象日のAnalysisResult有無
 * だけでは、解析が失敗した日や、成功後に対象日を切り替えた日に、設定変更→再予約で同じ実行日の別の
 * 新規解析が起きてしまう。実行日そのものを記録して判定する。
 *
 * 日付は端末timezoneでのカレンダー日。Custom Webhookの自動解析にはこの上限が無いため記録しない。
 * Hosted retry中は、別Workerやプロセス再生成を跨いでもrequestを変えないよう対象日・期間・記録も保存する。
 */
internal interface AutoAnalysisAttemptStore {
  /** 最後にHosted自動解析を試行した実行日。まだ無ければnull。 */
  suspend fun lastHostedAttemptDate(): LocalDate?

  suspend fun recordHostedAttempt(date: LocalDate)

  suspend fun hostedRetrySnapshot(): HostedAutoAnalysisRetrySnapshot?

  suspend fun storeHostedRetrySnapshot(snapshot: HostedAutoAnalysisRetrySnapshot)

  suspend fun clearHostedRetrySnapshot()
}

internal data class HostedAutoAnalysisRetrySnapshot(
  val analysisDate: LocalDate,
  val periodStart: Instant,
  val periodEnd: Instant,
  val entries: List<JournalEntry>,
)

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

  override suspend fun hostedRetrySnapshot(): HostedAutoAnalysisRetrySnapshot? {
    val raw = dataStore.data.first()[KEY_HOSTED_RETRY_SNAPSHOT] ?: return null
    return try {
      val stored = Json.decodeFromString<StoredRetrySnapshot>(raw)
      HostedAutoAnalysisRetrySnapshot(
        analysisDate = LocalDate.parse(stored.analysisDate),
        periodStart = Instant.parse(stored.periodStart),
        periodEnd = Instant.parse(stored.periodEnd),
        entries = stored.entries.map { entry ->
          JournalEntry(
            id = entry.id,
            timestamp = Instant.parse(entry.timestamp),
            moodId = entry.moodId,
            moodEmoji = entry.moodEmoji,
            moodLabel = entry.moodLabel,
            note = entry.note,
            source = JournalSource.valueOf(entry.source),
          )
        },
      )
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      null
    }
  }

  override suspend fun storeHostedRetrySnapshot(snapshot: HostedAutoAnalysisRetrySnapshot) {
    val stored = StoredRetrySnapshot(
      analysisDate = snapshot.analysisDate.toString(),
      periodStart = snapshot.periodStart.toString(),
      periodEnd = snapshot.periodEnd.toString(),
      entries = snapshot.entries.map { entry ->
        StoredJournalEntry(
          id = entry.id,
          timestamp = entry.timestamp.toString(),
          moodId = entry.moodId,
          moodEmoji = entry.moodEmoji,
          moodLabel = entry.moodLabel,
          note = entry.note,
          source = entry.source.name,
        )
      },
    )
    dataStore.edit { it[KEY_HOSTED_RETRY_SNAPSHOT] = Json.encodeToString(stored) }
  }

  override suspend fun clearHostedRetrySnapshot() {
    dataStore.edit { it.remove(KEY_HOSTED_RETRY_SNAPSHOT) }
  }

  @Serializable
  private data class StoredRetrySnapshot(
    val analysisDate: String,
    val periodStart: String,
    val periodEnd: String,
    val entries: List<StoredJournalEntry>,
  )

  @Serializable
  private data class StoredJournalEntry(
    val id: Long,
    val timestamp: String,
    val moodId: String?,
    val moodEmoji: String?,
    val moodLabel: String?,
    val note: String?,
    val source: String,
  )

  private companion object {
    val KEY_LAST_HOSTED_ATTEMPT_EPOCH_DAY = longPreferencesKey("last_hosted_auto_analysis_attempt_epoch_day")
    val KEY_HOSTED_RETRY_SNAPSHOT = stringPreferencesKey("hosted_auto_analysis_retry_snapshot")
  }
}
