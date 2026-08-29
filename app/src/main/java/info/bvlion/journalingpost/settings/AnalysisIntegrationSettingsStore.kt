package info.bvlion.journalingpost.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile

/**
 * DataStoreは同一ファイルに対して複数instanceを生成すると例外になるため、
 * JournalDatabase.getInstanceと同様にprocess内で1つだけ生成し再利用する。
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

  // 既存端末の保存済み設定を引き継ぐため、解析・連携へ意味を整理した後もファイル名は変更しない。
  private const val FILE_NAME = "record_mode_settings"
}
