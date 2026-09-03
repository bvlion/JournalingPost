package info.bvlion.journalingpost.onboarding

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen

internal class DataStoreAnalysisIntroductionRepository(
  private val dataStore: DataStore<Preferences>,
) : AnalysisIntroductionRepository {
  /**
   * 読み込めない間はfalse(未案内)へ倒す。一時的なI/O失敗で案内を出しそびれるより、
   * 再表示されてしまう側の実害の方が小さい。
   */
  override val isIntroductionSeen: Flow<Boolean> = dataStore.data
    .map { preferences -> preferences[KEY] ?: false }
    .retryWhen { cause, _ ->
      if (cause !is IOException) return@retryWhen false
      emit(false)
      delay(RETRY_DELAY_MILLIS)
      true
    }

  override suspend fun markIntroductionSeen() {
    dataStore.edit { it[KEY] = true }
  }

  private companion object {
    val KEY = booleanPreferencesKey("analysis_introduction_seen")
    const val RETRY_DELAY_MILLIS = 1_000L
  }
}
