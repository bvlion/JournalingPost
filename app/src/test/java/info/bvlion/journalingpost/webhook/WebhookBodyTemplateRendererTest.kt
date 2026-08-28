package info.bvlion.journalingpost.webhook

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebhookBodyTemplateRendererTest {
  @Test
  fun `messageのplaceholderが置換される`() {
    val result = WebhookBodyTemplateRenderer.render(
      template = """{"text": "{{message}}"}""",
      message = "today was good",
      timestamp = "1700000000.000000",
    )

    val json = (result as WebhookBodyTemplateRenderer.Result.Success).json
    assertEquals("today was good", Json.parseToJsonElement(json).jsonObject["text"]?.jsonPrimitive?.content)
  }

  @Test
  fun `timestampのplaceholderが置換される`() {
    val result = WebhookBodyTemplateRenderer.render(
      template = """{"ts": "{{timestamp}}"}""",
      message = "today was good",
      timestamp = "1700000000.123000",
    )

    val json = (result as WebhookBodyTemplateRenderer.Result.Success).json
    assertEquals("1700000000.123000", Json.parseToJsonElement(json).jsonObject["ts"]?.jsonPrimitive?.content)
  }

  @Test
  fun `quoteを含むmessageでも有効なJSONになる`() {
    val result = WebhookBodyTemplateRenderer.render(
      template = """{"text": "{{message}}"}""",
      message = """今日は"良かった"""",
      timestamp = "0",
    )

    val json = (result as WebhookBodyTemplateRenderer.Result.Success).json
    assertEquals("""今日は"良かった"""", Json.parseToJsonElement(json).jsonObject["text"]?.jsonPrimitive?.content)
  }

  @Test
  fun `backslashを含むmessageでも有効なJSONになる`() {
    val result = WebhookBodyTemplateRenderer.render(
      template = """{"text": "{{message}}"}""",
      message = """C:\path\to\file""",
      timestamp = "0",
    )

    val json = (result as WebhookBodyTemplateRenderer.Result.Success).json
    assertEquals("""C:\path\to\file""", Json.parseToJsonElement(json).jsonObject["text"]?.jsonPrimitive?.content)
  }

  @Test
  fun `改行を含むmessageでも有効なJSONになる`() {
    val result = WebhookBodyTemplateRenderer.render(
      template = """{"text": "{{message}}"}""",
      message = "今日は\"良かった\"\n次も頑張る",
      timestamp = "0",
    )

    val json = (result as WebhookBodyTemplateRenderer.Result.Success).json
    assertEquals("今日は\"良かった\"\n次も頑張る", Json.parseToJsonElement(json).jsonObject["text"]?.jsonPrimitive?.content)
  }

  @Test
  fun `placeholderが文字列の一部として利用できる場合も安全に置換される`() {
    val result = WebhookBodyTemplateRenderer.render(
      template = """{"text": "message: {{message}} at {{timestamp}}"}""",
      message = "today was good",
      timestamp = "1700000000.000000",
    )

    val json = (result as WebhookBodyTemplateRenderer.Result.Success).json
    assertEquals(
      "message: today was good at 1700000000.000000",
      Json.parseToJsonElement(json).jsonObject["text"]?.jsonPrimitive?.content,
    )
  }

  @Test
  fun `ネストしたobjectやarray内のplaceholderも置換される`() {
    val result = WebhookBodyTemplateRenderer.render(
      template = """{"event": {"items": ["{{message}}"], "ts": "{{timestamp}}"}}""",
      message = "today was good",
      timestamp = "1700000000.000000",
    )

    val json = (result as WebhookBodyTemplateRenderer.Result.Success).json
    val event = Json.parseToJsonElement(json).jsonObject["event"]!!.jsonObject
    assertEquals("today was good", event["items"]!!.jsonArray.single().jsonPrimitive.content)
    assertEquals("1700000000.000000", event["ts"]?.jsonPrimitive?.content)
  }

  @Test
  fun `unsupportedなplaceholderは拒否される`() {
    val result = WebhookBodyTemplateRenderer.render(
      template = """{"text": "{{mood}}"}""",
      message = "today was good",
      timestamp = "0",
    )

    assertTrue(result is WebhookBodyTemplateRenderer.Result.Failure.UnsupportedPlaceholder)
    assertEquals("mood", (result as WebhookBodyTemplateRenderer.Result.Failure.UnsupportedPlaceholder).name)
  }

  @Test
  fun `raw JSON fragmentとしてのplaceholder利用は不正JSONとして拒否される`() {
    val result = WebhookBodyTemplateRenderer.render(
      template = """{"value": {{message}}}""",
      message = "today was good",
      timestamp = "0",
    )

    assertTrue(result is WebhookBodyTemplateRenderer.Result.Failure.InvalidJson)
  }

  @Test
  fun `不正なJSONは拒否される`() {
    val result = WebhookBodyTemplateRenderer.render(
      template = "{not valid json",
      message = "today was good",
      timestamp = "0",
    )

    assertTrue(result is WebhookBodyTemplateRenderer.Result.Failure.InvalidJson)
  }

  @Test
  fun `object key上のplaceholderは名前がmessageと一致していても拒否される`() {
    val result = WebhookBodyTemplateRenderer.render(
      template = """{"{{message}}": "value"}""",
      message = "today was good",
      timestamp = "0",
    )

    assertTrue(result is WebhookBodyTemplateRenderer.Result.Failure.UnsupportedPlaceholder)
  }

  @Test
  fun `hyphenを含むplaceholder記法は拒否される`() {
    val result = WebhookBodyTemplateRenderer.render(
      template = """{"text": "{{foo-bar}}"}""",
      message = "today was good",
      timestamp = "0",
    )

    assertTrue(result is WebhookBodyTemplateRenderer.Result.Failure.UnsupportedPlaceholder)
    assertEquals("foo-bar", (result as WebhookBodyTemplateRenderer.Result.Failure.UnsupportedPlaceholder).name)
  }

  @Test
  fun `内側に空白を含むplaceholder記法は拒否される`() {
    val result = WebhookBodyTemplateRenderer.render(
      template = """{"text": "{{ message }}"}""",
      message = "today was good",
      timestamp = "0",
    )

    assertTrue(result is WebhookBodyTemplateRenderer.Result.Failure.UnsupportedPlaceholder)
    assertEquals(" message ", (result as WebhookBodyTemplateRenderer.Result.Failure.UnsupportedPlaceholder).name)
  }

  @Test
  fun `厳密な記法のplaceholderを含むtemplateは成功する`() {
    val result = WebhookBodyTemplateRenderer.render(
      template = """{"text":"{{message}}"}""",
      message = "today was good",
      timestamp = "0",
    )

    assertTrue(result is WebhookBodyTemplateRenderer.Result.Success)
  }
}
