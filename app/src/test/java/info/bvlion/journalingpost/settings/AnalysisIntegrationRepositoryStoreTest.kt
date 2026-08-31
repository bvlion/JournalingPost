package info.bvlion.journalingpost.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import info.bvlion.journalingpost.webhook.WebhookSettings
import info.bvlion.journalingpost.webhook.WebhookSettingsRepository
import info.bvlion.journalingpost.webhook.WebhookSettingsState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
  fun `getInstanceは常に同じrepositoryインスタンスを返す`() {
    val dataStore = BlockingWriteDataStore(customWebhookPreferences())

    val first = AnalysisIntegrationRepositoryStore.getInstance(dataStore, ConfiguredWebhookSettingsRepository())
    val second = AnalysisIntegrationRepositoryStore.getInstance(dataStore, ConfiguredWebhookSettingsRepository())

    assertSame(first, second)
  }

  @Test
  fun `Settings側の使用しない選択はwrite完了前でも共有インスタンスの実効値へ反映される`() = runTest {
    val dataStore = BlockingWriteDataStore(customWebhookPreferences())
    val webhookSettingsRepository = ConfiguredWebhookSettingsRepository()
    val settingsRepository = AnalysisIntegrationRepositoryStore.getInstance(dataStore, webhookSettingsRepository)
    val mainRepository = AnalysisIntegrationRepositoryStore.getInstance(dataStore, webhookSettingsRepository)

    backgroundScope.launch { settingsRepository.setAnalysisIntegration(AnalysisIntegration.NONE) }
    runCurrent()

    assertEquals(AnalysisIntegration.NONE, mainRepository.analysisIntegration.first())
  }

  private fun customWebhookPreferences(): Preferences =
    preferencesOf(stringPreferencesKey("analysis_integration") to AnalysisIntegration.CUSTOM_WEBHOOK.name)

  private class ConfiguredWebhookSettingsRepository : WebhookSettingsRepository {
    override val settings: Flow<WebhookSettingsState> = MutableStateFlow(
      WebhookSettingsState.Configured(
        WebhookSettings(url = "https://example.com/webhook", headers = emptyList(), bodyTemplate = "{}"),
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
}
