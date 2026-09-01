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
 * 「同じ意図」かどうかは対象期間と、送信するrequest payloadのfingerprintで判定する。timeout後に
 * 記録を追加・削除してから同じ日を解析し直した場合はfingerprintが変わるため新しいkeyになり、Server側の
 * `idempotency_key_reuse` 衝突を避けられる。payloadが同じretryだけが同じkeyを再利用する。
 *
 * keyは、恒久的な失敗(4xx等)で[clear]するか、Serverのretry buffer保持期間(30分)を過ぎたときに
 * 作り直す。成功時は消さない。端末保存(Room write)が失敗しても、同じkeyのretryがbufferから同じ結果を
 * 引けるようにするためで、期限切れまでに保存できなければ次の解析として扱われる。
 */
interface HostedIdempotencyKeyStore {
  /**
   * [period]と[requestFingerprint]の実行に使うkey。同じfingerprintの未確定retryなら既存のkeyを、
   * それ以外(fingerprintが変わった／期限切れ／未保存)は新しいkeyを返す。
   */
  suspend fun currentKey(period: HostedAnalysisPeriod, requestFingerprint: String): String

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
  override suspend fun currentKey(period: HostedAnalysisPeriod, requestFingerprint: String): String {
    val stored = readEntries()[period.identity]
    if (
      stored != null &&
      stored.fingerprint == requestFingerprint &&
      Duration.between(Instant.ofEpochMilli(stored.createdAtEpochMillis), now()) < RETENTION
    ) {
      return stored.key
    }
    val fresh = StoredKey(
      key = UUID.randomUUID().toString(),
      fingerprint = requestFingerprint,
      createdAtEpochMillis = now().toEpochMilli(),
    )
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
  private data class StoredKey(val key: String, val fingerprint: String, val createdAtEpochMillis: Long)

  private companion object {
    val KEY_ENTRIES = stringPreferencesKey("idempotency_keys")

    // JournalingPostServer #4 のretry result buffer保持期間(30分)。これを過ぎた再送は
    // Server側でも新しい解析として扱われるため、keyを使い回す意味がない。
    val RETENTION: Duration = Duration.ofMinutes(30)
  }
}
