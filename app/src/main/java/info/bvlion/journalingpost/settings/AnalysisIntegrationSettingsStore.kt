package info.bvlion.journalingpost.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile

/**
 * DataStoreは同一ファイルに対して複数instanceを生成すると例外になるため、process内で1つだけ生成し再利用する。
 */
internal object AnalysisIntegrationSettingsStore {
  @Volatile
  private var instance: DataStore<Preferences>? = null

  fun getInstance(context: Context): DataStore<Preferences> =
    instance ?: synchronized(this) {
      instance ?: PreferenceDataStoreFactory.create(
        produceFile = { context.applicationContext.preferencesDataStoreFile(FILE_NAME) },
      ).also { instance = it }
    }

  private const val FILE_NAME = "analysis_integration_settings"
}
