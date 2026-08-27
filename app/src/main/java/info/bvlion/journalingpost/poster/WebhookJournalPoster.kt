package info.bvlion.journalingpost.poster

import info.bvlion.journalingpost.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.Locale

/** HTTP status < 400を成功として扱う。 */
class WebhookJournalPoster(
  private val httpClient: HttpClient,
  private val now: () -> Instant = Instant::now,
) : JournalPoster {
  override suspend fun post(message: String): Boolean {
    val response = httpClient.post(BuildConfig.POST_URL) {
      contentType(ContentType.Application.Json)
      setBody(
        WebhookRequestBody(
          event = WebhookEvent(
            ts = now().toWebhookTimestamp(),
            text = message,
          ),
        ),
      )
    }
    return response.status.value < 400
  }
}

private fun Instant.toWebhookTimestamp(): String =
  String.format(Locale.US, "%.6f", epochSecond + nano / 1_000_000_000.0)

@Serializable
internal data class WebhookRequestBody(
  @SerialName("team_id") val teamId: String = BuildConfig.TEAM_ID,
  val token: String = BuildConfig.TOKEN,
  val event: WebhookEvent,
)

@Serializable
internal data class WebhookEvent(
  val channel: String = BuildConfig.CHANNEL,
  val user: String = BuildConfig.USER,
  val ts: String,
  val text: String,
)
