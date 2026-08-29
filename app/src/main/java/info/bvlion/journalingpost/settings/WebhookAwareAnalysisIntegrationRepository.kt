package info.bvlion.journalingpost.settings

import info.bvlion.journalingpost.webhook.WebhookSettingsRepository
import info.bvlion.journalingpost.webhook.WebhookSettingsState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * CUSTOM_WEBHOOKを有効値として返すのは、保存済みWebhook設定が存在する場合だけにする。
 *
 * NotConfiguredではNONEへ倒す。一方、Unavailableは一時的な読み取り失敗なのでCUSTOM_WEBHOOKを維持し、
 * 記録時の送信失敗として扱う。ここでNONEへ倒すと配送失敗がNOT_REQUIREDとして隠れてしまうため。
 */
internal class WebhookAwareAnalysisIntegrationRepository(
  private val delegate: AnalysisIntegrationRepository,
  private val webhookSettingsRepository: WebhookSettingsRepository,
) : AnalysisIntegrationRepository {
  override val analysisIntegration: Flow<AnalysisIntegration> =
    combine(delegate.analysisIntegration, webhookSettingsRepository.settings) { integration, settings ->
      when {
        integration != AnalysisIntegration.CUSTOM_WEBHOOK -> integration
        settings is WebhookSettingsState.NotConfigured -> AnalysisIntegration.NONE
        else -> AnalysisIntegration.CUSTOM_WEBHOOK
      }
    }

  override suspend fun setAnalysisIntegration(integration: AnalysisIntegration) =
    delegate.setAnalysisIntegration(integration)
}
