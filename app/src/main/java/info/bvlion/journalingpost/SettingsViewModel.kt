package info.bvlion.journalingpost

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import info.bvlion.journalingpost.poster.WebhookConfig
import info.bvlion.journalingpost.settings.RecordMode
import info.bvlion.journalingpost.settings.RecordModeRepository
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
  private val recordModeRepository: RecordModeRepository,
) : ViewModel() {
  val recordMode: StateFlow<RecordMode> = recordModeRepository.recordMode
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecordMode.LOCAL_AND_WEBHOOK)

  val isWebhookConfigured: Boolean = WebhookConfig.isConfigured

  private val _saveFailed = MutableStateFlow(false)
  val saveFailed: StateFlow<Boolean> = _saveFailed.asStateFlow()

  // 呼び出し完了時に、より新しい選択が既に行われていないかを確認するための世代番号。
  private val requestGeneration = AtomicInteger(0)

  fun setRecordMode(mode: RecordMode) {
    val generation = requestGeneration.incrementAndGet()
    _saveFailed.value = false
    viewModelScope.launch {
      try {
        recordModeRepository.setRecordMode(mode)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        if (generation == requestGeneration.get()) _saveFailed.value = true
      }
    }
  }
}
