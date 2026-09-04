package info.bvlion.journalingpost.settings

import kotlinx.coroutines.flow.Flow

/** Mood記録の開始時から、任意のメモ入力を開くかどうかの設定。 */
interface MoodNoteInputRepository {
  val isMoodNoteInputInitiallyOpen: Flow<Boolean>

  /** 永続化に失敗した場合は例外を投げる(CancellationExceptionはそのまま再送出する)。 */
  suspend fun setMoodNoteInputInitiallyOpen(isOpen: Boolean)
}
