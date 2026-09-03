package info.bvlion.journalingpost

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import info.bvlion.journalingpost.onboarding.AnalysisIntroductionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * fresh install後の初回起動時に一度だけ出す、AI解析機能の案内(#67)の状態。
 */
class AnalysisIntroductionViewModel(
  private val repository: AnalysisIntroductionRepository,
) : ViewModel() {
  val uiState: StateFlow<AnalysisIntroductionUiState> = repository.isIntroductionSeen
    .map { seen -> AnalysisIntroductionUiState(shouldShow = !seen) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnalysisIntroductionUiState())

  private val _events = Channel<AnalysisIntroductionEvent>(Channel.BUFFERED)
  val events: Flow<AnalysisIntroductionEvent> = _events.receiveAsFlow()

  /** 「設定する」を選んだ。以後は案内を出さず、解析・連携の設定項目を示す遷移を要求する。 */
  fun onSetupSelected() {
    viewModelScope.launch {
      markSeen()
      _events.send(AnalysisIntroductionEvent.NavigateToAnalysisSettings)
    }
  }

  /** 「今はしない」、またはダイアログを閉じた。「使用しない」のまま以後は案内を出さない。 */
  fun onDismissed() {
    viewModelScope.launch { markSeen() }
  }

  private suspend fun markSeen() {
    try {
      repository.markIntroductionSeen()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // 既読状態は補助的な表示制御のみに使う。保存に失敗しても今回の案内自体は進め、
      // 次回起動時に読み込みへ再挑戦させる(#59の自動解析等と違い、外部送信には影響しない)。
    }
  }
}

/**
 * [shouldShow]は読み込み確定前はfalse。案内はfresh installの利用開始を妨げてはならないため、
 * 読み込み中を「表示待ち」扱いにはしない。
 */
data class AnalysisIntroductionUiState(
  val shouldShow: Boolean = false,
)

sealed interface AnalysisIntroductionEvent {
  /** 解析・連携の設定へ移動し、その項目を一度だけ示す。 */
  data object NavigateToAnalysisSettings : AnalysisIntroductionEvent
}
