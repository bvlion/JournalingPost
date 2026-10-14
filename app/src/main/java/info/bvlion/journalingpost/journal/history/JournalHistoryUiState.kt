package info.bvlion.journalingpost.journal.history

import info.bvlion.journalingpost.time.CalendarDate
import java.time.LocalDate
import java.time.ZoneId

/**
 * 初回のデータ受信前(Loading)と、受信済み(Content)を区別する。区別しないと、既存の履歴がある場合でも
 * 読み込み中に記録0件の表示が一瞬出る。
 */
sealed interface JournalHistoryUiState {
  data object Loading : JournalHistoryUiState

  /**
   * [itemsByDate]は選択日だけでなく全日分を持つ。左右スワイプの最中は隣の日も同時に描画されるため、
   * 表示日以外の記録もその場で引ける必要がある。
   *
   * [earliestDate]は移動できる過去方向の下限で、現在存在するJournalEntryのうち最古の日。
   * 記録が無い場合や時刻設定で全記録が未来になった場合は[today]と同じ値になる。
   */
  data class Content(
    val selectedDate: LocalDate,
    val today: LocalDate,
    val earliestDate: LocalDate,
    val itemsByDate: Map<LocalDate, List<JournalHistoryItem>>,
    val zoneId: ZoneId = ZoneId.systemDefault(),
    val latestAnalysisResultIdsByDay: Map<LocalDate, Long> = emptyMap(),
  ) : JournalHistoryUiState {
    val calendarDate: CalendarDate get() = CalendarDate(today, zoneId)

    /** 未来日は表示しないため、「今日を表示中か」と「翌日へ進めないか」は同じ条件になる。 */
    val isToday: Boolean get() = selectedDate == today

    val isEarliestDate: Boolean get() = selectedDate == earliestDate

    /** 記録が1件も無い利用者へ、「この日は記録なし」ではなく初回向けの案内を出すために使う。 */
    val hasAnyEntry: Boolean get() = itemsByDate.isNotEmpty()

    fun itemsOn(date: LocalDate): List<JournalHistoryItem> = itemsByDate[date].orEmpty()
  }
}
