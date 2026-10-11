package info.bvlion.journalingpost.analysis

import java.time.LocalDate

/**
 * 初回のデータ受信前(Loading)と、受信した結果として解析結果が0件(Empty)を区別する。
 * 区別しないと、既存の解析結果がある場合でも読み込み中に空状態が一瞬表示される。
 */
sealed interface AnalysisHistoryUiState {
  data object Loading : AnalysisHistoryUiState
  data object Empty : AnalysisHistoryUiState
  data class Content(val items: List<AnalysisHistoryItem>) : AnalysisHistoryUiState {
    // 一覧は解析日時とIDの降順。保存済みの対象期間の開始日ごとに最新の結果を引く。
    val latestResultsByDay: Map<LocalDate, AnalysisHistoryItem>
      get() = items.asReversed().associateBy { it.periodStart.toLocalDate() }
  }
}
