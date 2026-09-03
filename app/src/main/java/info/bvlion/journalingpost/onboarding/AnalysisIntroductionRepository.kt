package info.bvlion.journalingpost.onboarding

import kotlinx.coroutines.flow.Flow

/**
 * AI解析機能の初回案内(#67)を、fresh install後の初回起動時に一度だけ見せたかどうかの状態。
 */
interface AnalysisIntroductionRepository {
  val isIntroductionSeen: Flow<Boolean>

  /** 永続化に失敗した場合は例外を投げる(CancellationExceptionはそのまま再送出する)。 */
  suspend fun markIntroductionSeen()
}
