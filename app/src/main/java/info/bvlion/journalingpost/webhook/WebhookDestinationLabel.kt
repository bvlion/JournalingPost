package info.bvlion.journalingpost.webhook

import java.net.URI
import java.net.URISyntaxException

/**
 * 親Settingsで「現在の送信先」を安全に示すための短い文字列。scheme + host(必要ならport)のみを使う。
 * Slack等ではURLのpath以降にtokenが入る形式があるため、URL全体は表示に使わない。
 *
 * 安全なlabelを作れない場合はnullを返す(URLは保存時に検証しているため通常は到達しない)。
 * 呼び出し側は「設定済み」等のfallback表示を出す。
 */
fun WebhookSettings.destinationLabelOrNull(): String? {
  val uri = try {
    URI(url)
  } catch (e: URISyntaxException) {
    return null
  }
  val scheme = uri.scheme
  val host = uri.host
  if (scheme.isNullOrBlank() || host.isNullOrBlank()) return null
  val port = if (uri.port >= 0) ":${uri.port}" else ""
  return "$scheme://$host$port"
}
