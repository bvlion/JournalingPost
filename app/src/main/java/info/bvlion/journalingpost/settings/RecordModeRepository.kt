package info.bvlion.journalingpost.settings

import kotlinx.coroutines.flow.Flow

/** JournalRecorder/SettingsViewModelはこのinterfaceのみへ依存する。 */
interface RecordModeRepository {
  val recordMode: Flow<RecordMode>

  suspend fun setRecordMode(mode: RecordMode)
}
