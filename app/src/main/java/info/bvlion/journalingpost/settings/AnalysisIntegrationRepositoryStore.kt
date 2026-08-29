package info.bvlion.journalingpost.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import info.bvlion.journalingpost.webhook.WebhookSettingsRepository
import info.bvlion.journalingpost.webhook.WebhookSettingsRepositoryStore

/**
 * DataStoreがsingletonでも、pendingIntegration(即時反映用の状態)はrepositoryインスタンスごとの
 * in-memory状態のため、MainViewModelFactory/SettingsViewModelFactoryが同じ
 * AnalysisIntegrationRepositoryインスタンスを共有できるようにする。
 *
 * 「CUSTOM_WEBHOOKが有効ならWebhook設定が存在する」契約はここでのみ組み立てる。記録側・設定画面側の
 * どちらかが素のDataStore実装を直接使うと、その契約が片側だけ崩れるため。
 */
internal object AnalysisIntegrationRepositoryStore {
  @Volatile
  private var instance: AnalysisIntegrationRepository? = null

  fun getInstance(context: Context): AnalysisIntegrationRepository =
    getInstance(
      dataStore = AnalysisIntegrationSettingsStore.getInstance(context),
      webhookSettingsRepository = WebhookSettingsRepositoryStore.getInstance(context),
    )

  internal fun getInstance(
    dataStore: DataStore<Preferences>,
    webhookSettingsRepository: WebhookSettingsRepository,
  ): AnalysisIntegrationRepository =
    instance ?: synchronized(this) {
      instance ?: WebhookAwareAnalysisIntegrationRepository(
        delegate = DataStoreAnalysisIntegrationRepository(dataStore),
        webhookSettingsRepository = webhookSettingsRepository,
      ).also { instance = it }
    }

  internal fun resetForTesting() {
    instance = null
  }
}
