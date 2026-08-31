package info.bvlion.journalingpost.webhook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebhookSettingsValidatorTest {
  private val validUrl = "https://example.com/webhook"
  private val validTemplate = """{"period":{"start":"{{periodStart}}","end":"{{periodEnd}}"},"entries":{{entries}}}"""

  private fun validate(
    url: String = validUrl,
    headers: List<WebhookHeader> = emptyList(),
    bodyTemplate: String = validTemplate,
  ) = WebhookSettingsValidator.validate(url, headers, bodyTemplate)

  @Test
  fun `妥当な設定はvalidation errorを返さない`() {
    val result = validate(headers = listOf(WebhookHeader("Authorization", "Bearer xxxxx")))

    assertTrue(result.errors.isEmpty())
  }

  @Test
  fun `初期Body templateはvalidation errorを返さない`() {
    assertTrue(validate(bodyTemplate = WebhookBodyTemplateRenderer.DEFAULT_TEMPLATE).errors.isEmpty())
  }

  @Test
  fun `URLがhttp・https以外のscheme場合はINVALID_URL`() {
    val result = validate(url = "ftp://example.com")

    assertEquals(listOf(WebhookSettingsValidator.ValidationError.INVALID_URL), result.errors)
  }

  @Test
  fun `URLにhostがない場合はINVALID_URL`() {
    assertEquals(
      listOf(WebhookSettingsValidator.ValidationError.INVALID_URL),
      validate(url = "https:///path").errors,
    )
  }

  @Test
  fun `URLとして構文解析できない場合はINVALID_URL`() {
    assertEquals(
      listOf(WebhookSettingsValidator.ValidationError.INVALID_URL),
      validate(url = "not a url").errors,
    )
  }

  @Test
  fun `Header名が空白のみの場合はBLANK_HEADER_NAME`() {
    assertEquals(
      listOf(WebhookSettingsValidator.ValidationError.BLANK_HEADER_NAME),
      validate(headers = listOf(WebhookHeader("  ", "v"))).errors,
    )
  }

  @Test
  fun `Header名が大文字小文字違いで重複する場合はDUPLICATE_HEADER_NAME`() {
    val result = validate(headers = listOf(WebhookHeader("X-API-Key", "a"), WebhookHeader("x-api-key", "b")))

    assertEquals(listOf(WebhookSettingsValidator.ValidationError.DUPLICATE_HEADER_NAME), result.errors)
  }

  @Test
  fun `Content-TypeヘッダーはRESERVED_CONTENT_TYPE_HEADER`() {
    assertEquals(
      listOf(WebhookSettingsValidator.ValidationError.RESERVED_CONTENT_TYPE_HEADER),
      validate(headers = listOf(WebhookHeader("Content-Type", "text/plain"))).errors,
    )
  }

  @Test
  fun `Header nameに改行を含む場合はINVALID_HEADER_SYNTAX`() {
    assertEquals(
      listOf(WebhookSettingsValidator.ValidationError.INVALID_HEADER_SYNTAX),
      validate(headers = listOf(WebhookHeader("X-Api\r\nKey", "v"))).errors,
    )
  }

  @Test
  fun `Header valueに改行を含む場合はINVALID_HEADER_SYNTAX`() {
    assertEquals(
      listOf(WebhookSettingsValidator.ValidationError.INVALID_HEADER_SYNTAX),
      validate(headers = listOf(WebhookHeader("X-Api-Key", "v\r\nX-Injected: 1"))).errors,
    )
  }

  @Test
  fun `colonを含むHeader nameはINVALID_HEADER_SYNTAX`() {
    assertEquals(
      listOf(WebhookSettingsValidator.ValidationError.INVALID_HEADER_SYNTAX),
      validate(headers = listOf(WebhookHeader("X:Api-Key", "v"))).errors,
    )
  }

  @Test
  fun `通常のHeader nameはvalidation errorを返さずtoken文字のみ許可される`() {
    assertTrue(validate(headers = listOf(WebhookHeader("X-API-Key_9.test!~", "v"))).errors.isEmpty())
  }

  @Test
  fun `Header nameの前後空白はtrimされて保存用に正規化される`() {
    val result = validate(headers = listOf(WebhookHeader("  Authorization  ", "v")))

    assertTrue(result.errors.isEmpty())
    assertEquals("Authorization", result.normalizedHeaders.single().name)
  }

  @Test
  fun `Header valueの前後空白はtrimされない`() {
    val result = validate(headers = listOf(WebhookHeader("X-API-Key", "  v  ")))

    assertEquals("  v  ", result.normalizedHeaders.single().value)
  }

  @Test
  fun `trim後に同名になるHeaderはDUPLICATE_HEADER_NAME`() {
    val result = validate(headers = listOf(WebhookHeader(" X-API-Key", "a"), WebhookHeader("X-API-Key ", "b")))

    assertEquals(listOf(WebhookSettingsValidator.ValidationError.DUPLICATE_HEADER_NAME), result.errors)
  }

  @Test
  fun `entriesを展開すると有効なJSONにならないtemplateはINVALID_BODY_TEMPLATE`() {
    assertEquals(
      listOf(WebhookSettingsValidator.ValidationError.INVALID_BODY_TEMPLATE),
      validate(bodyTemplate = "{ not json").errors,
    )
  }

  @Test
  fun `entriesを引用符で囲むとJSON arrayにならずINVALID_BODY_TEMPLATE`() {
    // {{entries}}はraw JSON値として展開されるため、引用符で囲むと文字列 + array連結になり壊れる。
    assertEquals(
      listOf(WebhookSettingsValidator.ValidationError.INVALID_BODY_TEMPLATE),
      validate(bodyTemplate = """{"entries":"{{entries}}"}""").errors,
    )
  }

  @Test
  fun `未知のplaceholderが残ってJSONを壊す場合もINVALID_BODY_TEMPLATE`() {
    assertEquals(
      listOf(WebhookSettingsValidator.ValidationError.INVALID_BODY_TEMPLATE),
      validate(bodyTemplate = """{"x": {{unknown}}}""").errors,
    )
  }

  @Test
  fun `未知のplaceholderが文字列内なら有効なJSONのままでerrorにならない`() {
    assertTrue(validate(bodyTemplate = """{"x": "{{unknown}}", "entries": {{entries}}}""").errors.isEmpty())
  }

  @Test
  fun `headerErrorsは違反した行のindexだけを内訳として返す`() {
    val result = validate(
      headers = listOf(
        WebhookHeader("Authorization", "Bearer x"),
        WebhookHeader("", "v"),
      ),
    )

    assertEquals(setOf(1), result.headerErrors.keys)
    assertEquals(listOf(WebhookSettingsValidator.ValidationError.BLANK_HEADER_NAME), result.headerErrors.getValue(1))
  }

  @Test
  fun `重複したheaderはどちらの行もheaderErrorsに載る`() {
    val result = validate(headers = listOf(WebhookHeader("X-Key", "a"), WebhookHeader("x-key", "b")))

    assertEquals(setOf(0, 1), result.headerErrors.keys)
  }

  @Test
  fun `複数の違反がある場合は全てのvalidation errorを返す`() {
    val result = validate(
      url = "not a url",
      headers = listOf(WebhookHeader("", "v")),
      bodyTemplate = "{ not json",
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
