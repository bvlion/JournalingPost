package info.bvlion.journalingpost.hosted

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/**
 * Hosted利用に必要な匿名installation登録とBearer API keyの取得。
 *
 * 既に端末へAPI keyがあればそれを返し、無ければ `POST /v1/installations` で発行して保存する。
 * 登録に失敗したら[HostedRegistrationException]を投げ、retryできるかどうかを[HostedRegistrationException.retryable]で伝える。
 */
class HostedInstallationRegistrar(
  private val httpClient: HttpClient,
  private val credentialsRepository: HostedCredentialsRepository,
  private val baseUrl: String,
) {
  private val json = Json { ignoreUnknownKeys = true }

  suspend fun apiKey(): String {
    credentialsRepository.apiKey()?.let { return it }

    val response = try {
      httpClient.post("$baseUrl/v1/installations")
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // 到達しない・timeout・接続断。いずれもそのまま再試行してよい。
      throw HostedRegistrationException(retryable = true, cause = e)
    }

    if (!response.status.isSuccess()) {
      throw HostedRegistrationException(
        retryable = response.status.value >= HttpStatusCode.InternalServerError.value,
      )
    }

    val apiKey = try {
      json.decodeFromString<HostedInstallationResponse>(response.bodyAsText()).installation.apiKey
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      throw HostedRegistrationException(retryable = false, cause = e)
    }
    if (apiKey.isBlank()) throw HostedRegistrationException(retryable = false)

    credentialsRepository.store(apiKey)
    return apiKey
  }
}

/** installation登録の失敗。[retryable]がtrueなら同じ操作をそのまま再試行してよい。 */
class HostedRegistrationException(
  val retryable: Boolean,
  cause: Throwable? = null,
) : Exception("Hosted installation registration failed (retryable=$retryable)", cause)
