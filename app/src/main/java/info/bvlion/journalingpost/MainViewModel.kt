package info.bvlion.journalingpost

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import info.bvlion.journalingpost.poster.JournalPoster
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(
  private val journalPoster: JournalPoster,
) : ViewModel() {
  private val _uiState = MutableStateFlow(UiState.INIT)
  val uiState = _uiState.asStateFlow()

  fun postMessage(message: String) {
    _uiState.value = UiState.LOADING
    viewModelScope.launch {
      _uiState.value = try {
        if (journalPoster.post(message)) UiState.SUCCESS else UiState.FAILURE
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        UiState.FAILURE
      }
    }
  }

  fun resetState() {
    _uiState.value = UiState.INIT
  }

  enum class UiState {
    INIT,
    LOADING,
    SUCCESS,
    FAILURE,
  }
}
