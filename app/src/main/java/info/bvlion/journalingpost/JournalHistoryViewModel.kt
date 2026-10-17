package info.bvlion.journalingpost

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import info.bvlion.journalingpost.analysis.AnalysisExecutionRepository
import info.bvlion.journalingpost.analysis.AnalysisResultReader
import info.bvlion.journalingpost.analysis.latestAnalysisResultIdsByDay
import info.bvlion.journalingpost.journal.JournalEntryDeleter
import info.bvlion.journalingpost.journal.JournalEntryReader
import info.bvlion.journalingpost.journal.history.JournalHistoryGroup
import info.bvlion.journalingpost.journal.history.JournalHistoryUiState
import info.bvlion.journalingpost.journal.history.coerceToHistoryRange
import info.bvlion.journalingpost.journal.history.toHistoryGroups
import info.bvlion.journalingpost.time.CalendarDate
import info.bvlion.journalingpost.time.CurrentDateProvider
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class JournalHistoryViewModel(
  reader: JournalEntryReader,
  analysisResultReader: AnalysisResultReader,
  analysisExecutionRepository: AnalysisExecutionRepository,
  private val deleter: JournalEntryDeleter,
  private val currentDateProvider: CurrentDateProvider,
) : ViewModel() {
  // 表示中の日付はViewModelが保持するため、他タブへ移動して戻っても選択日は変わらない。
  // process再生成でViewModelごと作り直されたときだけ今日へ戻る。
  private val selectedDate = MutableStateFlow(currentDateProvider.refresh().today)

  // 過去方向の下限は現在の記録から算出するため、showPreviousDay等の同期的な操作からも参照できるよう
  // ここへ保持する。記録が1件も無い間は今日を下限として扱う。
  private var earliestDate: LocalDate = currentDateProvider.state.value.today

  /**
   * 日別表示のためにDAOへ日付条件を足さず、既存の全件Flowをグループ化した結果を日付で引けるようにする。
   * 過去方向の下限([earliestDate])は記録が無い日を除いた実データの最古日で決まるため、記録の増減の
   * たびと端末timezone変更時に[syncRangeToGroups]で追従させる。
   */
  private val historyData = combine(
    reader.observeAll(),
    analysisResultReader.observeAll(),
    analysisExecutionRepository.state,
    currentDateProvider.state,
  ) { entries, analysisResults, executionState, calendarDate ->
    val existingResultIds = analysisResults.mapTo(mutableSetOf()) { it.id }
    val usedEntryIds = executionState.entryIdsByResultId
      .filterKeys { it in existingResultIds }
      .values
      .flatten()
      .toSet()
    HistoryData(
      calendarDate = calendarDate,
      groups = entries.toHistoryGroups(calendarDate.zoneId, usedEntryIds),
      latestAnalysisResultIdsByDay = analysisResults.latestAnalysisResultIdsByDay(calendarDate.zoneId),
    )
  }.onEach { syncRangeToGroups(it.groups, it.calendarDate.today) }

  val uiState: StateFlow<JournalHistoryUiState> = combine(historyData, selectedDate) { data, selected ->
    JournalHistoryUiState.Content(
      selectedDate = coerceToHistoryRange(selected, earliestDate, data.calendarDate.today),
      today = data.calendarDate.today,
      earliestDate = earliestDate,
      itemsByDate = data.groups.associate { it.date to it.items },
      zoneId = data.calendarDate.zoneId,
      latestAnalysisResultIdsByDay = data.latestAnalysisResultIdsByDay,
    )
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), JournalHistoryUiState.Loading)

  // 削除結果は継続的な画面状態ではなく1度きりの通知なので、画面がSnackbarで見せるまで保持して消費する。
  private val _deleteSuccesses = Channel<Unit>(Channel.BUFFERED)
  val deleteSuccesses: Flow<Unit> = _deleteSuccesses.receiveAsFlow()

  private val _deleteFailures = Channel<Unit>(Channel.BUFFERED)
  val deleteFailures: Flow<Unit> = _deleteFailures.receiveAsFlow()

  /** 記録の無い日も飛ばさず、最古の記録日を下限にカレンダー日で1日戻す。 */
  fun showPreviousDay() {
    selectedDate.update { coerceToHistoryRange(it.minusDays(1), earliestDate, currentDateProvider.refresh().today) }
  }

  fun showNextDay() {
    selectedDate.update { coerceToHistoryRange(it.plusDays(1), earliestDate, currentDateProvider.refresh().today) }
  }

  fun showToday() {
    selectedDate.value = currentDateProvider.refresh().today
  }

  /** スワイプで確定したページからも呼ばれるため、範囲外の日はここで丸める。 */
  fun selectDate(date: LocalDate) {
    selectedDate.value = coerceToHistoryRange(date, earliestDate, currentDateProvider.refresh().today)
  }

  /** 削除後の一覧はRoomのFlowが更新するため、ここでuiStateを直接書き換えない。 */
  fun deleteEntry(id: Long) {
    viewModelScope.launch {
      try {
        deleter.delete(id)
        _deleteSuccesses.send(Unit)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        _deleteFailures.send(Unit)
      }
    }
  }

  /**
   * 最古の記録日を含む日が削除されると下限は今日側へ動き、記録が0件になれば今日だけが下限になる。
   * 端末timezoneが変わった場合も、再分類した記録から下限を決め直す。
   * 時刻設定で全記録が未来になった場合は今日を下限にし、表示中の日を範囲内へ戻す。
   */
  private fun syncRangeToGroups(groups: List<JournalHistoryGroup>, today: LocalDate) {
    earliestDate = groups.minOfOrNull { it.date }?.coerceAtMost(today) ?: today
    selectedDate.update { coerceToHistoryRange(it, earliestDate, today) }
  }

  private data class HistoryData(
    val calendarDate: CalendarDate,
    val groups: List<JournalHistoryGroup>,
    val latestAnalysisResultIdsByDay: Map<LocalDate, Long>,
  )
}
