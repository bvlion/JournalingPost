package info.bvlion.journalingpost.webhook

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile

/**
 * #5のAnalysisIntegration設定用DataStoreとはファイルを分離する(責務が異なるうえ、Webhook設定ファイルは
 * backupから除外する必要があり、他設定と混在させられない)。FILE_NAMEを変更する場合は
 * AndroidManifestのdataExtractionRules側のファイルパスも合わせて更新すること。
 */
internal object WebhookSettingsStore {
  @Volatile
  private var instance: DataStore<Preferences>? = null

  fun getInstance(context: Context): DataStore<Preferences> =
    instance ?: synchronized(this) {
      instance ?: PreferenceDataStoreFactory.create(
        produceFile = { context.applicationContext.preferencesDataStoreFile(FILE_NAME) },
      ).also { instance = it }
    }

  private const val FILE_NAME = "webhook_settings"
}
