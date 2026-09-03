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

internal class DataStoreWelcomeRepository(
  private val dataStore: DataStore<Preferences>,
) : WelcomeRepository {
  /**
   * 読み込めない間はfalse(未表示)へ倒す。一時的なI/O失敗でウェルカムダイアログが再度出てしまう側の方が、
   * 一度も表示せず視覚誘導だけが出てしまう側より実害が小さい。
   */
  override val isWelcomeDialogSeen: Flow<Boolean> = dataStore.data
    .map { preferences -> preferences[KEY] ?: false }
    .retryWhen { cause, _ ->
      if (cause !is IOException) return@retryWhen false
      emit(false)
      delay(RETRY_DELAY_MILLIS)
      true
    }

  override suspend fun markWelcomeDialogSeen() {
    dataStore.edit { it[KEY] = true }
  }

  private companion object {
    val KEY = booleanPreferencesKey("welcome_dialog_seen")
    const val RETRY_DELAY_MILLIS = 1_000L
  }
}
