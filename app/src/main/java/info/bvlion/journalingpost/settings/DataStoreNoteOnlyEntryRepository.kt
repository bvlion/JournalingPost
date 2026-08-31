package info.bvlion.journalingpost.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen

internal class DataStoreNoteOnlyEntryRepository(
  private val dataStore: DataStore<Preferences>,
) : NoteOnlyEntryRepository {
  /**
   * 読み込めない間は導線を出さない側(false)へ倒す。この設定はWidgetのcomposition内でも購読するため、
   * 一時的なI/O失敗でWidget自体の描画を止めないよう既定値を流してから再試行する。
   */
  override val isNoteOnlyEntryEnabled: Flow<Boolean> = dataStore.data
    .map { preferences -> preferences[KEY] ?: false }
    .retryWhen { cause, _ ->
      if (cause !is IOException) return@retryWhen false
      emit(false)
      delay(RETRY_DELAY_MILLIS)
      true
    }

  override suspend fun setNoteOnlyEntryEnabled(enabled: Boolean) {
    dataStore.edit { it[KEY] = enabled }
  }

  private companion object {
    val KEY = booleanPreferencesKey("note_only_entry_enabled")
    const val RETRY_DELAY_MILLIS = 1_000L
  }
}
