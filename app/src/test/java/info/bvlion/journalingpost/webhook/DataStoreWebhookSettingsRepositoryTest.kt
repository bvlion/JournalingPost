package info.bvlion.journalingpost.webhook

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreWebhookSettingsRepositoryTest {
  @get:Rule
  val tempFolder = TemporaryFolder()

  private val sampleSettings = WebhookSettings(
    url = "https://example.com/webhook",
    headers = listOf(WebhookHeader("Authorization", "Bearer xxxxx")),
    bodyTemplate = """{"text": "{{message}}"}""",
  )

  private fun createRepository(cipher: WebhookSettingsCipher = FakeWebhookSettingsCipher()): DataStoreWebhookSettingsRepository {
    val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
      produceFile = { File(tempFolder.root, "webhook_settings.preferences_pb") },
    )
    return DataStoreWebhookSettingsRepository(dataStore, cipher)
  }

  @Test
  fun `初期状態は未設定になる`() = runTest {
    val repository = createRepository()

    assertNull(repository.settings.first())
  }

  @Test
  fun `saveした設定全体を再取得できる`() = runTest {
    val repository = createRepository()

    repository.save(sampleSettings)

    assertEquals(sampleSettings, repository.settings.first())
  }

  @Test
  fun `save内容はcipherのencryptへ渡される`() = runTest {
    val cipher = FakeWebhookSettingsCipher()
    val repository = createRepository(cipher)

    repository.save(sampleSettings)

    val plaintext = requireNotNull(cipher.lastEncryptedPlaintext).toByteArray().decodeToString()
    assertTrue(plaintext.contains(sampleSettings.url))
  }

  @Test
  fun `clearすると設定は未設定になる`() = runTest {
    val repository = createRepository()
    repository.save(sampleSettings)

    repository.clear()

    assertNull(repository.settings.first())
  }

  @Test
  fun `clearしてもlegacy migration済み状態は維持される`() = runTest {
    val repository = createRepository()
    repository.markLegacyMigrationCompleted()
    repository.save(sampleSettings)

    repository.clear()

    assertTrue(repository.isLegacyMigrationCompleted())
  }

  @Test
  fun `isLegacyMigrationCompletedの初期状態はfalse`() = runTest {
    val repository = createRepository()

    assertEquals(false, repository.isLegacyMigrationCompleted())
  }

  @Test
  fun `markLegacyMigrationCompleted後はtrueを返す`() = runTest {
    val repository = createRepository()

    repository.markLegacyMigrationCompleted()

    assertTrue(repository.isLegacyMigrationCompleted())
  }

  @Test
  fun `復号に失敗した場合は未設定として扱われる`() = runTest {
    val cipher = FakeWebhookSettingsCipher()
    val repository = createRepository(cipher)
    repository.save(sampleSettings)
    cipher.failDecrypt = true

    assertNull(repository.settings.first())
  }

  @Test
  fun `別のrepositoryインスタンスからも保存した設定を読める`() = runTest {
    val dataStore = PreferenceDataStoreFactory.create(
      produceFile = { File(tempFolder.root, "webhook_settings.preferences_pb") },
    )
    val cipher = FakeWebhookSettingsCipher()
    DataStoreWebhookSettingsRepository(dataStore, cipher).save(sampleSettings)

    val reloaded = DataStoreWebhookSettingsRepository(dataStore, cipher)

    assertEquals(sampleSettings, reloaded.settings.first())
  }

  @Test
  fun `DataStoreへのwrite完了前でもsave呼び出し直後にsettingsへ反映される`() = runTest {
    val repository = DataStoreWebhookSettingsRepository(BlockingWriteDataStore(), FakeWebhookSettingsCipher())

    backgroundScope.launch { repository.save(sampleSettings) }
    runCurrent() // BlockingWriteDataStoreのwriteは完了しないため、pendingSettings反映直後まで進む

    assertEquals(sampleSettings, repository.settings.first())
  }

  @Test
  fun `DataStore読み込みがIOExceptionを投げた場合は未設定へ倒す`() = runTest {
    val repository = DataStoreWebhookSettingsRepository(ThrowingDataStore(IOException("disk error")), FakeWebhookSettingsCipher())

    assertNull(repository.settings.first())
  }

  @Test
  fun `DataStore読み込みがIOException以外を投げた場合は再送出される`() = runTest {
    val repository = DataStoreWebhookSettingsRepository(ThrowingDataStore(IllegalStateException("boom")), FakeWebhookSettingsCipher())

    var thrown: Throwable? = null
    try {
      repository.settings.first()
    } catch (e: IllegalStateException) {
      thrown = e
    }

    assertEquals("boom", thrown?.message)
  }

  @Test
  fun `read IOExceptionから復旧すると同じ購読が新しい永続設定を取得できる`() = runTest {
    val repository = DataStoreWebhookSettingsRepository(RecoveringDataStore(), FakeWebhookSettingsCipher())

    val collected = mutableListOf<WebhookSettings?>()
    val job = launch { repository.settings.collect { collected += it } }
    advanceUntilIdle()

    assertEquals(listOf(null, sampleSettings), collected)
    job.cancel()
  }

  @Test
  fun `write中にキャンセルされてもCancellationExceptionが伝播しそのpendingは片付く`() = runTest {
    val repository = DataStoreWebhookSettingsRepository(BlockingWriteDataStore(), FakeWebhookSettingsCipher())
    var thrown: Throwable? = null

    val job = launch {
      try {
        repository.save(sampleSettings)
      } catch (e: CancellationException) {
        thrown = e
        throw e
      }
    }
    runCurrent()
    assertEquals(sampleSettings, repository.settings.first())

    job.cancelAndJoin()

    assertTrue(thrown is CancellationException)
    assertNull(repository.settings.first())
  }

  @Test
  fun `write失敗時はsaveが例外を投げる`() = runTest {
    val repository = DataStoreWebhookSettingsRepository(FailingWriteDataStore(IOException("disk error")), FakeWebhookSettingsCipher())

    var thrown: Throwable? = null
    try {
      repository.save(sampleSettings)
    } catch (e: IOException) {
      thrown = e
    }

    assertEquals("disk error", thrown?.message)
  }

  @Test
  fun `write失敗後はsettingsが永続化前の状態へ戻る`() = runTest {
    val repository = DataStoreWebhookSettingsRepository(FailingWriteDataStore(IOException("disk error")), FakeWebhookSettingsCipher())
    assertNull(repository.settings.first())

    try {
      repository.save(sampleSettings)
    } catch (e: IOException) {
    }

    assertNull(repository.settings.first())
  }

  private class FakeWebhookSettingsCipher(var failDecrypt: Boolean = false) : WebhookSettingsCipher {
    var lastEncryptedPlaintext: List<Byte>? = null

    override fun encrypt(plaintext: ByteArray): EncryptedWebhookSettings {
      lastEncryptedPlaintext = plaintext.toList()
      return EncryptedWebhookSettings(ciphertext = plaintext, iv = byteArrayOf(1, 2, 3))
    }

    override fun decrypt(encrypted: EncryptedWebhookSettings): ByteArray {
      if (failDecrypt) throw GeneralSecurityException("decrypt failed")
      return encrypted.ciphertext
    }
  }

  private class BlockingWriteDataStore : DataStore<Preferences> {
    override val data: Flow<Preferences> = MutableStateFlow(emptyPreferences())

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
      delay(Long.MAX_VALUE)
      error("unreachable: このテストではwriteを意図的に完了させない")
    }
  }

  private class FailingWriteDataStore(private val error: Throwable) : DataStore<Preferences> {
    override val data: Flow<Preferences> = MutableStateFlow(emptyPreferences())

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw error
  }

  private class ThrowingDataStore(private val error: Throwable) : DataStore<Preferences> {
    override val data: Flow<Preferences> = flow { throw error }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
      error("not used in this test")
  }

  /** dataの最初の購読だけIOExceptionを投げ、以降はbackingを反映するFake。 */
  private inner class RecoveringDataStore : DataStore<Preferences> {
    private val backing = MutableStateFlow(emptyPreferences())
    private var readAttempt = 0

    override val data: Flow<Preferences> = flow {
      readAttempt++
      if (readAttempt == 1) throw IOException("disk error")
      emitAll(backing)
    }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
      val updated = transform(backing.value)
      backing.value = updated
      return updated
    }

    init {
      // テスト対象と同じcipher(平文=ciphertext)で、復旧後に読める永続値をあらかじめ書き込む。
      val cipher = FakeWebhookSettingsCipher()
      val plaintext = Json.encodeToString(sampleSettings).encodeToByteArray()
      val encrypted = cipher.encrypt(plaintext)
      backing.value = emptyPreferences().toMutablePreferences().apply {
        this[stringPreferencesKey("ciphertext")] = Base64.getEncoder().encodeToString(encrypted.ciphertext)
        this[stringPreferencesKey("iv")] = Base64.getEncoder().encodeToString(encrypted.iv)
      }
    }
  }
}
