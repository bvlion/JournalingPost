package info.bvlion.journalingpost.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

internal class DataStoreRecordModeRepository(
  private val dataStore: DataStore<Preferences>,
) : RecordModeRepository {
  /**
   * setRecordMode()呼び出し時にdataStore.editへ到達する前に同期的へ更新する即時反映用の値。
   * DataStoreへの永続化(ディスクI/O)が完了する前でも、以降にrecordModeを取得する呼び出しが
   * 常にこの値を優先して読むため、選択直後の記録が旧モードを参照する競合を防げる。
   */
  private val pendingMode = MutableStateFlow<RecordMode?>(null)

  private val persistedRecordMode: Flow<RecordMode> = dataStore.data
    .map { preferences -> preferences.toRecordModeOrDefault() }
    .catch { e ->
      // 設定を読み取れない場合は外部送信を行わない安全側(LOCAL_ONLY)へ倒す。
      // IOException以外(プログラミングミス等)はここで握り潰さず再送出する。
      if (e is IOException) emit(RecordMode.LOCAL_ONLY) else throw e
    }

  override val recordMode: Flow<RecordMode> = combine(persistedRecordMode, pendingMode) { persisted, pending ->
    pending ?: persisted
  }

  override suspend fun setRecordMode(mode: RecordMode) {
    pendingMode.value = mode
    dataStore.edit { it[KEY] = mode.name }
  }

  private fun Preferences.toRecordModeOrDefault(): RecordMode =
    this[KEY]?.let { name -> RecordMode.entries.firstOrNull { it.name == name } } ?: RecordMode.LOCAL_AND_WEBHOOK

  private companion object {
    val KEY = stringPreferencesKey("record_mode")
  }
}
