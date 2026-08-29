package info.bvlion.journalingpost.webhook

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WebhookSettingsRepositoryStoreTest {
  @get:Rule
  val tempFolder = TemporaryFolder()

  @Before
  fun setUp() {
    WebhookSettingsRepositoryStore.resetForTesting()
  }

  @Test
  fun `getInstanceは同じrepositoryインスタンスを返す`() {
    val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
      produceFile = { File(tempFolder.root, "webhook_settings.preferences_pb") },
    )
    val cipher = object : WebhookSettingsCipher {
      override fun encrypt(plaintext: ByteArray) = EncryptedWebhookSettings(plaintext, byteArrayOf())
      override fun decrypt(encrypted: EncryptedWebhookSettings) = encrypted.ciphertext
    }

    val first = WebhookSettingsRepositoryStore.getInstance(dataStore, cipher)
    val second = WebhookSettingsRepositoryStore.getInstance(dataStore, cipher)

    assertSame(first, second)
  }

  @Test
  fun `repository生成factoryは初回だけ実行する`() {
    val createCallCount = AtomicInteger(0)
    val createRepository = { createCallCount.incrementAndGet(); FakeWebhookSettingsRepository() }

    val first = WebhookSettingsRepositoryStore.getInstance(createRepository)
    val second = WebhookSettingsRepositoryStore.getInstance(createRepository)

    assertEquals(1, createCallCount.get())
    assertSame(first, second)
  }

  @Test
  fun `並行に初回取得してもrepository生成は1回だけになる`() {
    val createCallCount = AtomicInteger(0)
    val createRepository = { createCallCount.incrementAndGet(); FakeWebhookSettingsRepository() }
    val startGate = CountDownLatch(1)
    val obtained = ConcurrentHashMap.newKeySet<WebhookSettingsRepository>()

    val threads = List(THREAD_COUNT) {
      Thread {
        startGate.await()
        obtained += WebhookSettingsRepositoryStore.getInstance(createRepository)
      }
    }
    threads.forEach { it.start() }
    startGate.countDown()
    threads.forEach { it.join() }

    assertEquals(1, createCallCount.get())
    assertEquals(1, obtained.size)
  }

  private class FakeWebhookSettingsRepository : WebhookSettingsRepository {
    override val settings: Flow<WebhookSettingsState> = MutableStateFlow(WebhookSettingsState.NotConfigured)

    override suspend fun save(settings: WebhookSettings) = Unit
  }

  private companion object {
    const val THREAD_COUNT = 8
  }
}
