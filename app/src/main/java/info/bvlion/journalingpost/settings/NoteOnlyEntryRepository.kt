package info.bvlion.journalingpost.settings

import kotlinx.coroutines.flow.Flow

/**
 * 「メモだけ記録」導線を表示するかどうかの設定。記録画面とWidgetは同じ設定を参照する。
 */
interface NoteOnlyEntryRepository {
  val isNoteOnlyEntryEnabled: Flow<Boolean>

  /** 永続化に失敗した場合は例外を投げる(CancellationExceptionはそのまま再送出する)。 */
  suspend fun setNoteOnlyEntryEnabled(enabled: Boolean)
}
