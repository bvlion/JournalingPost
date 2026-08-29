package info.bvlion.journalingpost.settings

import info.bvlion.journalingpost.journal.DeliveryStatus
import info.bvlion.journalingpost.journal.IntegrationRoutingJournalRecorder
import info.bvlion.journalingpost.journal.JournalEntry
import info.bvlion.journalingpost.journal.JournalEntryRepository
import info.bvlion.journalingpost.journal.JournalSource
import info.bvlion.journalingpost.journal.LocalOnlyJournalRecorder
import info.bvlion.journalingpost.journal.LocalWebhookJournalRecorder
import info.bvlion.journalingpost.poster.JournalPoster
import info.bvlion.journalingpost.webhook.LegacyWebhookConfig
import info.bvlion.journalingpost.webhook.WebhookSettings
import info.bvlion.journalingpost.webhook.WebhookSettingsRepository
import info.bvlion.journalingpost.webhook.WebhookSettingsState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WebhookAwareAnalysisIntegrationRepositoryTest {

  @Test
  fun `保存済みWebhook設定がなければCUSTOM_WEBHOOKは有効にならない`() = runTest {
    val repository = createRepository(
      integration = AnalysisIntegration.CUSTOM_WEBHOOK,
      webhookState = WebhookSettingsState.NotConfigured,
    )

    assertEquals(AnalysisIntegration.NONE, repository.analysisIntegration.first())
  }

  @Test
  fun `保存済みWebhook設定があればCUSTOM_WEBHOOKのまま有効になる`() = runTest {
    val repository = createRepository(
      integration = AnalysisIntegration.CUSTOM_WEBHOOK,
      webhookState = WebhookSettingsState.Configured(webhookSettings()),
    )

    assertEquals(AnalysisIntegration.CUSTOM_WEBHOOK, repository.analysisIntegration.first())
  }

  @Test
  fun `Webhook設定を一時的に読めない間はCUSTOM_WEBHOOKの選択を維持する`() = runTest {
    val repository = createRepository(
      integration = AnalysisIntegration.CUSTOM_WEBHOOK,
      webhookState = WebhookSettingsState.Unavailable,
    )

    assertEquals(AnalysisIntegration.CUSTOM_WEBHOOK, repository.analysisIntegration.first())
  }

  @Test
  fun `保存済みWebhook設定があってもNONEの選択はNONEのまま`() = runTest {
    val repository = createRepository(
      integration = AnalysisIntegration.NONE,
      webhookState = WebhookSettingsState.Configured(webhookSettings()),
    )

    assertEquals(AnalysisIntegration.NONE, repository.analysisIntegration.first())
  }

  @Test
  fun `Webhook設定が保存されるとCUSTOM_WEBHOOKの選択が有効に戻る`() = runTest {
    val webhookRepository = FakeWebhookSettingsRepository(WebhookSettingsState.NotConfigured)
    val repository = WebhookAwareAnalysisIntegrationRepository(
      delegate = FakeAnalysisIntegrationRepository(AnalysisIntegration.CUSTOM_WEBHOOK),
      webhookSettingsRepository = webhookRepository,
    )
    assertEquals(AnalysisIntegration.NONE, repository.analysisIntegration.first())

    webhookRepository.emit(WebhookSettingsState.Configured(webhookSettings()))

    assertEquals(AnalysisIntegration.CUSTOM_WEBHOOK, repository.analysisIntegration.first())
  }

  @Test
  fun `setAnalysisIntegrationはdelegateへそのまま永続化される`() = runTest {
    val delegate = FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE)
    val repository = WebhookAwareAnalysisIntegrationRepository(
      delegate = delegate,
      webhookSettingsRepository = FakeWebhookSettingsRepository(WebhookSettingsState.Configured(webhookSettings())),
    )

    repository.setAnalysisIntegration(AnalysisIntegration.CUSTOM_WEBHOOK)

    assertEquals(AnalysisIntegration.CUSTOM_WEBHOOK, delegate.analysisIntegration.first())
  }

  @Test
  fun `Webhook未設定ならCUSTOM_WEBHOOKでも記録はローカル保存だけになりWebhookへ送信しない`() = runTest {
    val journalRepository = FakeJournalEntryRepository()
    var postCalled = false
    val recorder = IntegrationRoutingJournalRecorder(
      analysisIntegrationRepository = createRepository(
        integration = AnalysisIntegration.CUSTOM_WEBHOOK,
        webhookState = WebhookSettingsState.NotConfigured,
      ),
      localOnlyRecorder = LocalOnlyJournalRecorder(journalRepository),
      localWebhookRecorder = LocalWebhookJournalRecorder(
        journalRepository,
        JournalPoster { postCalled = true; false },
      ),
    )

    val result = recorder.record("today was good", mood = null, source = JournalSource.APP)

    assertEquals(DeliveryStatus.NOT_REQUIRED, result)
    assertFalse(postCalled)
    assertEquals("today was good", journalRepository.entries.values.single().note)
  }

  @Test
  fun `MainActivityの起動処理を経由せずanalysisIntegrationを読んだだけでも未完了のlegacy migrationが完了しCustom Webhookが有効になる`() = runTest {
    val legacyConfig = LegacyWebhookConfig(
      postUrl = "https://legacy.example.com/webhook",
      teamId = "T000",
      token = "token",
      channel = "general",
      user = "bvlion",
    )
    val webhookRepository = FakeWebhookSettingsRepository(
      initial = WebhookSettingsState.NotConfigured,
      legacyMigrationCompleted = false,
    )
    val repository = WebhookAwareAnalysisIntegrationRepository(
      delegate = FakeAnalysisIntegrationRepository(AnalysisIntegration.CUSTOM_WEBHOOK),
      webhookSettingsRepository = webhookRepository,
      legacyConfigProvider = { legacyConfig },
    )

    val result = repository.analysisIntegration.first()

    assertEquals(AnalysisIntegration.CUSTOM_WEBHOOK, result)
    assertEquals(1, webhookRepository.saveCallCount)
  }

  @Test
  fun `legacy migration完了済みで保存済み設定が無ければ通常どおりNONEになる`() = runTest {
    val repository = WebhookAwareAnalysisIntegrationRepository(
      delegate = FakeAnalysisIntegrationRepository(AnalysisIntegration.CUSTOM_WEBHOOK),
      webhookSettingsRepository = FakeWebhookSettingsRepository(
        initial = WebhookSettingsState.NotConfigured,
        legacyMigrationCompleted = true,
      ),
      legacyConfigProvider = { null },
    )

    assertEquals(AnalysisIntegration.NONE, repository.analysisIntegration.first())
  }

  private fun createRepository(
    integration: AnalysisIntegration,
    webhookState: WebhookSettingsState,
  ) = WebhookAwareAnalysisIntegrationRepository(
    delegate = FakeAnalysisIntegrationRepository(integration),
    webhookSettingsRepository = FakeWebhookSettingsRepository(webhookState),
  )

  private fun webhookSettings() = WebhookSettings(
    url = "https://example.com/webhook",
    headers = emptyList(),
    bodyTemplate = """{"text": "{{message}}"}""",
  )

  private class FakeAnalysisIntegrationRepository(initial: AnalysisIntegration) : AnalysisIntegrationRepository {
    private val state = MutableStateFlow(initial)
    override val analysisIntegration: Flow<AnalysisIntegration> = state

    override suspend fun setAnalysisIntegration(integration: AnalysisIntegration) {
      state.value = integration
    }
  }

  private class FakeWebhookSettingsRepository(
    initial: WebhookSettingsState,
    private var legacyMigrationCompleted: Boolean = true,
  ) : WebhookSettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings: Flow<WebhookSettingsState> = state
    var saveCallCount = 0
      private set

    fun emit(newState: WebhookSettingsState) {
      state.value = newState
    }

    override suspend fun save(settings: WebhookSettings) {
      saveCallCount++
      state.value = WebhookSettingsState.Configured(settings)
    }

    override suspend fun clear() {
      state.value = WebhookSettingsState.NotConfigured
    }

    override suspend fun isLegacyMigrationCompleted(): Boolean = legacyMigrationCompleted

    override suspend fun markLegacyMigrationCompleted() {
      legacyMigrationCompleted = true
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
