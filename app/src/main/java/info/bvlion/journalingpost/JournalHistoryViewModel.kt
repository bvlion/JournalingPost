package info.bvlion.journalingpost

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import info.bvlion.journalingpost.journal.JournalEntryDeleter
import info.bvlion.journalingpost.journal.JournalEntryReader
import info.bvlion.journalingpost.journal.history.JournalHistoryUiState
import info.bvlion.journalingpost.journal.history.toHistoryGroups
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class JournalHistoryViewModel(
  reader: JournalEntryReader,
  private val deleter: JournalEntryDeleter,
  private val zoneId: ZoneId = ZoneId.systemDefault(),
  private val now: () -> Instant = Instant::now,
) : ViewModel() {
  // 表示中の日付はViewModelが保持するため、他タブへ移動して戻っても選択日は変わらない。
  // process再生成でViewModelごと作り直されたときだけ今日へ戻る。
  private val selectedDate = MutableStateFlow(today())

  /**
   * 日別表示のためにDAOへ日付条件を足さず、既存の全件Flowをグループ化した結果から選択日を引く。
   * 過去方向の下限を設けないため、記録が無い日は空の一覧として扱う。
   */
  val uiState: StateFlow<JournalHistoryUiState> = combine(reader.observeAll(), selectedDate) { entries, selected ->
    val groups = entries.toHistoryGroups(zoneId)
    JournalHistoryUiState.Content(
      selectedDate = selected,
      // アプリを開いたまま日付が変わることがあるため、「今日」は保持せず算出のたびに求める。
      today = today(),
      items = groups.firstOrNull { it.date == selected }?.items.orEmpty(),
      hasAnyEntry = entries.isNotEmpty(),
    )
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), JournalHistoryUiState.Loading)

  // 削除失敗は継続的な画面状態ではなく1度きりの通知なので、画面がSnackbarで見せるまで保持して消費する。
  private val _deleteFailures = Channel<Unit>(Channel.BUFFERED)
  val deleteFailures: Flow<Unit> = _deleteFailures.receiveAsFlow()

  /** 記録の無い日も飛ばさないため、下限を設けずカレンダー日で1日戻す。 */
  fun showPreviousDay() {
    selectedDate.update { it.minusDays(1) }
  }

  fun showNextDay() {
    selectedDate.update { minOf(it.plusDays(1), today()) }
  }

  fun showToday() {
    selectedDate.value = today()
  }

  /** 未来日には記録が存在し得ないため、指定された日は今日までへ丸める。 */
  fun selectDate(date: LocalDate) {
    selectedDate.value = minOf(date, today())
  }

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

  private fun today(): LocalDate = now().atZone(zoneId).toLocalDate()
}
