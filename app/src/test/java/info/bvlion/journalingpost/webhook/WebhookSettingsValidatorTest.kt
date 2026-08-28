package info.bvlion.journalingpost.webhook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebhookSettingsValidatorTest {
  private val validUrl = "https://example.com/webhook"
  private val validBody = """{"text": "{{message}}"}"""

  @Test
  fun `妥当な設定はvalidation errorを返さない`() {
    val result = WebhookSettingsValidator.validate(
      url = validUrl,
      headers = listOf(WebhookHeader("Authorization", "Bearer xxxxx")),
      bodyTemplate = validBody,
    )

    assertTrue(result.errors.isEmpty())
  }

  @Test
  fun `URLがhttp・https以外のscheme場合はINVALID_URL`() {
    val result = WebhookSettingsValidator.validate("ftp://example.com", emptyList(), validBody)

    assertEquals(listOf(WebhookSettingsValidator.ValidationError.INVALID_URL), result.errors)
  }

  @Test
  fun `URLにhostがない場合はINVALID_URL`() {
    val result = WebhookSettingsValidator.validate("https:///path", emptyList(), validBody)

    assertEquals(listOf(WebhookSettingsValidator.ValidationError.INVALID_URL), result.errors)
  }

  @Test
  fun `URLとして構文解析できない場合はINVALID_URL`() {
    val result = WebhookSettingsValidator.validate("not a url", emptyList(), validBody)

    assertEquals(listOf(WebhookSettingsValidator.ValidationError.INVALID_URL), result.errors)
  }

  @Test
  fun `Header名が空白のみの場合はBLANK_HEADER_NAME`() {
    val result = WebhookSettingsValidator.validate(validUrl, listOf(WebhookHeader("  ", "v")), validBody)

    assertEquals(listOf(WebhookSettingsValidator.ValidationError.BLANK_HEADER_NAME), result.errors)
  }

  @Test
  fun `Header名が大文字小文字違いで重複する場合はDUPLICATE_HEADER_NAME`() {
    val result = WebhookSettingsValidator.validate(
      validUrl,
      listOf(WebhookHeader("X-API-Key", "a"), WebhookHeader("x-api-key", "b")),
      validBody,
    )

    assertEquals(listOf(WebhookSettingsValidator.ValidationError.DUPLICATE_HEADER_NAME), result.errors)
  }

  @Test
  fun `Content-TypeヘッダーはRESERVED_CONTENT_TYPE_HEADER`() {
    val result = WebhookSettingsValidator.validate(validUrl, listOf(WebhookHeader("Content-Type", "text/plain")), validBody)

    assertEquals(listOf(WebhookSettingsValidator.ValidationError.RESERVED_CONTENT_TYPE_HEADER), result.errors)
  }

  @Test
  fun `Header nameに改行を含む場合はINVALID_HEADER_SYNTAX`() {
    val result = WebhookSettingsValidator.validate(validUrl, listOf(WebhookHeader("X-Api\r\nKey", "v")), validBody)

    assertEquals(listOf(WebhookSettingsValidator.ValidationError.INVALID_HEADER_SYNTAX), result.errors)
  }

  @Test
  fun `Header valueに改行を含む場合はINVALID_HEADER_SYNTAX`() {
    val result = WebhookSettingsValidator.validate(validUrl, listOf(WebhookHeader("X-Api-Key", "v\r\nX-Injected: 1")), validBody)

    assertEquals(listOf(WebhookSettingsValidator.ValidationError.INVALID_HEADER_SYNTAX), result.errors)
  }

  @Test
  fun `colonを含むHeader nameはINVALID_HEADER_SYNTAX`() {
    val result = WebhookSettingsValidator.validate(validUrl, listOf(WebhookHeader("X:Api-Key", "v")), validBody)

    assertEquals(listOf(WebhookSettingsValidator.ValidationError.INVALID_HEADER_SYNTAX), result.errors)
  }

  @Test
  fun `空白を内部に含むHeader nameはINVALID_HEADER_SYNTAX`() {
    val result = WebhookSettingsValidator.validate(validUrl, listOf(WebhookHeader("X Api Key", "v")), validBody)

    assertEquals(listOf(WebhookSettingsValidator.ValidationError.INVALID_HEADER_SYNTAX), result.errors)
  }

  @Test
  fun `通常のHeader nameはvalidation errorを返さずtoken文字のみ許可される`() {
    val result = WebhookSettingsValidator.validate(validUrl, listOf(WebhookHeader("X-API-Key_9.test!~", "v")), validBody)

    assertTrue(result.errors.isEmpty())
  }

  @Test
  fun `Header nameの前後空白はtrimされて保存用に正規化される`() {
    val result = WebhookSettingsValidator.validate(validUrl, listOf(WebhookHeader("  Authorization  ", "v")), validBody)

    assertTrue(result.errors.isEmpty())
    assertEquals("Authorization", result.normalizedHeaders.single().name)
  }

  @Test
  fun `Header valueの前後空白はtrimされない`() {
    val result = WebhookSettingsValidator.validate(validUrl, listOf(WebhookHeader("X-API-Key", "  v  ")), validBody)

    assertEquals("  v  ", result.normalizedHeaders.single().value)
  }

  @Test
  fun `trim後に同名になるHeaderはDUPLICATE_HEADER_NAME`() {
    val result = WebhookSettingsValidator.validate(
      validUrl,
      listOf(WebhookHeader(" X-API-Key", "a"), WebhookHeader("X-API-Key ", "b")),
      validBody,
    )

    assertEquals(listOf(WebhookSettingsValidator.ValidationError.DUPLICATE_HEADER_NAME), result.errors)
  }

  @Test
  fun `trim後にContent-Typeになる場合もRESERVED_CONTENT_TYPE_HEADER`() {
    val result = WebhookSettingsValidator.validate(validUrl, listOf(WebhookHeader(" Content-Type ", "text/plain")), validBody)

    assertEquals(listOf(WebhookSettingsValidator.ValidationError.RESERVED_CONTENT_TYPE_HEADER), result.errors)
  }

  @Test
  fun `不正なJSON bodyはINVALID_BODY_TEMPLATE`() {
    val result = WebhookSettingsValidator.validate(validUrl, emptyList(), "{not valid json")

    assertEquals(listOf(WebhookSettingsValidator.ValidationError.INVALID_BODY_TEMPLATE), result.errors)
  }

  @Test
  fun `未対応のplaceholderを含むbodyはINVALID_BODY_TEMPLATE`() {
    val result = WebhookSettingsValidator.validate(validUrl, emptyList(), """{"text": "{{mood}}"}""")

    assertEquals(listOf(WebhookSettingsValidator.ValidationError.INVALID_BODY_TEMPLATE), result.errors)
  }

  @Test
  fun `object key上のplaceholderはINVALID_BODY_TEMPLATE`() {
    val result = WebhookSettingsValidator.validate(validUrl, emptyList(), """{"{{message}}": "value"}""")

    assertEquals(listOf(WebhookSettingsValidator.ValidationError.INVALID_BODY_TEMPLATE), result.errors)
  }

  @Test
  fun `hyphenを含むplaceholder記法はINVALID_BODY_TEMPLATE`() {
    val result = WebhookSettingsValidator.validate(validUrl, emptyList(), """{"text": "{{foo-bar}}"}""")

    assertEquals(listOf(WebhookSettingsValidator.ValidationError.INVALID_BODY_TEMPLATE), result.errors)
  }

  @Test
  fun `内側に空白を含むplaceholder記法はINVALID_BODY_TEMPLATE`() {
    val result = WebhookSettingsValidator.validate(validUrl, emptyList(), """{"text": "{{ message }}"}""")

    assertEquals(listOf(WebhookSettingsValidator.ValidationError.INVALID_BODY_TEMPLATE), result.errors)
  }

  @Test
  fun `複数の違反がある場合は全てのvalidation errorを返す`() {
    val result = WebhookSettingsValidator.validate(
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
      result.errors.toSet(),
    )
  }
}
