package info.bvlion.journalingpost

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import info.bvlion.journalingpost.settings.AutoAnalysisSettings
import info.bvlion.journalingpost.settings.AutoAnalysisSettingsRepository
import info.bvlion.journalingpost.settings.AutoAnalysisTargetDay
import java.time.LocalTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 設定画面の「自動解析」セクションの状態を持つ。設定を保存したあと、[onSettingsChanged]で
 * scheduler([AutoAnalysisScheduler.reschedule])へ次回予約の作り直しを依頼する。
 *
 * 読み込み確定前は[uiState]がnullで、その間は操作を受け付けない。
 */
class AutoAnalysisSettingsViewModel(
  private val repository: AutoAnalysisSettingsRepository,
  private val onSettingsChanged: suspend () -> Unit,
) : ViewModel() {
  val uiState: StateFlow<AutoAnalysisSettingsUiState?> = repository.autoAnalysisSettings
    .map { AutoAnalysisSettingsUiState(it.enabled, it.timeOfDay, it.targetDay) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

  private val _events = Channel<AutoAnalysisSettingsEvent>(Channel.BUFFERED)
  val events: Flow<AutoAnalysisSettingsEvent> = _events.receiveAsFlow()

  fun setEnabled(enabled: Boolean) = update { it.copy(enabled = enabled) }

  /** 時刻と対象日は1つのダイアログでまとめて設定するため、1回の書き込みで更新する。 */
  fun setSchedule(timeOfDay: LocalTime, targetDay: AutoAnalysisTargetDay) =
    update { it.copy(timeOfDay = timeOfDay, targetDay = targetDay) }

  /**
   * 複数のスイッチ/選択を続けて操作したときに、read-modify-writeが交錯して片方の変更を打ち消さないよう
   * 直列化する。DataStoreへの書き込み自体はcancelせず最後まで行う。
   */
  private val updateMutex = Mutex()

  private fun update(transform: (AutoAnalysisSettings) -> AutoAnalysisSettings) {
    viewModelScope.launch {
      updateMutex.withLock {
        val current = repository.autoAnalysisSettings.first()
        val updated = transform(current)
        if (updated == current) return@withLock
        try {
          repository.setAutoAnalysisSettings(updated)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          _events.send(AutoAnalysisSettingsEvent.SaveFailed)
          return@withLock
        }
        try {
          onSettingsChanged()
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          // 設定は保存済み。次回のアプリ起動時にsyncFromSettingsで予約が拾い直される。
        }
      }
    }
  }
}

/** 設定画面の「自動解析」セクションの継続的な状態。 */
data class AutoAnalysisSettingsUiState(
  val enabled: Boolean,
  val timeOfDay: LocalTime,
  val targetDay: AutoAnalysisTargetDay,
)

/** 「自動解析」セクションで1度だけ扱う操作結果。 */
sealed interface AutoAnalysisSettingsEvent {
  data object SaveFailed : AutoAnalysisSettingsEvent
}
