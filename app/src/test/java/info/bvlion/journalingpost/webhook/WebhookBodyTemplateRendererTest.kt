package info.bvlion.journalingpost.webhook

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebhookBodyTemplateRendererTest {
  private val entriesJson = """[{"recordedAt":"2026-08-30T01:00:00Z","note":"メモ"}]"""

  @Test
  fun `periodとentriesのplaceholderを展開する`() {
    val rendered = WebhookBodyTemplateRenderer.render(
      template = WebhookBodyTemplateRenderer.DEFAULT_TEMPLATE,
      periodStart = "2026-08-30T00:00:00Z",
      periodEnd = "2026-08-31T00:00:00Z",
      entriesJson = entriesJson,
    )

    val json = Json.parseToJsonElement(rendered).jsonObject
    assertEquals("2026-08-30T00:00:00Z", json.getValue("period").jsonObject.getValue("start").jsonPrimitive.content)
    assertEquals("2026-08-31T00:00:00Z", json.getValue("period").jsonObject.getValue("end").jsonPrimitive.content)
    val entries = json.getValue("entries").jsonArray
    assertEquals(1, entries.size)
    assertEquals("メモ", entries[0].jsonObject.getValue("note").jsonPrimitive.content)
  }

  @Test
  fun `entriesはraw JSON値として引用符なしで差し込まれる`() {
    val rendered = WebhookBodyTemplateRenderer.render(
      template = """{"entries": {{entries}}}""",
      periodStart = "s",
      periodEnd = "e",
      entriesJson = "[]",
    )

    assertEquals("""{"entries": []}""", rendered)
  }

  @Test
  fun `未知のplaceholderは置換されずそのまま残る`() {
    val rendered = WebhookBodyTemplateRenderer.render(
      template = """{"a": "{{unknown}}", "b": "{{periodStart}}"}""",
      periodStart = "2026-08-30T00:00:00Z",
      periodEnd = "2026-08-31T00:00:00Z",
      entriesJson = "[]",
    )

    assertEquals("""{"a": "{{unknown}}", "b": "2026-08-30T00:00:00Z"}""", rendered)
  }

  @Test
  fun `entries内に現れるplaceholder風の文字列は置換対象にならない`() {
    // periodStart/periodEndを先に置換し、そのあとentriesを差し込むため、entryのnote等に
    // {{periodStart}} が入っていてもそのまま送られる。
    val rendered = WebhookBodyTemplateRenderer.render(
      template = """{"entries": {{entries}}}""",
      periodStart = "REPLACED",
      periodEnd = "e",
      entriesJson = """[{"note":"{{periodStart}}"}]""",
    )

    assertEquals("""{"entries": [{"note":"{{periodStart}}"}]}""", rendered)
  }

  @Test
  fun `初期templateはrendersValidJsonを満たす`() {
    assertTrue(WebhookBodyTemplateRenderer.rendersValidJson(WebhookBodyTemplateRenderer.DEFAULT_TEMPLATE))
  }

  @Test
  fun `entriesを引用符で囲むとrendersValidJsonがfalse`() {
    assertFalse(WebhookBodyTemplateRenderer.rendersValidJson("""{"entries": "{{entries}}"}"""))
  }

  @Test
  fun `未知placeholderがJSON構造を壊すとrendersValidJsonがfalse`() {
    assertFalse(WebhookBodyTemplateRenderer.rendersValidJson("""{"x": {{unknown}}}"""))
  }

  @Test
  fun `未知placeholderが文字列内ならrendersValidJsonはtrue`() {
    assertTrue(WebhookBodyTemplateRenderer.rendersValidJson("""{"x": "{{unknown}}", "entries": {{entries}}}"""))
  }
}
