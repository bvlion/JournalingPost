package info.bvlion.journalingpost.webhook

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class DataStoreWebhookSettingsRepository(
  private val dataStore: DataStore<Preferences>,
  private val cipher: WebhookSettingsCipher,
) : WebhookSettingsRepository {
  override val settings: Flow<WebhookSettingsState> = dataStore.data
    .map { preferences -> preferences.toWebhookSettingsState() }
    .retryWhen { cause, _ ->
      if (cause !is IOException) return@retryWhen false
      emit(WebhookSettingsState.Unavailable)
      delay(RETRY_DELAY_MILLIS)
      true
    }

  override suspend fun save(settings: WebhookSettings) {
    val plaintext = Json.encodeToString(settings).encodeToByteArray()
    val encrypted = cipher.encrypt(plaintext)
    dataStore.edit { preferences ->
      preferences[KEY_CIPHERTEXT] = Base64.getEncoder().encodeToString(encrypted.ciphertext)
      preferences[KEY_IV] = Base64.getEncoder().encodeToString(encrypted.iv)
    }
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
      // 復号できない設定で外部送信しないため、未設定として扱う。
      WebhookSettingsState.NotConfigured
    }
  }

  private companion object {
    val KEY_CIPHERTEXT = stringPreferencesKey("ciphertext")
    val KEY_IV = stringPreferencesKey("iv")
    const val RETRY_DELAY_MILLIS = 1_000L
  }
}
