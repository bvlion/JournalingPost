package info.bvlion.journalingpost.webhook

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreWebhookSettingsRepositoryTest {
  @get:Rule
  val tempFolder = TemporaryFolder()

  private val sampleSettings = WebhookSettings(
    url = "https://example.com/webhook",
    headers = listOf(WebhookHeader("Authorization", "Bearer xxxxx")),
    bodyTemplate = """{"entries": {{entries}}}""",
  )

  private fun createRepository(cipher: WebhookSettingsCipher = FakeWebhookSettingsCipher()): DataStoreWebhookSettingsRepository {
    val dataStore = PreferenceDataStoreFactory.create(
      produceFile = { File(tempFolder.root, "webhook_settings.preferences_pb") },
    )
    return DataStoreWebhookSettingsRepository(dataStore, cipher)
  }

  @Test
  fun `初期状態は未設定になる`() = runTest {
    assertEquals(WebhookSettingsState.NotConfigured, createRepository().settings.first())
  }

  @Test
  fun `saveした設定を再取得できる`() = runTest {
    val repository = createRepository()

    repository.save(sampleSettings)

    assertEquals(WebhookSettingsState.Configured(sampleSettings), repository.settings.first())
  }

  @Test
  fun `save内容はcipherへ渡される`() = runTest {
    val cipher = FakeWebhookSettingsCipher()
    val repository = createRepository(cipher)

    repository.save(sampleSettings)

    assertTrue(requireNotNull(cipher.lastEncryptedPlaintext).decodeToString().contains(sampleSettings.url))
  }

  @Test
  fun `復号できない保存値は未設定として扱う`() = runTest {
    val cipher = FakeWebhookSettingsCipher()
    val repository = createRepository(cipher)
    repository.save(sampleSettings)
    cipher.failDecrypt = true

    assertEquals(WebhookSettingsState.NotConfigured, repository.settings.first())
  }

  @Test
  fun `DataStore読み込みがIOExceptionならUnavailableを返す`() = runTest {
    val repository = DataStoreWebhookSettingsRepository(
      ThrowingDataStore(IOException("disk error")),
      FakeWebhookSettingsCipher(),
    )

    assertEquals(WebhookSettingsState.Unavailable, repository.settings.first())
  }

  @Test
  fun `DataStore読み込みの非IOExceptionは再送出する`() = runTest {
    val repository = DataStoreWebhookSettingsRepository(
      ThrowingDataStore(IllegalStateException("boom")),
      FakeWebhookSettingsCipher(),
    )

    var thrown: Throwable? = null
    try {
      repository.settings.first()
    } catch (e: IllegalStateException) {
      thrown = e
    }

    assertEquals("boom", thrown?.message)
  }

  @Test
  fun `読み込みIOExceptionから復旧すると保存済み設定を取得できる`() = runTest {
    val repository = DataStoreWebhookSettingsRepository(
      RecoveringDataStore(configuredPreferences(sampleSettings)),
      FakeWebhookSettingsCipher(),
    )

    val first = repository.settings.first()
    val second = repository.settings.first { it is WebhookSettingsState.Configured }

    assertEquals(WebhookSettingsState.Unavailable, first)
    assertEquals(WebhookSettingsState.Configured(sampleSettings), second)
  }

  @Test
  fun `write失敗時は例外を返し未設定のままになる`() = runTest {
    val repository = DataStoreWebhookSettingsRepository(
      FailingWriteDataStore(IOException("disk error")),
      FakeWebhookSettingsCipher(),
    )

    var thrown: Throwable? = null
    try {
      repository.save(sampleSettings)
    } catch (e: IOException) {
      thrown = e
    }

    assertEquals("disk error", thrown?.message)
    assertEquals(WebhookSettingsState.NotConfigured, repository.settings.first())
  }

  private fun configuredPreferences(settings: WebhookSettings): Preferences {
    val plaintext = Json.encodeToString(settings).encodeToByteArray()
    return preferencesOf(
      stringPreferencesKey("ciphertext") to Base64.getEncoder().encodeToString(plaintext),
      stringPreferencesKey("iv") to Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)),
    )
  }

  private class FakeWebhookSettingsCipher : WebhookSettingsCipher {
    var failDecrypt = false
    var lastEncryptedPlaintext: ByteArray? = null

    override fun encrypt(plaintext: ByteArray): EncryptedWebhookSettings {
      lastEncryptedPlaintext = plaintext
      return EncryptedWebhookSettings(plaintext, byteArrayOf(1, 2, 3))
    }

    override fun decrypt(encrypted: EncryptedWebhookSettings): ByteArray {
      if (failDecrypt) throw GeneralSecurityException("decrypt failed")
      return encrypted.ciphertext
    }
  }

  private class ThrowingDataStore(private val error: Throwable) : DataStore<Preferences> {
    override val data: Flow<Preferences> = flow { throw error }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
      error("not used in this test")
  }

  private class RecoveringDataStore(private val recovered: Preferences) : DataStore<Preferences> {
    private var attempt = 0

    override val data: Flow<Preferences> = flow {
      attempt++
      if (attempt == 1) throw IOException("disk error")
      emitAll(flowOf(recovered))
    }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
      error("not used in this test")
  }

  private class FailingWriteDataStore(private val error: Throwable) : DataStore<Preferences> {
    override val data: Flow<Preferences> = flowOf(emptyPreferences())

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw error
  }
}
