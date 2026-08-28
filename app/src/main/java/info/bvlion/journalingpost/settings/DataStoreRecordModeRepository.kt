package info.bvlion.journalingpost.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

internal class DataStoreRecordModeRepository(
  private val dataStore: DataStore<Preferences>,
) : RecordModeRepository {
  /**
   * write完了前でも選択モードを記録処理へ即時反映するための値。generationは、複数回の
   * setRecordMode()が重なった際、古い呼び出しのwrite完了/失敗がより新しい選択を
   * 誤って上書き・巻き戻ししないよう、pendingModeをクリアする際に確認する世代番号。
   */
  private val pendingMode = MutableStateFlow<PendingMode?>(null)
  private val generation = AtomicInteger(0)

  private val persistedRecordMode: Flow<RecordMode> = dataStore.data
    .map { preferences -> preferences.toRecordModeOrDefault() }
    .catch { e ->
      // 読み取れない場合は外部送信を行わない安全側(LOCAL_ONLY)へ倒す。IOException以外は再送出する。
      if (e is IOException) emit(RecordMode.LOCAL_ONLY) else throw e
    }

  override val recordMode: Flow<RecordMode> = combine(persistedRecordMode, pendingMode) { persisted, pending ->
    pending?.mode ?: persisted
  }

  override suspend fun setRecordMode(mode: RecordMode) {
    val pending = PendingMode(mode, generation.incrementAndGet())
    pendingMode.value = pending
    try {
      dataStore.edit { it[KEY] = mode.name }
      pendingMode.compareAndSet(pending, null)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      pendingMode.compareAndSet(pending, null)
      throw e
    }
  }

  private fun Preferences.toRecordModeOrDefault(): RecordMode =
    this[KEY]?.let { name -> RecordMode.entries.firstOrNull { it.name == name } } ?: RecordMode.LOCAL_AND_WEBHOOK

  private data class PendingMode(val mode: RecordMode, val generation: Int)

  private companion object {
    val KEY = stringPreferencesKey("record_mode")
  }
}
