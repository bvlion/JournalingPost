package info.bvlion.journalingpost.webhook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LegacyWebhookConfigProviderTest {
  @Test
  fun `全ての値が揃っていればLegacyWebhookConfigを返す`() {
    val config = legacyWebhookConfigOrNull("https://example.com", "T1", "TOKEN", "C1", "U1")

    assertEquals(LegacyWebhookConfig("https://example.com", "T1", "TOKEN", "C1", "U1"), config)
  }

  @Test
  fun `いずれかが空文字ならnullを返す`() {
    assertNull(legacyWebhookConfigOrNull("https://example.com", "", "TOKEN", "C1", "U1"))
  }

  @Test
  fun `いずれかが空白のみならnullを返す`() {
    assertNull(legacyWebhookConfigOrNull("https://example.com", "T1", "   ", "C1", "U1"))
  }
}
