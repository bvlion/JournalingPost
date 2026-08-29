package info.bvlion.journalingpost.settings

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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WebhookAwareAnalysisIntegrationRepositoryTest {
  @Test
  fun `Webhook未設定ならCUSTOM_WEBHOOKは実効値NONEになる`() = runTest {
    val repository = createRepository(
      integration = AnalysisIntegration.CUSTOM_WEBHOOK,
      webhookState = WebhookSettingsState.NotConfigured,
    )

    assertEquals(AnalysisIntegration.NONE, repository.analysisIntegration.first())
  }

  @Test
  fun `Webhook設定済みならCUSTOM_WEBHOOKのままになる`() = runTest {
    val repository = createRepository(
      integration = AnalysisIntegration.CUSTOM_WEBHOOK,
      webhookState = WebhookSettingsState.Configured(webhookSettings()),
    )

    assertEquals(AnalysisIntegration.CUSTOM_WEBHOOK, repository.analysisIntegration.first())
  }

  @Test
  fun `Webhook設定を一時的に読めない間はCUSTOM_WEBHOOKを維持する`() = runTest {
    val repository = createRepository(
      integration = AnalysisIntegration.CUSTOM_WEBHOOK,
      webhookState = WebhookSettingsState.Unavailable,
    )

    assertEquals(AnalysisIntegration.CUSTOM_WEBHOOK, repository.analysisIntegration.first())
  }

  @Test
  fun `Webhook設定済みでもNONEの選択はNONEのままになる`() = runTest {
    val repository = createRepository(
      integration = AnalysisIntegration.NONE,
      webhookState = WebhookSettingsState.Configured(webhookSettings()),
    )

    assertEquals(AnalysisIntegration.NONE, repository.analysisIntegration.first())
  }

  @Test
  fun `Webhook設定が復旧すると保存済みCUSTOM_WEBHOOKも実効値へ戻る`() = runTest {
    val webhookRepository = FakeWebhookSettingsRepository(WebhookSettingsState.NotConfigured)
    val repository = WebhookAwareAnalysisIntegrationRepository(
      FakeAnalysisIntegrationRepository(AnalysisIntegration.CUSTOM_WEBHOOK),
      webhookRepository,
    )
    assertEquals(AnalysisIntegration.NONE, repository.analysisIntegration.first())

    webhookRepository.emit(WebhookSettingsState.Configured(webhookSettings()))

    assertEquals(AnalysisIntegration.CUSTOM_WEBHOOK, repository.analysisIntegration.first())
  }

  @Test
  fun `Webhook未設定なら記録はローカル保存だけ行う`() = runTest {
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

  private fun createRepository(
    integration: AnalysisIntegration,
    webhookState: WebhookSettingsState,
  ) = WebhookAwareAnalysisIntegrationRepository(
    FakeAnalysisIntegrationRepository(integration),
    FakeWebhookSettingsRepository(webhookState),
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

  private class FakeWebhookSettingsRepository(initial: WebhookSettingsState) : WebhookSettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings: Flow<WebhookSettingsState> = state

    fun emit(newState: WebhookSettingsState) {
      state.value = newState
    }

    override suspend fun save(settings: WebhookSettings) {
      state.value = WebhookSettingsState.Configured(settings)
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
