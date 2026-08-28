package info.bvlion.journalingpost.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class DataStoreRecordModeRepository(
  private val dataStore: DataStore<Preferences>,
) : RecordModeRepository {
  override val recordMode: Flow<RecordMode> = dataStore.data.map { preferences ->
    preferences[KEY]?.let { name -> RecordMode.entries.firstOrNull { it.name == name } }
      ?: RecordMode.LOCAL_AND_WEBHOOK
  }

  override suspend fun setRecordMode(mode: RecordMode) {
    dataStore.edit { it[KEY] = mode.name }
  }

  private companion object {
    val KEY = stringPreferencesKey("record_mode")
  }
}
