package info.bvlion.journalingpost.hosted

/**
 * Hosted APIのBearer API keyの端末保存。Androidが保存するのはAPI keyだけで、
 * Server内部のinstallation IDは保持しない(JournalingPostServer #40)。
 *
 * API keyは`POST /v1/installations`で1度発行され、そのinstallationとして以後のHosted API
 * アクセスに継続利用する。保存値は[info.bvlion.journalingpost.security.KeystoreCipher]で
 * 暗号化してからDataStoreへ書き込む。
 */
interface HostedCredentialsRepository {
  /** 保存済みのAPI key。未登録または復号不能なら null。 */
  suspend fun apiKey(): String?

  suspend fun store(apiKey: String)

  /** API keyが無効(401)と分かった場合に消す。次のHosted利用時に再登録される。 */
  suspend fun clear()
}
