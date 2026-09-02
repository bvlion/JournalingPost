package info.bvlion.journalingpost

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import info.bvlion.journalingpost.journal.JournalEntryDeleter
import info.bvlion.journalingpost.journal.JournalEntryReader
import info.bvlion.journalingpost.journal.history.JournalHistoryGroup
import info.bvlion.journalingpost.journal.history.JournalHistoryUiState
import info.bvlion.journalingpost.journal.history.coerceToHistoryRange
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
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

  // 過去方向の下限は現在の記録から算出するため、showPreviousDay等の同期的な操作からも参照できるよう
  // ここへ保持する。記録が1件も無い間は今日を下限として扱う。
  private var earliestDate: LocalDate = today()

  /**
   * 日別表示のためにDAOへ日付条件を足さず、既存の全件Flowをグループ化した結果を日付で引けるようにする。
   * 過去方向の下限([earliestDate])は記録が無い日を除いた実データの最古日で決まるため、記録の増減の
   * たびに[syncRangeToGroups]で追従させる。
   */
  val uiState: StateFlow<JournalHistoryUiState> = combine(
    reader.observeAll().map { it.toHistoryGroups(zoneId) }.onEach(::syncRangeToGroups),
    selectedDate,
  ) { groups, selected ->
    JournalHistoryUiState.Content(
      selectedDate = selected,
      // アプリを開いたまま日付が変わることがあるため、「今日」は保持せず算出のたびに求める。
      today = today(),
      earliestDate = earliestDate,
      itemsByDate = groups.associate { it.date to it.items },
    )
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), JournalHistoryUiState.Loading)

  // 削除失敗は継続的な画面状態ではなく1度きりの通知なので、画面がSnackbarで見せるまで保持して消費する。
  private val _deleteFailures = Channel<Unit>(Channel.BUFFERED)
  val deleteFailures: Flow<Unit> = _deleteFailures.receiveAsFlow()

  /** 記録の無い日も飛ばさず、最古の記録日を下限にカレンダー日で1日戻す。 */
  fun showPreviousDay() {
    selectedDate.update { coerceToHistoryRange(it.minusDays(1), earliestDate, today()) }
  }

  fun showNextDay() {
    selectedDate.update { coerceToHistoryRange(it.plusDays(1), earliestDate, today()) }
  }

  fun showToday() {
    selectedDate.value = today()
  }

  /** スワイプで確定したページからも呼ばれるため、範囲外の日はここで丸める。 */
  fun selectDate(date: LocalDate) {
    selectedDate.value = coerceToHistoryRange(date, earliestDate, today())
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

  /**
   * 最古の記録日を含む日が削除されると下限は今日側へ動き、記録が0件になれば今日だけが下限になる。
   * JournalRecorderは常に記録時点のInstant.now()で保存するため、下限がこれより過去へ動くことはない。
   * 表示中の日が新しい下限より前になっていれば、ここで範囲内へ戻す。
   */
  private fun syncRangeToGroups(groups: List<JournalHistoryGroup>) {
    val today = today()
    earliestDate = groups.minOfOrNull { it.date } ?: today
    selectedDate.update { coerceToHistoryRange(it, earliestDate, today) }
  }

  private fun today(): LocalDate = now().atZone(zoneId).toLocalDate()
}
