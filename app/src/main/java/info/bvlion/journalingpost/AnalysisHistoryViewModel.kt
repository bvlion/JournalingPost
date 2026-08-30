package info.bvlion.journalingpost

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import info.bvlion.journalingpost.analysis.AnalysisHistoryUiState
import info.bvlion.journalingpost.analysis.AnalysisResultReader
import info.bvlion.journalingpost.analysis.toAnalysisHistoryItems
import java.time.ZoneId
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class AnalysisHistoryViewModel(
  reader: AnalysisResultReader,
  zoneId: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {
  val uiState: StateFlow<AnalysisHistoryUiState> = reader.observeAll()
    .map { results ->
      val items = results.toAnalysisHistoryItems(zoneId)
      if (items.isEmpty()) AnalysisHistoryUiState.Empty else AnalysisHistoryUiState.Content(items)
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnalysisHistoryUiState.Loading)
}
