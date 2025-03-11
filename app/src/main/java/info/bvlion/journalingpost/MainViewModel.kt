package info.bvlion.journalingpost

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import info.bvlion.journalingpost.httpclient.sendPostRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {
  private val _uiState = MutableStateFlow(UiState.INIT)
  val uiState = _uiState.asStateFlow()

  fun postMessage(message: String) {
    _uiState.value = UiState.LOADING
    viewModelScope.launch {
      try {
        val res = sendPostRequest(message)
        _uiState.value = if (res.status.value < 400) {
          UiState.SUCCESS
        } else {
          UiState.FAILURE
        }
      } catch (e: Exception) {
        _uiState.value = UiState.FAILURE
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