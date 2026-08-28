package info.bvlion.journalingpost.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen

internal class DataStoreRecordModeRepository(
  private val dataStore: DataStore<Preferences>,
) : RecordModeRepository {
  /**
   * write完了前でも選択モードを記録処理へ即時反映するための値。generationは、複数回の
   * setRecordMode()が重なった際、古い呼び出しのwrite完了/失敗/キャンセルがより新しい選択を
   * 誤って上書き・巻き戻ししないよう、pendingModeをクリアする際に確認する世代番号。
   */
  private val pendingMode = MutableStateFlow<PendingMode?>(null)
  private val generation = AtomicInteger(0)

  private val persistedRecordMode: Flow<RecordMode> = dataStore.data
    .map { preferences -> preferences.toRecordModeOrDefault() }
    .retryWhen { cause, _ ->
      // 読み取れない場合は安全側(LOCAL_ONLY)へ一旦フォールバックしたうえで再購読を試み続ける。
      // IOException以外は再送出する(リトライしない)。
      if (cause !is IOException) return@retryWhen false
      emit(RecordMode.LOCAL_ONLY)
      delay(RETRY_DELAY_MILLIS)
      true
    }

  override val recordMode: Flow<RecordMode> = combine(persistedRecordMode, pendingMode) { persisted, pending ->
    pending?.mode ?: persisted
  }

  override suspend fun setRecordMode(mode: RecordMode) {
    val pending = PendingMode(mode, generation.incrementAndGet())
    pendingMode.value = pending
    try {
      dataStore.edit { it[KEY] = mode.name }
    } finally {
      pendingMode.compareAndSet(pending, null)
    }
  }

  private fun Preferences.toRecordModeOrDefault(): RecordMode =
    this[KEY]?.let { name -> RecordMode.entries.firstOrNull { it.name == name } } ?: RecordMode.LOCAL_AND_WEBHOOK

  private data class PendingMode(val mode: RecordMode, val generation: Int)

  private companion object {
    val KEY = stringPreferencesKey("record_mode")
    const val RETRY_DELAY_MILLIS = 1_000L
  }
}
