package info.bvlion.journalingpost.webhook

import java.net.URI
import java.net.URISyntaxException

/**
 * 設定画面の「確認」表示へ渡す、保存済みCustom Webhook設定の要約。
 *
 * WebhookSettingsそのものはURLのpath以降・Header値・Body template本文にsecretを含み得るため、
 * 編集フォーム以外のUIへは渡さず、この要約だけを渡す。
 */
sealed interface WebhookSettingsOverview {
  data object Loading : WebhookSettingsOverview
  data object Unavailable : WebhookSettingsOverview
  data object NotConfigured : WebhookSettingsOverview

  /**
   * [destination]は送信先URLのscheme + host(必要ならport)のみ。Slack等のようにpathやqueryへ
   * tokenが入る形式があるため、URL全体は表示用に持たない。[headerNames]も名前だけで値は持たない。
   * [bodyTemplatePlaceholders]はBody templateが実際に使っているplaceholder名で、template本文は持たない。
   */
  data class Configured(
    val destination: String,
    val headerNames: List<String>,
    val bodyTemplatePlaceholders: List<String>,
  ) : WebhookSettingsOverview
}

fun WebhookSettingsState.toOverview(): WebhookSettingsOverview = when (this) {
  WebhookSettingsState.Loading -> WebhookSettingsOverview.Loading
  WebhookSettingsState.Unavailable -> WebhookSettingsOverview.Unavailable
  WebhookSettingsState.NotConfigured -> WebhookSettingsOverview.NotConfigured
  is WebhookSettingsState.Configured -> WebhookSettingsOverview.Configured(
    destination = settings.url.toDestinationOrMasked(),
    headerNames = settings.headers.map { it.name },
    bodyTemplatePlaceholders = settings.bodyTemplate.usedPlaceholderNames(),
  )
}

private fun String.toDestinationOrMasked(): String {
  val uri = try {
    URI(this)
  } catch (e: URISyntaxException) {
    return MASKED_DESTINATION
  }
  val scheme = uri.scheme
  val host = uri.host
  if (scheme.isNullOrBlank() || host.isNullOrBlank()) return MASKED_DESTINATION
  val port = if (uri.port >= 0) ":${uri.port}" else ""
  return "$scheme://$host$port"
}

/** placeholderの利用有無だけを取り出す。template本文はここから復元できない。 */
private fun String.usedPlaceholderNames(): List<String> =
  SUPPORTED_PLACEHOLDER_NAMES.filter { contains("{{$it}}") }

private const val MASKED_DESTINATION = "(表示できない形式のURL)"
private val SUPPORTED_PLACEHOLDER_NAMES = listOf("message", "timestamp")
