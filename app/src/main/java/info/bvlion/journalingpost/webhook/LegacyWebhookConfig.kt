package info.bvlion.journalingpost.webhook

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

data class LegacyWebhookConfig(
  val postUrl: String,
  val teamId: String,
  val token: String,
  val channel: String,
  val user: String,
)

/** 現行Webhookのpayload形状(team_id/token/event.channel/event.user/event.ts/event.text)と同じ意味になるtemplateを生成する。 */
fun LegacyWebhookConfig.toWebhookSettings(): WebhookSettings {
  val body = buildJsonObject {
    put("team_id", teamId)
    put("token", token)
    putJsonObject("event") {
      put("channel", channel)
      put("user", user)
      put("ts", "{{timestamp}}")
      put("text", "{{message}}")
    }
  }
  return WebhookSettings(
    url = postUrl,
    headers = emptyList(),
    bodyTemplate = Json.encodeToString(JsonElement.serializer(), body),
  )
}
