package info.bvlion.journalingpost.webhook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WebhookDestinationLabelTest {

  @Test
  fun `destinationLabelはschemeとhostだけでpathやqueryを含まない`() {
    val label = webhookSettings(
      url = "https://hooks.example.com/services/T000/B000/xxxxSECRETxxxx?token=abc",
    ).destinationLabel()

    assertEquals("https://hooks.example.com", label)
  }

  @Test
  fun `明示されたportはlabelへ残す`() {
    val label = webhookSettings(url = "http://192.168.0.2:8080/hook").destinationLabel()

    assertEquals("http://192.168.0.2:8080", label)
  }

  @Test
  fun `URLとして解釈できない値はlabelへそのまま出さない`() {
    val label = webhookSettings(url = "not a url").destinationLabel()

    assertFalse(label.contains("not a url"))
  }

  private fun webhookSettings(url: String) = WebhookSettings(
    url = url,
    headers = listOf(WebhookHeader("Authorization", "Bearer secret-token")),
    bodyTemplate = """{"text": "{{message}}"}""",
  )
}
