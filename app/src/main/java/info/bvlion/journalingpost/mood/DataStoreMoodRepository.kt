package info.bvlion.journalingpost.mood

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class DataStoreMoodRepository(
  private val dataStore: DataStore<Preferences>,
  private val initialMoods: List<Mood>,
) : MoodRepository {
  init {
    require(MoodValidator.isValid(initialMoods))
  }

  override val moods: Flow<List<Mood>> = dataStore.data
    .map { preferences -> preferences.toMoodsOrInitial() }
    .retryWhen { cause, _ ->
      if (cause !is IOException) return@retryWhen false
      delay(RETRY_DELAY_MILLIS)
      true
    }

  override suspend fun save(moods: List<Mood>) {
    require(MoodValidator.isValid(moods))
    dataStore.edit { preferences ->
      preferences[KEY_MOODS] = Json.encodeToString(moods)
    }
  }

  private fun Preferences.toMoodsOrInitial(): List<Mood> {
    val stored = this[KEY_MOODS] ?: return initialMoods
    return try {
      Json.decodeFromString<List<Mood>>(stored).takeIf(MoodValidator::isValid) ?: initialMoods
    } catch (e: SerializationException) {
      initialMoods
    } catch (e: IllegalArgumentException) {
      initialMoods
    }
  }

  private companion object {
    val KEY_MOODS = stringPreferencesKey("moods")
    const val RETRY_DELAY_MILLIS = 1_000L
  }
}
