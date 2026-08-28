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
   * setRecordMode()呼び出し時にdataStore.editへ到達する前に同期的へ更新する即時反映用の値。
   * DataStoreへの永続化(ディスクI/O)が完了する前でも、以降にrecordModeを取得する呼び出しが
   * 常にこの値を優先して読むため、選択直後の記録が旧モードを参照する競合を防げる。
   *
   * generationは、短時間に複数回setRecordMode()が呼ばれた場合に、古い呼び出しのwrite完了/失敗が
   * より新しい呼び出しが設定したpendingを誤って上書き・巻き戻ししないようにするための世代番号。
   * write完了/失敗時にpendingModeをクリアする際、自分がpendingModeを設定した時点と同じ世代の
   * ままであることをcompareAndSetで確認し、既に新しい呼び出しに上書きされていれば何もしない。
   */
  private val pendingMode = MutableStateFlow<PendingMode?>(null)
  private val generation = AtomicInteger(0)

  private val persistedRecordMode: Flow<RecordMode> = dataStore.data
    .map { preferences -> preferences.toRecordModeOrDefault() }
    .catch { e ->
      // 設定を読み取れない場合は外部送信を行わない安全側(LOCAL_ONLY)へ倒す。
      // IOException以外(プログラミングミス等)はここで握り潰さず再送出する。
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
      // 永続化に成功した場合、以降はpersistedRecordModeへ委ねてよいのでpendingを片付ける。
      // 既により新しい呼び出しに上書きされていれば(世代が変わっていれば)何もしない。
      pendingMode.compareAndSet(pending, null)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // 永続化に失敗した場合、楽観的に反映していたpendingを元(persisted側)へ戻す。
      // これも既により新しい呼び出しに上書きされていれば何もしない。
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
