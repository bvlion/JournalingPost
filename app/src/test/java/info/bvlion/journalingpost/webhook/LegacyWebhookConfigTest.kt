package info.bvlion.journalingpost.webhook

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyWebhookConfigTest {
  @Test
  fun `現行Webhookと同じ意味のBody templateを生成する`() {
    val legacy = LegacyWebhookConfig(postUrl = "https://example.com/webhook", teamId = "T1", token = "TOKEN", channel = "C1", user = "U1")

    val settings = legacy.toWebhookSettings()

    assertEquals("https://example.com/webhook", settings.url)
    assertEquals(emptyList<WebhookHeader>(), settings.headers)
    val body = Json.parseToJsonElement(settings.bodyTemplate).jsonObject
    assertEquals("T1", body["team_id"]?.jsonPrimitive?.content)
    assertEquals("TOKEN", body["token"]?.jsonPrimitive?.content)
    val event = body["event"]!!.jsonObject
    assertEquals("C1", event["channel"]?.jsonPrimitive?.content)
    assertEquals("U1", event["user"]?.jsonPrimitive?.content)
    assertEquals("{{timestamp}}", event["ts"]?.jsonPrimitive?.content)
    assertEquals("{{message}}", event["text"]?.jsonPrimitive?.content)
  }

  @Test
  fun `legacy値にquoteが含まれても安全にJSON escapeされる`() {
    val legacy = LegacyWebhookConfig(
      postUrl = "https://example.com/webhook",
      teamId = """T"1""",
      token = "TOKEN",
      channel = "C1",
      user = "U1",
    )

    val settings = legacy.toWebhookSettings()

    val body = Json.parseToJsonElement(settings.bodyTemplate).jsonObject
    assertEquals("""T"1""", body["team_id"]?.jsonPrimitive?.content)
  }

  @Test
  fun `生成したBody templateはrenderで安全にplaceholderが置換される`() {
    val legacy = LegacyWebhookConfig("https://example.com/webhook", "T1", "TOKEN", "C1", "U1")
    val settings = legacy.toWebhookSettings()

    val rendered = WebhookBodyTemplateRenderer.render(settings.bodyTemplate, message = "today was good", timestamp = "1700000000.000000")

    val body = (rendered as WebhookBodyTemplateRenderer.Result.Success).json
    val event = Json.parseToJsonElement(body).jsonObject["event"]!!.jsonObject
    assertEquals("today was good", event["text"]?.jsonPrimitive?.content)
    assertEquals("1700000000.000000", event["ts"]?.jsonPrimitive?.content)
  }
}
