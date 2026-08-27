package info.bvlion.journalingpost

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import info.bvlion.journalingpost.journal.JournalRecorder
import info.bvlion.journalingpost.journal.JournalSource
import info.bvlion.journalingpost.mood.MoodSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(
  private val journalRecorder: JournalRecorder,
) : ViewModel() {
  private val _uiState = MutableStateFlow(UiState.INIT)
  val uiState = _uiState.asStateFlow()

  fun record(note: String, mood: MoodSnapshot? = null, source: JournalSource) {
    _uiState.value = UiState.LOADING
    viewModelScope.launch {
      _uiState.value = try {
        if (journalRecorder.record(note, mood, source)) UiState.SUCCESS else UiState.FAILURE
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
