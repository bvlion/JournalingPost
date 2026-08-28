package info.bvlion.journalingpost.webhook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebhookSettingsValidatorTest {
  private val validUrl = "https://example.com/webhook"
  private val validBody = """{"text": "{{message}}"}"""

  @Test
  fun `妥当な設定はvalidation errorを返さない`() {
    val errors = WebhookSettingsValidator.validate(
      url = validUrl,
      headers = listOf(WebhookHeader("Authorization", "Bearer xxxxx")),
      bodyTemplate = validBody,
    )

    assertTrue(errors.isEmpty())
  }

  @Test
  fun `URLがhttp・https以外のscheme場合はINVALID_URL`() {
    val errors = WebhookSettingsValidator.validate("ftp://example.com", emptyList(), validBody)

    assertEquals(listOf(WebhookSettingsValidator.ValidationError.INVALID_URL), errors)
  }

  @Test
  fun `URLにhostがない場合はINVALID_URL`() {
    val errors = WebhookSettingsValidator.validate("https:///path", emptyList(), validBody)

    assertEquals(listOf(WebhookSettingsValidator.ValidationError.INVALID_URL), errors)
  }

  @Test
  fun `URLとして構文解析できない場合はINVALID_URL`() {
    val errors = WebhookSettingsValidator.validate("not a url", emptyList(), validBody)

    assertEquals(listOf(WebhookSettingsValidator.ValidationError.INVALID_URL), errors)
  }

  @Test
  fun `Header名が空白のみの場合はBLANK_HEADER_NAME`() {
    val errors = WebhookSettingsValidator.validate(validUrl, listOf(WebhookHeader("  ", "v")), validBody)

    assertEquals(listOf(WebhookSettingsValidator.ValidationError.BLANK_HEADER_NAME), errors)
  }

  @Test
  fun `Header名が大文字小文字違いで重複する場合はDUPLICATE_HEADER_NAME`() {
    val errors = WebhookSettingsValidator.validate(
      validUrl,
      listOf(WebhookHeader("X-API-Key", "a"), WebhookHeader("x-api-key", "b")),
      validBody,
    )

    assertEquals(listOf(WebhookSettingsValidator.ValidationError.DUPLICATE_HEADER_NAME), errors)
  }

  @Test
  fun `Content-TypeヘッダーはRESERVED_CONTENT_TYPE_HEADER`() {
    val errors = WebhookSettingsValidator.validate(validUrl, listOf(WebhookHeader("Content-Type", "text/plain")), validBody)

    assertEquals(listOf(WebhookSettingsValidator.ValidationError.RESERVED_CONTENT_TYPE_HEADER), errors)
  }

  @Test
  fun `不正なJSON bodyはINVALID_BODY_TEMPLATE`() {
    val errors = WebhookSettingsValidator.validate(validUrl, emptyList(), "{not valid json")

    assertEquals(listOf(WebhookSettingsValidator.ValidationError.INVALID_BODY_TEMPLATE), errors)
  }

  @Test
  fun `未対応のplaceholderを含むbodyはINVALID_BODY_TEMPLATE`() {
    val errors = WebhookSettingsValidator.validate(validUrl, emptyList(), """{"text": "{{mood}}"}""")

    assertEquals(listOf(WebhookSettingsValidator.ValidationError.INVALID_BODY_TEMPLATE), errors)
  }

  @Test
  fun `複数の違反がある場合は全てのvalidation errorを返す`() {
    val errors = WebhookSettingsValidator.validate(
      url = "not a url",
      headers = listOf(WebhookHeader("", "v")),
      bodyTemplate = "{not valid json",
    )

    assertEquals(
      setOf(
        WebhookSettingsValidator.ValidationError.INVALID_URL,
        WebhookSettingsValidator.ValidationError.BLANK_HEADER_NAME,
        WebhookSettingsValidator.ValidationError.INVALID_BODY_TEMPLATE,
      ),
      errors.toSet(),
    )
  }
}
