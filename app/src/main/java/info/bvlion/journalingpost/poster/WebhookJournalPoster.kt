package info.bvlion.journalingpost.poster

import info.bvlion.journalingpost.webhook.WebhookBodyTemplateRenderer
import info.bvlion.journalingpost.webhook.WebhookSettingsRepository
import info.bvlion.journalingpost.webhook.WebhookSettingsState
import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.flow.first

/**
 * HTTP status < 400を成功として扱う。設定snapshotの取得・body renderのどちらかが失敗した場合は
 * HTTP requestを開始せずfalseを返す。1回のpost()では同じsettings snapshotだけを使う。
 */
class WebhookJournalPoster(
  private val httpClient: HttpClient,
  private val webhookSettingsRepository: WebhookSettingsRepository,
  private val now: () -> Instant = Instant::now,
) : JournalPoster {
  override suspend fun post(message: String): Boolean {
    val state = webhookSettingsRepository.settings.first()
    val settings = (state as? WebhookSettingsState.Configured)?.settings ?: return false

    val rendered = WebhookBodyTemplateRenderer.render(
      template = settings.bodyTemplate,
      message = message,
      timestamp = now().toWebhookTimestamp(),
    )
    val body = (rendered as? WebhookBodyTemplateRenderer.Result.Success)?.json ?: return false

    val response = httpClient.post(settings.url) {
      contentType(ContentType.Application.Json)
      headers {
        settings.headers.forEach { header -> append(header.name, header.value) }
      }
      setBody(body)
    }
    return response.status.value < 400
  }
}

private fun Instant.toWebhookTimestamp(): String =
  String.format(Locale.US, "%.6f", epochSecond + nano / 1_000_000_000.0)
