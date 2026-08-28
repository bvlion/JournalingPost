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

  /**
   * repository生成に必要なDataStore・cipherの構築はcreateRepositoryへ包んで遅延させる。
   * AndroidKeystoreWebhookSettingsCipherのconstructorはAndroid Keystoreを同期的に初期化するため、
   * 引数として先に評価すると、既存instanceを返すだけの呼び出しでも毎回Keystore初期化の失敗点が増える。
   */
  fun getInstance(context: Context): WebhookSettingsRepository = getInstance {
    DataStoreWebhookSettingsRepository(
      dataStore = WebhookSettingsStore.getInstance(context),
      cipher = AndroidKeystoreWebhookSettingsCipher(),
    )
  }

  internal fun getInstance(dataStore: DataStore<Preferences>, cipher: WebhookSettingsCipher): WebhookSettingsRepository =
    getInstance { DataStoreWebhookSettingsRepository(dataStore, cipher) }

  internal fun getInstance(createRepository: () -> WebhookSettingsRepository): WebhookSettingsRepository =
    instance ?: synchronized(this) {
      instance ?: createRepository().also { instance = it }
    }

  internal fun resetForTesting() {
    instance = null
  }
}
