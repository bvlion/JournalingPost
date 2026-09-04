package info.bvlion.journalingpost.settings

import kotlinx.coroutines.flow.Flow

interface MoodNoteInputRepository {
  val isMoodNoteInputInitiallyOpen: Flow<Boolean>

  /** 永続化に失敗した場合は例外を投げる(CancellationExceptionはそのまま再送出する)。 */
  suspend fun setMoodNoteInputInitiallyOpen(isOpen: Boolean)
}
