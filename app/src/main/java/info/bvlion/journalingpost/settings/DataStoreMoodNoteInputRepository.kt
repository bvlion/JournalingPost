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

internal class DataStoreMoodNoteInputRepository(
  private val dataStore: DataStore<Preferences>,
) : MoodNoteInputRepository {
  override val isMoodNoteInputInitiallyOpen: Flow<Boolean> = dataStore.data
    .map { preferences -> preferences[KEY] ?: false }
    .retryWhen { cause, _ ->
      if (cause !is IOException) return@retryWhen false
      emit(false)
      delay(RETRY_DELAY_MILLIS)
      true
    }

  override suspend fun setMoodNoteInputInitiallyOpen(isOpen: Boolean) {
    dataStore.edit { it[KEY] = isOpen }
  }

  private companion object {
    val KEY = booleanPreferencesKey("mood_note_input_initially_open")
    const val RETRY_DELAY_MILLIS = 1_000L
  }
}
