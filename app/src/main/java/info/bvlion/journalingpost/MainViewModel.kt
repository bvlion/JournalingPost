package info.bvlion.journalingpost

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import info.bvlion.journalingpost.journal.DeliveryStatus
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

  /** 記録処理中(LOADING)の呼び出しは無視する。二重タップ等でJournalEntryが重複保存されるのを防ぐ。 */
  fun record(note: String, mood: MoodSnapshot? = null, source: JournalSource) {
    if (_uiState.value == UiState.LOADING) return
    _uiState.value = UiState.LOADING
    viewModelScope.launch {
      _uiState.value = try {
        // Webhook配送のFAILEDは記録自体の失敗ではないため、FAILUREへは絶対にマッピングしない。
        when (journalRecorder.record(note, mood, source)) {
          DeliveryStatus.SENT, DeliveryStatus.NOT_REQUIRED, DeliveryStatus.PENDING -> UiState.SUCCESS
          DeliveryStatus.FAILED -> UiState.SUCCESS_DELIVERY_FAILED
        }
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
    SUCCESS_DELIVERY_FAILED,
    FAILURE,
  }
}
