package info.bvlion.journalingpost.debug

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first

/**
 * fixtureを投入済みかどうかだけを持つ。JournalEntry / AnalysisResultのテーブルとは別のDataStoreへ
 * 置き、Room schemaへdebug専用の列を足さずに重複投入を防ぐ。debugビルドでのみ生成される。
 */
internal class DebugFixtureStateStore(private val dataStore: DataStore<Preferences>) {
  suspend fun isSeeded(): Boolean = dataStore.data.first()[SEEDED_KEY] == true

  suspend fun markSeeded() {
    dataStore.edit { it[SEEDED_KEY] = true }
  }

  private companion object {
    val SEEDED_KEY = booleanPreferencesKey("fixtures_seeded")
  }
}
