package info.bvlion.journalingpost

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import info.bvlion.journalingpost.journal.JournalEntryDeleter
import info.bvlion.journalingpost.journal.JournalEntryReader
import info.bvlion.journalingpost.journal.history.JournalHistoryUiState
import info.bvlion.journalingpost.journal.history.toHistoryGroups
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
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

  private val _deleteFailed = MutableStateFlow(false)
  val deleteFailed: StateFlow<Boolean> = _deleteFailed.asStateFlow()

  /** 削除後の一覧はRoomのFlowが更新するため、ここでuiStateを直接書き換えない。 */
  fun deleteEntry(id: Long) {
    _deleteFailed.value = false
    viewModelScope.launch {
      try {
        deleter.delete(id)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        _deleteFailed.value = true
      }
    }
  }
}
