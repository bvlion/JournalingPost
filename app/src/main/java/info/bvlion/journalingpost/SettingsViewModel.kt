package info.bvlion.journalingpost

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import info.bvlion.journalingpost.poster.WebhookConfig
import info.bvlion.journalingpost.settings.RecordMode
import info.bvlion.journalingpost.settings.RecordModeRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
  private val recordModeRepository: RecordModeRepository,
) : ViewModel() {
  val recordMode: StateFlow<RecordMode> = recordModeRepository.recordMode
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecordMode.LOCAL_AND_WEBHOOK)

  val isWebhookConfigured: Boolean = WebhookConfig.isConfigured

  fun setRecordMode(mode: RecordMode) {
    viewModelScope.launch { recordModeRepository.setRecordMode(mode) }
  }
}
