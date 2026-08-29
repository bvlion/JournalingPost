package info.bvlion.journalingpost.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences

/**
 * DataStoreがsingletonでも、pendingIntegration(即時反映用の状態)はrepositoryインスタンスごとの
 * in-memory状態のため、MainViewModelFactory/SettingsViewModelFactoryが同じ
 * AnalysisIntegrationRepositoryインスタンスを共有できるようにする。
 */
internal object AnalysisIntegrationRepositoryStore {
  @Volatile
  private var instance: AnalysisIntegrationRepository? = null

  fun getInstance(context: Context): AnalysisIntegrationRepository =
    getInstance(AnalysisIntegrationSettingsStore.getInstance(context))

  internal fun getInstance(dataStore: DataStore<Preferences>): AnalysisIntegrationRepository =
    instance ?: synchronized(this) {
      instance ?: DataStoreAnalysisIntegrationRepository(dataStore).also { instance = it }
    }

  internal fun resetForTesting() {
    instance = null
  }
}
