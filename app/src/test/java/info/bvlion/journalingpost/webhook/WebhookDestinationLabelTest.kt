package info.bvlion.journalingpost.webhook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebhookDestinationLabelTest {

  @Test
  fun `labelはschemeとhostだけでpathやqueryを含まない`() {
    val label = webhookSettings(
      url = "https://hooks.example.com/services/T000/B000/xxxxSECRETxxxx?token=abc",
    ).destinationLabelOrNull()

    assertEquals("https://hooks.example.com", label)
  }

  @Test
  fun `明示されたportはlabelへ残す`() {
    val label = webhookSettings(url = "http://192.168.0.2:8080/hook").destinationLabelOrNull()

    assertEquals("http://192.168.0.2:8080", label)
  }

  @Test
  fun `URLとして解釈できない値はnullを返す`() {
    assertNull(webhookSettings(url = "not a url").destinationLabelOrNull())
  }

  private fun webhookSettings(url: String) = WebhookSettings(
    url = url,
    headers = listOf(WebhookHeader("Authorization", "Bearer secret-token")),
    bodyTemplate = "{}",
  )
}
