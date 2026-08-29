package info.bvlion.journalingpost.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.toMutablePreferences
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

class AnalysisIntegrationRepositoryStoreTest {
  @Before
  fun setUp() {
    AnalysisIntegrationRepositoryStore.resetForTesting()
  }

  @Test
  fun `getInstanceは常に同じrepositoryインスタンスを返す`() {
    val dataStore = BlockingWriteDataStore(customWebhookPreferences())

    val first = AnalysisIntegrationRepositoryStore.getInstance(dataStore, ConfiguredWebhookSettingsRepository())
    val second = AnalysisIntegrationRepositoryStore.getInstance(dataStore, ConfiguredWebhookSettingsRepository())

    assertSame(first, second)
  }

  @Test
  fun `Settings側の使用しない選択はwrite完了前でもMain側の記録へ反映される`() = runTest {
    val dataStore = BlockingWriteDataStore(customWebhookPreferences())
    val webhookSettingsRepository = ConfiguredWebhookSettingsRepository()
    val settingsRepository = AnalysisIntegrationRepositoryStore.getInstance(dataStore, webhookSettingsRepository)
    val mainRepository = AnalysisIntegrationRepositoryStore.getInstance(dataStore, webhookSettingsRepository)
    val journalRepository = FakeJournalEntryRepository()
    var postCalled = false
    val recorder = IntegrationRoutingJournalRecorder(
      analysisIntegrationRepository = mainRepository,
      localOnlyRecorder = LocalOnlyJournalRecorder(journalRepository),
      localWebhookRecorder = LocalWebhookJournalRecorder(
        journalRepository,
        JournalPoster { postCalled = true; true },
      ),
    )

    backgroundScope.launch { settingsRepository.setAnalysisIntegration(AnalysisIntegration.NONE) }
    runCurrent()
    val result = recorder.record("today was good", mood = null, source = JournalSource.APP)

    assertEquals(DeliveryStatus.NOT_REQUIRED, result)
    assertFalse(postCalled)
  }

  private fun customWebhookPreferences(): Preferences = emptyPreferences().toMutablePreferences().apply {
    this[stringPreferencesKey("analysis_integration")] = AnalysisIntegration.CUSTOM_WEBHOOK.name
  }

  private class ConfiguredWebhookSettingsRepository : WebhookSettingsRepository {
    override val settings: Flow<WebhookSettingsState> = MutableStateFlow(
      WebhookSettingsState.Configured(
        WebhookSettings(
          url = "https://example.com/webhook",
          headers = emptyList(),
          bodyTemplate = """{"text": "{{message}}"}""",
        ),
      ),
    )

    override suspend fun save(settings: WebhookSettings) = error("not used in this test")
  }

  private class BlockingWriteDataStore(initial: Preferences) : DataStore<Preferences> {
    override val data: Flow<Preferences> = MutableStateFlow(initial)

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
      delay(Long.MAX_VALUE)
      error("unreachable")
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
