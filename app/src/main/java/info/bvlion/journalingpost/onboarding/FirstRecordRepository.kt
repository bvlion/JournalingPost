package info.bvlion.journalingpost.onboarding

import kotlinx.coroutines.flow.Flow

/**
 * fresh install後、アプリ内で最初の記録が成功したかどうかの状態(#67)。
 * 完了するまでは記録画面にウェルカム表示を出し、完了した後にAI振り返りの案内へ進める。
 */
interface FirstRecordRepository {
  val isFirstRecordCompleted: Flow<Boolean>

  /** 永続化に失敗した場合は例外を投げる(CancellationExceptionはそのまま再送出する)。 */
  suspend fun markFirstRecordCompleted()
}
