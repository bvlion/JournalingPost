package info.bvlion.journalingpost.settings

import info.bvlion.journalingpost.webhook.WebhookSettings
import info.bvlion.journalingpost.webhook.WebhookSettingsRepository
import info.bvlion.journalingpost.webhook.WebhookSettingsState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
}
