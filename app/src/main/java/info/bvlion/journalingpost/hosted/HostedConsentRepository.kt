package info.bvlion.journalingpost.hosted

import kotlinx.coroutines.flow.Flow

/**
 * 「アプリが用意する解析先」を有効化する外部送信への同意状態(#67)。
 * 一度同意すれば、以後「使用しない」等へ切り替えてから選び直しても再度は求めない。
 */
interface HostedConsentRepository {
  val hasConsented: Flow<Boolean>

  /** 永続化に失敗した場合は例外を投げる(CancellationExceptionはそのまま再送出する)。 */
  suspend fun markConsented()
}
