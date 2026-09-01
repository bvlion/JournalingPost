package info.bvlion.journalingpost.journal.history

import java.time.LocalDate

/**
 * 初回のデータ受信前(Loading)と、受信済み(Content)を区別する。区別しないと、既存の履歴がある場合でも
 * 読み込み中に記録0件の表示が一瞬出る。
 */
sealed interface JournalHistoryUiState {
  data object Loading : JournalHistoryUiState

  /**
   * [items]は[selectedDate]1日分だけを新しい順で持つ。[hasAnyEntry]は、記録が1件も無い利用者へ
   * 「この日は記録なし」ではなく初回向けの案内を出し分けるために使う。
   */
  data class Content(
    val selectedDate: LocalDate,
    val today: LocalDate,
    val items: List<JournalHistoryItem>,
    val hasAnyEntry: Boolean,
  ) : JournalHistoryUiState {
    /** 未来日は表示しないため、「今日を表示中か」と「翌日へ進めないか」は同じ条件になる。 */
    val isToday: Boolean get() = selectedDate == today
  }
}
