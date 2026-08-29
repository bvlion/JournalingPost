package info.bvlion.journalingpost.webhook

import java.net.URI
import java.net.URISyntaxException

/**
 * 親Settingsで「現在の送信先」を安全に示すための短い文字列。scheme + host(必要ならport)のみを使う。
 * Slack等ではURLのpath以降にtokenが入る形式があるため、URL全体は表示に使わない。
 */
fun WebhookSettings.destinationLabel(): String {
  val uri = try {
    URI(url)
  } catch (e: URISyntaxException) {
    return MASKED_DESTINATION
  }
  val scheme = uri.scheme
  val host = uri.host
  if (scheme.isNullOrBlank() || host.isNullOrBlank()) return MASKED_DESTINATION
  val port = if (uri.port >= 0) ":${uri.port}" else ""
  return "$scheme://$host$port"
}

private const val MASKED_DESTINATION = "(表示できない形式のURL)"
