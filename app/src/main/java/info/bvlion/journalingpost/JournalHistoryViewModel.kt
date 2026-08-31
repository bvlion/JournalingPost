package info.bvlion.journalingpost

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import info.bvlion.journalingpost.journal.JournalEntryDeleter
import info.bvlion.journalingpost.journal.JournalEntryReader
import info.bvlion.journalingpost.journal.history.JournalHistoryUiState
import info.bvlion.journalingpost.journal.history.toHistoryGroups
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class JournalHistoryViewModel(
  reader: JournalEntryReader,
  private val deleter: JournalEntryDeleter,
  zoneId: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {
  val uiState: StateFlow<JournalHistoryUiState> = reader.observeAll()
    .map { entries ->
      val groups = entries.toHistoryGroups(zoneId)
      if (groups.isEmpty()) JournalHistoryUiState.Empty else JournalHistoryUiState.Content(groups)
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), JournalHistoryUiState.Loading)

  // 削除失敗は継続的な画面状態ではなく1度きりの通知なので、画面がSnackbarで見せるまで保持して消費する。
  private val _deleteFailures = Channel<Unit>(Channel.BUFFERED)
  val deleteFailures: Flow<Unit> = _deleteFailures.receiveAsFlow()

  /** 削除後の一覧はRoomのFlowが更新するため、ここでuiStateを直接書き換えない。 */
  fun deleteEntry(id: Long) {
    viewModelScope.launch {
      try {
        deleter.delete(id)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        _deleteFailures.send(Unit)
      }
    }
  }
}
