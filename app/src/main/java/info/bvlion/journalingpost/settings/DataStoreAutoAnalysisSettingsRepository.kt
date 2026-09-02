package info.bvlion.journalingpost.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import java.time.LocalTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen

internal class DataStoreAutoAnalysisSettingsRepository(
  private val dataStore: DataStore<Preferences>,
) : AutoAnalysisSettingsRepository {
  /**
   * 読み込めない間は既定値(無効)を流してから再試行する。設定画面がこの値を購読するため、
   * 一時的なI/O失敗で画面が固まらないようにする。
   */
  override val autoAnalysisSettings: Flow<AutoAnalysisSettings> = dataStore.data
    .map { preferences -> preferences.toAutoAnalysisSettings() }
    .retryWhen { cause, _ ->
      if (cause !is IOException) return@retryWhen false
      emit(AutoAnalysisSettings.DEFAULT)
      delay(RETRY_DELAY_MILLIS)
      true
    }

  override suspend fun setAutoAnalysisSettings(settings: AutoAnalysisSettings) {
    dataStore.edit { preferences ->
      preferences[KEY_ENABLED] = settings.enabled
      preferences[KEY_TIME_MINUTES] = settings.timeOfDay.hour * 60 + settings.timeOfDay.minute
      preferences[KEY_TARGET_DAY] = settings.targetDay.name
    }
  }

  private fun Preferences.toAutoAnalysisSettings(): AutoAnalysisSettings {
    val default = AutoAnalysisSettings.DEFAULT
    val timeOfDay = this[KEY_TIME_MINUTES]
      ?.takeIf { it in 0 until MINUTES_PER_DAY }
      ?.let { LocalTime.of(it / 60, it % 60) }
      ?: default.timeOfDay
    val targetDay = this[KEY_TARGET_DAY]
      ?.let { stored -> AutoAnalysisTargetDay.entries.firstOrNull { it.name == stored } }
      ?: default.targetDay
    return AutoAnalysisSettings(
      enabled = this[KEY_ENABLED] ?: default.enabled,
      timeOfDay = timeOfDay,
      targetDay = targetDay,
    )
  }

  private companion object {
    val KEY_ENABLED = booleanPreferencesKey("auto_analysis_enabled")
    val KEY_TIME_MINUTES = intPreferencesKey("auto_analysis_time_minutes")
    val KEY_TARGET_DAY = stringPreferencesKey("auto_analysis_target_day")
    const val RETRY_DELAY_MILLIS = 1_000L
    const val MINUTES_PER_DAY = 24 * 60
  }
}
