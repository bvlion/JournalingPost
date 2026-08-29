package info.bvlion.journalingpost.settings

import info.bvlion.journalingpost.webhook.LegacyWebhookConfig
import info.bvlion.journalingpost.webhook.LegacyWebhookConfigProvider
import info.bvlion.journalingpost.webhook.WebhookSettingsMigrationCoordinator
import info.bvlion.journalingpost.webhook.WebhookSettingsRepository
import info.bvlion.journalingpost.webhook.WebhookSettingsState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart

/**
 * 「CUSTOM_WEBHOOKが有効なら、保存済みのCustom Webhook設定が存在する」という契約をここで保証する。
 *
 * 保存済み設定が無い(NotConfigured)間はCUSTOM_WEBHOOKをNONEとして扱うため、「Custom Webhookを使う
 * 設定なのに送信先が無く、記録のたびに送信失敗になる」状態を作れない。この判定は永続値を書き換えず
 * 導出だけで行うので、設定を保存し直せば選択はそのまま復帰する。
 *
 * Loading/Unavailable(DataStoreを一時的に読めない状態)ではCUSTOM_WEBHOOKを維持する。ここでNONEへ
 * 倒すと、一時的な読み取り失敗が「解析・連携を使用しない」という永続的な選択に見えてしまい、
 * 実際の配送失敗もNOT_REQUIREDとして黙って握り潰されるため。
 *
 * 既定値(未保存)がCUSTOM_WEBHOOKのままなのは、#31以前から選択を一度も変更していない既存端末で、
 * 保存済みWebhook設定がある場合に送信を止めないため。設定が無ければこの導出によりNONEになる。
 *
 * NotConfigured/Configuredの判定はlegacy Webhook設定のmigration完了後の状態で行う必要がある。
 * MainActivity・Widget(MoodEntryActivity)どちらの入口でもこのrepositoryは同じsingletonなので、
 * collectのたびにここでmigrationを確認することで、入口ごとにmigration呼び出しを複製せずに済む。
 * 完了済みならWebhookSettingsMigrationCoordinator側で早期returnするため、通常の追加コストは
 * DataStoreの軽い読み取り1回で済む。
 */
internal class WebhookAwareAnalysisIntegrationRepository(
  private val delegate: AnalysisIntegrationRepository,
  private val webhookSettingsRepository: WebhookSettingsRepository,
  private val legacyConfigProvider: () -> LegacyWebhookConfig? = LegacyWebhookConfigProvider::get,
) : AnalysisIntegrationRepository {
  override val analysisIntegration: Flow<AnalysisIntegration> =
    combine(delegate.analysisIntegration, webhookSettingsRepository.settings) { integration, settings ->
      when {
        integration != AnalysisIntegration.CUSTOM_WEBHOOK -> integration
        settings is WebhookSettingsState.NotConfigured -> AnalysisIntegration.NONE
        else -> AnalysisIntegration.CUSTOM_WEBHOOK
      }
    }.onStart { WebhookSettingsMigrationCoordinator.ensureMigrated(webhookSettingsRepository, legacyConfigProvider) }

  override suspend fun setAnalysisIntegration(integration: AnalysisIntegration) =
    delegate.setAnalysisIntegration(integration)
}
