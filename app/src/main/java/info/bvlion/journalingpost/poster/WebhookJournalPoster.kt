package info.bvlion.journalingpost.poster

import info.bvlion.journalingpost.webhook.LegacyWebhookConfig
import info.bvlion.journalingpost.webhook.LegacyWebhookConfigProvider
import info.bvlion.journalingpost.webhook.WebhookBodyTemplateRenderer
import info.bvlion.journalingpost.webhook.WebhookSettingsMigrationCoordinator
import info.bvlion.journalingpost.webhook.WebhookSettingsRepository
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
 * HTTP requestを開始せずfalseを返す(1回のpost()呼び出し内では同じsettings snapshotだけを使う)。
 * MainActivity/Widget(MoodEntryActivity)どちらの入口から呼ばれても、settingsを読む前に
 * legacy migrationの完了をここで保証する(呼び出し元ごとにmigrationコードを複製しない)。
 */
class WebhookJournalPoster(
  private val httpClient: HttpClient,
  private val webhookSettingsRepository: WebhookSettingsRepository,
  private val now: () -> Instant = Instant::now,
  private val legacyConfigProvider: () -> LegacyWebhookConfig? = LegacyWebhookConfigProvider::get,
) : JournalPoster {
  override suspend fun post(message: String): Boolean {
    WebhookSettingsMigrationCoordinator.ensureMigrated(webhookSettingsRepository, legacyConfigProvider)
    val settings = webhookSettingsRepository.settings.first() ?: return false

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
