package info.bvlion.journalingpost

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import info.bvlion.journalingpost.journal.JournalEntryReader
import info.bvlion.journalingpost.journal.history.JournalHistoryGroup
import info.bvlion.journalingpost.journal.history.toHistoryGroups
import java.time.ZoneId
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** ローカル保存済みのJournalEntry履歴を、日付単位でグループ化した表示用データとして公開する。 */
class JournalHistoryViewModel(
  reader: JournalEntryReader,
  zoneId: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {
  val historyGroups: StateFlow<List<JournalHistoryGroup>> = reader.observeAll()
    .map { it.toHistoryGroups(zoneId) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
