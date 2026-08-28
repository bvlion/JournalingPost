package info.bvlion.journalingpost.settings

import kotlinx.coroutines.flow.Flow

/** JournalRecorder/SettingsViewModelはこのinterfaceのみへ依存する。 */
interface RecordModeRepository {
  val recordMode: Flow<RecordMode>

  /** 永続化に失敗した場合は例外を投げる(CancellationExceptionはそのまま再送出する)。 */
  suspend fun setRecordMode(mode: RecordMode)
}
