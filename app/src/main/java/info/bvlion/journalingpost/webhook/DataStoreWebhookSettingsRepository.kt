package info.bvlion.journalingpost.webhook

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class DataStoreWebhookSettingsRepository(
  private val dataStore: DataStore<Preferences>,
  private val cipher: WebhookSettingsCipher,
) : WebhookSettingsRepository {
  /**
   * write完了前でも編集直後の設定をrepositoryへ即時反映するための値。#5のDataStoreRecordModeRepositoryと
   * 同様、generationは複数回のsave/clearが重なった際、古い呼び出しのwrite完了/失敗/キャンセルが
   * より新しい変更を誤って上書き・巻き戻ししないよう、pendingSettingsをクリアする際に確認する世代番号。
   */
  private val pendingSettings = MutableStateFlow<PendingSettings?>(null)
  private val generation = AtomicInteger(0)

  private val persistedState: Flow<WebhookSettingsState> = dataStore.data
    .map { preferences -> preferences.toWebhookSettingsState() }
    .retryWhen { cause, _ ->
      // 読み取れない場合は安全側(Unavailable)へ一旦フォールバックしたうえで再購読を試み続ける。
      // IOException以外は再送出する(リトライしない)。
      if (cause !is IOException) return@retryWhen false
      emit(WebhookSettingsState.Unavailable)
      delay(RETRY_DELAY_MILLIS)
      true
    }

  // pendingSettingsがnull(未pending)であることと、clear()によるpending中の削除を
  // Elvis演算子1つで区別すると両方とも「persistedへfall back」になってしまい、削除直後もDataStore
  // write完了までは旧設定を返してしまう。PendingSettingsをsealedにして両者を型で区別する。
  override val settings: Flow<WebhookSettingsState> = combine(persistedState, pendingSettings) { persisted, pending ->
    when (pending) {
      null -> persisted
      is PendingSettings.Save -> WebhookSettingsState.Configured(pending.settings)
      is PendingSettings.Clear -> WebhookSettingsState.NotConfigured
    }
  }

  override suspend fun save(settings: WebhookSettings) {
    val pending = PendingSettings.Save(settings, generation.incrementAndGet())
    pendingSettings.value = pending
    try {
      val plaintext = Json.encodeToString(settings).encodeToByteArray()
      val encrypted = cipher.encrypt(plaintext)
      dataStore.edit { preferences ->
        preferences[KEY_CIPHERTEXT] = Base64.getEncoder().encodeToString(encrypted.ciphertext)
        preferences[KEY_IV] = Base64.getEncoder().encodeToString(encrypted.iv)
      }
    } finally {
      pendingSettings.compareAndSet(pending, null)
    }
  }

  override suspend fun clear() {
    val pending = PendingSettings.Clear(generation.incrementAndGet())
    pendingSettings.value = pending
    try {
      dataStore.edit { preferences ->
        preferences.remove(KEY_CIPHERTEXT)
        preferences.remove(KEY_IV)
      }
    } finally {
      pendingSettings.compareAndSet(pending, null)
    }
  }

  override suspend fun isLegacyMigrationCompleted(): Boolean =
    dataStore.data.first()[KEY_MIGRATION_COMPLETED] ?: false

  override suspend fun markLegacyMigrationCompleted() {
    dataStore.edit { it[KEY_MIGRATION_COMPLETED] = true }
  }

  private fun Preferences.toWebhookSettingsState(): WebhookSettingsState {
    val ciphertextBase64 = this[KEY_CIPHERTEXT] ?: return WebhookSettingsState.NotConfigured
    val ivBase64 = this[KEY_IV] ?: return WebhookSettingsState.NotConfigured
    return try {
      val encrypted = EncryptedWebhookSettings(
        ciphertext = Base64.getDecoder().decode(ciphertextBase64),
        iv = Base64.getDecoder().decode(ivBase64),
      )
      val plaintext = cipher.decrypt(encrypted).toString(Charsets.UTF_8)
      WebhookSettingsState.Configured(Json.decodeFromString<WebhookSettings>(plaintext))
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // 復号鍵の紛失・データ破損・format不整合等はネットワーク送信を止める安全側(未設定扱い)にする。
      WebhookSettingsState.NotConfigured
    }
  }

  private sealed interface PendingSettings {
    val generation: Int

    data class Save(val settings: WebhookSettings, override val generation: Int) : PendingSettings
    data class Clear(override val generation: Int) : PendingSettings
  }

  private companion object {
    val KEY_CIPHERTEXT = stringPreferencesKey("ciphertext")
    val KEY_IV = stringPreferencesKey("iv")
    val KEY_MIGRATION_COMPLETED = booleanPreferencesKey("legacy_migration_completed")
    const val RETRY_DELAY_MILLIS = 1_000L
  }
}
