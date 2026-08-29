package info.bvlion.journalingpost.journal.history

/**
 * 初回のデータ受信前(Loading)と、受信した結果として記録が0件(Empty)を区別する。
 * 区別しないと、既存の履歴がある場合でも読み込み中に空状態が一瞬表示される。
 */
sealed interface JournalHistoryUiState {
  data object Loading : JournalHistoryUiState
  data object Empty : JournalHistoryUiState
  data class Content(val groups: List<JournalHistoryGroup>) : JournalHistoryUiState
}
