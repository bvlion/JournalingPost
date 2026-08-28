package info.bvlion.journalingpost.poster

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebhookConfigTest {
  @Test
  fun `全ての値が有効な文字列なら設定済みと判定する`() {
    assertTrue(isWebhookConfigValid("https://example.com", "T1", "TOKEN", "C1", "U1"))
  }

  @Test
  fun `いずれかが空文字なら未設定と判定する`() {
    assertFalse(isWebhookConfigValid("https://example.com", "", "TOKEN", "C1", "U1"))
  }

  @Test
  fun `いずれかが空白のみなら未設定と判定する`() {
    assertFalse(isWebhookConfigValid("https://example.com", "T1", "   ", "C1", "U1"))
  }

  @Test
  fun `いずれかが文字列nullなら未設定と判定する`() {
    assertFalse(isWebhookConfigValid("null", "T1", "TOKEN", "C1", "U1"))
  }
}
