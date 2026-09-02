package info.bvlion.journalingpost.hosted

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import info.bvlion.journalingpost.security.EncryptedPayload
import info.bvlion.journalingpost.security.KeystoreCipher
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Hosted API keyを暗号化してDataStoreへ保存する。Webhook設定と同じく、暗号化した本文と
 * IVをBase64でPreferencesへ入れる。鍵はAndroid Keystore管理でファイルへ書き出さない。
 */
internal class DataStoreHostedCredentialsRepository(
  private val dataStore: DataStore<Preferences>,
  private val cipher: KeystoreCipher,
) : HostedCredentialsRepository {
  override suspend fun apiKey(): String? {
    val preferences = dataStore.data.first()
    val ciphertextBase64 = preferences[KEY_CIPHERTEXT] ?: return null
    val ivBase64 = preferences[KEY_IV] ?: return null
    return try {
      val plaintext = cipher.decrypt(
        EncryptedPayload(
          ciphertext = Base64.getDecoder().decode(ciphertextBase64),
          iv = Base64.getDecoder().decode(ivBase64),
        ),
      ).toString(Charsets.UTF_8)
      Json.decodeFromString<StoredCredentials>(plaintext).apiKey.ifBlank { null }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // 復号できない保存値では認証せず、未登録として扱う。
      null
    }
  }

  override suspend fun store(apiKey: String) {
    val plaintext = Json.encodeToString(StoredCredentials(apiKey)).encodeToByteArray()
    val encrypted = cipher.encrypt(plaintext)
    dataStore.edit { preferences ->
      preferences[KEY_CIPHERTEXT] = Base64.getEncoder().encodeToString(encrypted.ciphertext)
      preferences[KEY_IV] = Base64.getEncoder().encodeToString(encrypted.iv)
    }
  }

  override suspend fun clear() {
    dataStore.edit { preferences ->
      preferences.remove(KEY_CIPHERTEXT)
      preferences.remove(KEY_IV)
    }
  }

  @Serializable
  private data class StoredCredentials(val apiKey: String)

  private companion object {
    val KEY_CIPHERTEXT = stringPreferencesKey("ciphertext")
    val KEY_IV = stringPreferencesKey("iv")
  }
}
