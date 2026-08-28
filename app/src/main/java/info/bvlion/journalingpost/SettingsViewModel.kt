package info.bvlion.journalingpost

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import info.bvlion.journalingpost.poster.WebhookConfig
import info.bvlion.journalingpost.settings.RecordMode
import info.bvlion.journalingpost.settings.RecordModeRepository
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

  /**
   * recordModeRepository.setRecordMode()は永続化失敗時に例外を投げる。ここで拾わずに
   * viewModelScopeへ伝播させると未処理例外としてアプリを終了させかねないため、ここで
   * catchしてsaveFailedへ反映する(recordModeRepository側で楽観的なpendingは既に
   * 元へ戻されているため、recordModeは永続化前の有効なモードへ自然に戻る)。
   */
  fun setRecordMode(mode: RecordMode) {
    viewModelScope.launch {
      _saveFailed.value = false
      try {
        recordModeRepository.setRecordMode(mode)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        _saveFailed.value = true
      }
    }
  }
}
