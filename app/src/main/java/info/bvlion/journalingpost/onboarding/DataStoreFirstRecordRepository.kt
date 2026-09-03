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

internal class DataStoreFirstRecordRepository(
  private val dataStore: DataStore<Preferences>,
) : FirstRecordRepository {
  /**
   * 読み込めない間はfalse(未完了)へ倒す。一時的なI/O失敗でウェルカム表示が再度出てしまう側の方が、
   * AI振り返り案内を出しそびれるより実害が小さい。
   */
  override val isFirstRecordCompleted: Flow<Boolean> = dataStore.data
    .map { preferences -> preferences[KEY] ?: false }
    .retryWhen { cause, _ ->
      if (cause !is IOException) return@retryWhen false
      emit(false)
      delay(RETRY_DELAY_MILLIS)
      true
    }

  override suspend fun markFirstRecordCompleted() {
    dataStore.edit { it[KEY] = true }
  }

  private companion object {
    val KEY = booleanPreferencesKey("first_record_completed")
    const val RETRY_DELAY_MILLIS = 1_000L
  }
}
