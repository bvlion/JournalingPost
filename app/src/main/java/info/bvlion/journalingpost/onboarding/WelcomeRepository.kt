package info.bvlion.journalingpost.onboarding

import kotlinx.coroutines.flow.Flow

/**
 * fresh install後の初回起動時に一度だけ出す、記録を促すウェルカムダイアログ(#67)の既読状態。
 * 閉じるまでの間だけダイアログを表示し、閉じた後は最初の記録が完了するまで
 * 気分を選ぶ場所への視覚誘導に切り替える。
 */
interface WelcomeRepository {
  val isWelcomeDialogSeen: Flow<Boolean>

  /** 永続化に失敗した場合は例外を投げる(CancellationExceptionはそのまま再送出する)。 */
  suspend fun markWelcomeDialogSeen()
}
