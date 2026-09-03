package info.bvlion.journalingpost

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import info.bvlion.journalingpost.onboarding.AnalysisIntroductionRepository
import info.bvlion.journalingpost.onboarding.FirstRecordRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * fresh install後の初回体験(#67)の状態。
 *
 * まず最初の記録が終わるまでは記録画面にウェルカム表示を出し、AI振り返りの案内は出さない。
 * 最初の記録が成功した後に、AI振り返りの案内を一度だけ出す。
 */
class OnboardingViewModel(
  private val firstRecordRepository: FirstRecordRepository,
  private val analysisIntroductionRepository: AnalysisIntroductionRepository,
) : ViewModel() {
  val uiState: StateFlow<OnboardingUiState> = combine(
    firstRecordRepository.isFirstRecordCompleted,
    analysisIntroductionRepository.isIntroductionSeen,
  ) { firstRecordCompleted, introductionSeen ->
    OnboardingUiState(
      showWelcomeHint = !firstRecordCompleted,
      showAnalysisIntroduction = firstRecordCompleted && !introductionSeen,
    )
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OnboardingUiState())

  private val _events = Channel<OnboardingEvent>(Channel.BUFFERED)
  val events: Flow<OnboardingEvent> = _events.receiveAsFlow()

  /**
   * 記録が成功するたびに呼ぶ。まだ最初の記録が完了していなければ、ウェルカム表示を終わらせて
   * AI振り返りの案内へ進める。既に完了済みの記録では何も変わらない(繰り返し呼んでよい)。
   */
  fun onRecordSucceeded() {
    viewModelScope.launch {
      try {
        firstRecordRepository.markFirstRecordCompleted()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // 完了記録に失敗しても記録自体は成功しているため、次回の記録成功時に再度扱う。
      }
    }
  }

  /** 「設定する」を選んだ。以後は案内を出さず、AIによる振り返りの設定項目を示す遷移を要求する。 */
  fun onAnalysisIntroductionSetupSelected() {
    viewModelScope.launch {
      markAnalysisIntroductionSeen()
      _events.send(OnboardingEvent.NavigateToAnalysisSettings)
    }
  }

  /** 「今はしない」、またはダイアログを閉じた。「使用しない」のまま以後は案内を出さない。 */
  fun onAnalysisIntroductionDismissed() {
    viewModelScope.launch { markAnalysisIntroductionSeen() }
  }

  private suspend fun markAnalysisIntroductionSeen() {
    try {
      analysisIntroductionRepository.markIntroductionSeen()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // 既読状態は補助的な表示制御のみに使う。保存に失敗しても今回の案内自体は進め、
      // 次回起動時に読み込みへ再挑戦させる(#59の自動解析等と違い、外部送信には影響しない)。
    }
  }
}

/**
 * [showWelcomeHint] [showAnalysisIntroduction]とも読み込み確定前はfalse。読み込み中を
 * 「表示待ち」扱いにはせず、fresh installの利用開始を妨げない。
 */
data class OnboardingUiState(
  val showWelcomeHint: Boolean = false,
  val showAnalysisIntroduction: Boolean = false,
)

sealed interface OnboardingEvent {
  /** AIによる振り返りの設定へ移動し、その項目を一度だけ示す。 */
  data object NavigateToAnalysisSettings : OnboardingEvent
}
