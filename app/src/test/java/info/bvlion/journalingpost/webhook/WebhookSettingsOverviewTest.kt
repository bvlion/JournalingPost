package info.bvlion.journalingpost.webhook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WebhookSettingsOverviewTest {

  @Test
  fun `Loading・Unavailable・NotConfiguredはそのまま対応するoverviewになる`() {
    assertEquals(WebhookSettingsOverview.Loading, WebhookSettingsState.Loading.toOverview())
    assertEquals(WebhookSettingsOverview.Unavailable, WebhookSettingsState.Unavailable.toOverview())
    assertEquals(WebhookSettingsOverview.NotConfigured, WebhookSettingsState.NotConfigured.toOverview())
  }

  @Test
  fun `destinationはschemeとhostだけでpathやqueryを含まない`() {
    val overview = configuredOverview(url = "https://hooks.example.com/services/T000/B000/xxxxSECRETxxxx?token=abc")

    assertEquals("https://hooks.example.com", overview.destination)
  }

  @Test
  fun `明示されたportはdestinationへ残す`() {
    val overview = configuredOverview(url = "http://192.168.0.2:8080/hook")

    assertEquals("http://192.168.0.2:8080", overview.destination)
  }

  @Test
  fun `URLとして解釈できない値はdestinationへそのまま出さない`() {
    val overview = configuredOverview(url = "not a url")

    assertFalse(overview.destination.contains("not a url"))
  }

  @Test
  fun `Header名だけを持ちHeader値は持たない`() {
    val overview = configuredOverview(
      headers = listOf(
        WebhookHeader("Authorization", "Bearer secret-token"),
        WebhookHeader("X-Custom", "another-secret"),
      ),
    )

    assertEquals(listOf("Authorization", "X-Custom"), overview.headerNames)
    assertFalse(overview.toString().contains("secret"))
  }

  @Test
  fun `Body templateは本文を持たず使用しているplaceholderだけを持つ`() {
    val overview = configuredOverview(
      bodyTemplate = """{"text": "{{message}}", "at": "{{timestamp}}", "channel": "secret-channel"}""",
    )

    assertEquals(listOf("message", "timestamp"), overview.bodyTemplatePlaceholders)
    assertFalse(overview.toString().contains("secret-channel"))
  }

  @Test
  fun `placeholderを使っていないBody templateではplaceholderが空になる`() {
    val overview = configuredOverview(bodyTemplate = """{"text": "fixed"}""")

    assertEquals(emptyList<String>(), overview.bodyTemplatePlaceholders)
  }

  private fun configuredOverview(
    url: String = "https://example.com/webhook",
    headers: List<WebhookHeader> = emptyList(),
    bodyTemplate: String = """{"text": "{{message}}"}""",
  ): WebhookSettingsOverview.Configured =
    WebhookSettingsState.Configured(WebhookSettings(url, headers, bodyTemplate)).toOverview()
      as WebhookSettingsOverview.Configured
}
