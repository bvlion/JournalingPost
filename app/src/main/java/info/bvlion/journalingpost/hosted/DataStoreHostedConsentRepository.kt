package info.bvlion.journalingpost.hosted

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen

internal class DataStoreHostedConsentRepository(
  private val dataStore: DataStore<Preferences>,
) : HostedConsentRepository {
  /**
   * 読み込めない間は未同意(false)へ倒す。一時的なI/O失敗で同意ダイアログが再度出てしまう側の方が、
   * 同意なしに外部送信が有効化される側より実害が小さい。
   */
  override val hasConsented: Flow<Boolean> = dataStore.data
    .map { preferences -> preferences[KEY] ?: false }
    .retryWhen { cause, _ ->
      if (cause !is IOException) return@retryWhen false
      emit(false)
      delay(RETRY_DELAY_MILLIS)
      true
    }

  override suspend fun markConsented() {
    dataStore.edit { it[KEY] = true }
  }

  private companion object {
    val KEY = booleanPreferencesKey("hosted_consented")
    const val RETRY_DELAY_MILLIS = 1_000L
  }
}
