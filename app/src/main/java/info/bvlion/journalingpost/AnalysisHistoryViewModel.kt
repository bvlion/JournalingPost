package info.bvlion.journalingpost

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import info.bvlion.journalingpost.analysis.AnalysisExecutionRepository
import info.bvlion.journalingpost.analysis.AnalysisHistoryUiState
import info.bvlion.journalingpost.analysis.AnalysisResultDeleter
import info.bvlion.journalingpost.analysis.AnalysisResultReader
import info.bvlion.journalingpost.analysis.toAnalysisHistoryItems
import info.bvlion.journalingpost.time.CurrentDateProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AnalysisHistoryViewModel(
  reader: () -> AnalysisResultReader,
  private val analysisResultDeleter: () -> AnalysisResultDeleter,
  private val analysisExecutionRepository: () -> AnalysisExecutionRepository,
  currentDateProvider: CurrentDateProvider,
) : ViewModel() {
  val uiState: StateFlow<AnalysisHistoryUiState> = flow {
    emitAll(combine(reader().observeAll(), currentDateProvider.state) { results, date ->
      val items = results.toAnalysisHistoryItems(date.zoneId)
      if (items.isEmpty()) AnalysisHistoryUiState.Empty else AnalysisHistoryUiState.Content(items, date.zoneId)
    })
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnalysisHistoryUiState.Loading)

  private val _deleteSuccesses = Channel<Unit>(Channel.BUFFERED)
  val deleteSuccesses: Flow<Unit> = _deleteSuccesses.receiveAsFlow()

  private val _deleteFailures = Channel<Unit>(Channel.BUFFERED)
  val deleteFailures: Flow<Unit> = _deleteFailures.receiveAsFlow()

  /** 削除後の一覧はRoomのFlowが更新するため、ここでuiStateを直接書き換えない。 */
  fun deleteResult(id: Long) {
    viewModelScope.launch {
      try {
        analysisResultDeleter().delete(id)
        try {
          analysisExecutionRepository().removeResult(id)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          // AnalysisResultが無ければ残った対応関係は使用済み判定から除外されるため、削除自体は成功扱いにする。
        }
        _deleteSuccesses.send(Unit)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        _deleteFailures.send(Unit)
      }
    }
  }
}
