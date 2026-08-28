package info.bvlion.journalingpost.webhook

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences

/**
 * #5のRecordModeRepositoryStoreと同様、pendingSettings(即時反映用のin-memory状態)を
 * MainViewModelFactory/SettingsViewModelFactoryが同じインスタンスとして共有できるようにする。
 */
object WebhookSettingsRepositoryStore {
  @Volatile
  private var instance: WebhookSettingsRepository? = null

  fun getInstance(context: Context): WebhookSettingsRepository =
    getInstance(WebhookSettingsStore.getInstance(context), AndroidKeystoreWebhookSettingsCipher())

  internal fun getInstance(dataStore: DataStore<Preferences>, cipher: WebhookSettingsCipher): WebhookSettingsRepository =
    instance ?: synchronized(this) {
      instance ?: DataStoreWebhookSettingsRepository(dataStore, cipher).also { instance = it }
    }

  internal fun resetForTesting() {
    instance = null
  }
}
