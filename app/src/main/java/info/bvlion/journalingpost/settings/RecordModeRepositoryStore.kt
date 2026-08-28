package info.bvlion.journalingpost.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences

/**
 * DataStoreがsingletonでも、pendingMode(即時反映用の状態)はrepositoryインスタンスごとの
 * in-memory状態のため、MainViewModelFactory/SettingsViewModelFactoryが同じ
 * RecordModeRepositoryインスタンスを共有できるようにする。
 */
internal object RecordModeRepositoryStore {
  @Volatile
  private var instance: RecordModeRepository? = null

  fun getInstance(context: Context): RecordModeRepository =
    getInstance(RecordModeSettingsStore.getInstance(context))

  internal fun getInstance(dataStore: DataStore<Preferences>): RecordModeRepository =
    instance ?: synchronized(this) {
      instance ?: DataStoreRecordModeRepository(dataStore).also { instance = it }
    }

  internal fun resetForTesting() {
    instance = null
  }
}
