package info.bvlion.journalingpost.hosted

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 対象期間ごとのIdempotency-Keyを覚えておく。
 *
 * network timeout等で成功responseを受け取れなかった「同じ意図の解析retry」では同じkeyを使い、
 * Server側の短期retry result bufferから同じ結果を取得して不要なAI再課金を防ぐ(JournalingPostServer #40)。
 * 利用者が意図して新しい解析を行う場合は別のkeyへ切り替える。
 *
 * この区別は保存状態から導く。解析が確定した(結果を端末保存できた／恒久的な失敗だった)時点で
 * [clear]し、その後の同じ日の実行は新しいkeyになる。確定しなかった失敗ではkeyを残し、次の実行が
 * retryとして同じkeyを使う。keyはServerのretry buffer保持期間(30分)を過ぎたら作り直す。
 */
interface HostedIdempotencyKeyStore {
  /** [period]の実行に使うkey。未確定のretryなら既存のkeyを、それ以外は新しいkeyを返す。 */
  suspend fun currentKey(period: HostedAnalysisPeriod): String

  suspend fun clear(period: HostedAnalysisPeriod)
}

/** 解析対象期間。Idempotency-Keyの単位。 */
data class HostedAnalysisPeriod(val start: Instant, val end: Instant) {
  internal val identity: String get() = "$start/$end"
}

internal class DataStoreHostedIdempotencyKeyStore(
  private val dataStore: DataStore<Preferences>,
  private val now: () -> Instant = Instant::now,
) : HostedIdempotencyKeyStore {
  override suspend fun currentKey(period: HostedAnalysisPeriod): String {
    val stored = readEntries()[period.identity]
    if (stored != null && Duration.between(Instant.ofEpochMilli(stored.createdAtEpochMillis), now()) < RETENTION) {
      return stored.key
    }
    val fresh = StoredKey(key = UUID.randomUUID().toString(), createdAtEpochMillis = now().toEpochMilli())
    writeEntries(readEntries() + (period.identity to fresh))
    return fresh.key
  }

  override suspend fun clear(period: HostedAnalysisPeriod) {
    writeEntries(readEntries() - period.identity)
  }

  private suspend fun readEntries(): Map<String, StoredKey> {
    val raw = dataStore.data.first()[KEY_ENTRIES] ?: return emptyMap()
    return try {
      Json.decodeFromString<Map<String, StoredKey>>(raw)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      emptyMap()
    }
  }

  private suspend fun writeEntries(entries: Map<String, StoredKey>) {
    // 期限切れのentryは同時に掃除しておく(端末に不要なkeyを溜めない)。
    val kept = entries.filterValues {
      Duration.between(Instant.ofEpochMilli(it.createdAtEpochMillis), now()) < RETENTION
    }
    dataStore.edit { preferences ->
      if (kept.isEmpty()) preferences.remove(KEY_ENTRIES) else preferences[KEY_ENTRIES] = Json.encodeToString(kept)
    }
  }

  @Serializable
  private data class StoredKey(val key: String, val createdAtEpochMillis: Long)

  private companion object {
    val KEY_ENTRIES = stringPreferencesKey("idempotency_keys")

    // JournalingPostServer #4 のretry result buffer保持期間(30分)。これを過ぎた再送は
    // Server側でも新しい解析として扱われるため、keyを使い回す意味がない。
    val RETENTION: Duration = Duration.ofMinutes(30)
  }
}
