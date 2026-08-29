package info.bvlion.journalingpost.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import info.bvlion.journalingpost.journal.DeliveryStatus
import info.bvlion.journalingpost.journal.IntegrationRoutingJournalRecorder
import info.bvlion.journalingpost.journal.JournalEntry
import info.bvlion.journalingpost.journal.JournalEntryRepository
import info.bvlion.journalingpost.journal.JournalSource
import info.bvlion.journalingpost.journal.LocalOnlyJournalRecorder
import info.bvlion.journalingpost.journal.LocalWebhookJournalRecorder
import info.bvlion.journalingpost.poster.JournalPoster
import info.bvlion.journalingpost.webhook.WebhookSettings
import info.bvlion.journalingpost.webhook.WebhookSettingsRepository
import info.bvlion.journalingpost.webhook.WebhookSettingsState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AnalysisIntegrationRepositoryStoreTest {

  @Before
  fun setUp() {
    AnalysisIntegrationRepositoryStore.resetForTesting()
  }

  @Test
  fun `getInstanceは同一DataStoreに対して常に同じrepositoryインスタンスを返す`() {
    val dataStore = BlockingWriteDataStore()

    val first = AnalysisIntegrationRepositoryStore.getInstance(dataStore, ConfiguredWebhookSettingsRepository())
    val second = AnalysisIntegrationRepositoryStore.getInstance(dataStore, ConfiguredWebhookSettingsRepository())

    assertSame(first, second)
  }

  @Test
  fun `Settings側とMain側が別々にgetInstanceしてもwrite未完了のNONEがMain側の記録へ反映される`() =
    runTest {
      val dataStore = BlockingWriteDataStore()
      // Custom Webhookが有効になり得るよう、Webhook設定は保存済みの前提にする。
      val webhookSettingsRepository = ConfiguredWebhookSettingsRepository()
      val settingsSideRepository = AnalysisIntegrationRepositoryStore.getInstance(dataStore, webhookSettingsRepository)
      val mainSideRepository = AnalysisIntegrationRepositoryStore.getInstance(dataStore, webhookSettingsRepository)

      val journalRepository = FakeJournalEntryRepository()
      var postCalled = false
      val recorder = IntegrationRoutingJournalRecorder(
        analysisIntegrationRepository = mainSideRepository,
        localOnlyRecorder = LocalOnlyJournalRecorder(journalRepository),
        localWebhookRecorder = LocalWebhookJournalRecorder(
          journalRepository,
          JournalPoster { postCalled = true; true },
        ),
      )

      backgroundScope.launch { settingsSideRepository.setAnalysisIntegration(AnalysisIntegration.NONE) }
      runCurrent()
      val result = recorder.record("today was good", mood = null, source = JournalSource.APP)

      assertEquals(DeliveryStatus.NOT_REQUIRED, result)
      assertFalse(postCalled)
    }

  private class ConfiguredWebhookSettingsRepository : WebhookSettingsRepository {
    private val settingsState = MutableStateFlow<WebhookSettingsState>(
      WebhookSettingsState.Configured(
        WebhookSettings(
          url = "https://example.com/webhook",
          headers = emptyList(),
          bodyTemplate = """{"text": "{{message}}"}""",
        ),
      ),
    )
    override val settings: Flow<WebhookSettingsState> = settingsState

    override suspend fun save(settings: WebhookSettings) = error("not used in this test")

    override suspend fun clear() = error("not used in this test")

    override suspend fun isLegacyMigrationCompleted(): Boolean = true

    override suspend fun markLegacyMigrationCompleted() = Unit
  }

  private class BlockingWriteDataStore : DataStore<Preferences> {
    override val data: Flow<Preferences> = MutableStateFlow(emptyPreferences())

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
      delay(Long.MAX_VALUE)
      error("unreachable: このテストではwriteを意図的に完了させない")
    }
  }

  private class FakeJournalEntryRepository : JournalEntryRepository {
    val entries = mutableMapOf<Long, JournalEntry>()
    private var nextId = 1L

    override suspend fun insert(entry: JournalEntry): Long {
      val id = nextId++
      entries[id] = entry.copy(id = id)
      return id
    }

    override suspend fun updateDeliveryStatus(id: Long, status: DeliveryStatus) {
      entries[id] = requireNotNull(entries[id]).copy(deliveryStatus = status)
    }
  }
}
