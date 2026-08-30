package info.bvlion.journalingpost.webhook

import java.net.URI
import java.net.URISyntaxException

object WebhookSettingsValidator {
  enum class ValidationError {
    INVALID_URL,
    BLANK_HEADER_NAME,
    DUPLICATE_HEADER_NAME,
    RESERVED_CONTENT_TYPE_HEADER,
    INVALID_HEADER_SYNTAX,
    INVALID_BODY_TEMPLATE,
  }

  /**
   * normalizedHeadersはHeader名の前後空白をtrimしたもの。errorsが空の場合、保存にはこの値を使う
   * (validationはtrim済み名で判定しているため、永続化・送信でtrim前の元の値を使うと
   * 「validationは通ったのに実際の送信で使われる名前は別」という不整合が生じる)。
   */
  data class Result(
    val errors: List<ValidationError>,
    val normalizedHeaders: List<WebhookHeader>,
  )

  fun validate(url: String, headers: List<WebhookHeader>, bodyTemplate: String): Result {
    val errors = mutableListOf<ValidationError>()
    if (!isPostableHttpUrl(url)) errors += ValidationError.INVALID_URL

    val normalizedHeaders = headers.map { it.copy(name = it.name.trim()) }
    if (normalizedHeaders.any { it.name.isBlank() }) errors += ValidationError.BLANK_HEADER_NAME

    val nonBlankLowerNames = normalizedHeaders.map { it.name }.filter { it.isNotBlank() }.map { it.lowercase() }
    if (nonBlankLowerNames.toSet().size != nonBlankLowerNames.size) errors += ValidationError.DUPLICATE_HEADER_NAME
    if (nonBlankLowerNames.any { it == CONTENT_TYPE_HEADER_NAME }) errors += ValidationError.RESERVED_CONTENT_TYPE_HEADER

    // HTTP field-nameとして送信できないnameは保存自体を拒否する。ここで弾かないと、保存は成功し
    // 送信時にHTTP clientがrequest構築時に例外を投げて解析がすべて失敗し続ける。
    // valueはtoken文字に制限せず、CR/LF(header injection)だけを拒否する。
    val hasInvalidHeaderSyntax = normalizedHeaders.any { header ->
      (header.name.isNotBlank() && !header.name.isValidHeaderNameToken()) || header.value.containsCrOrLf()
    }
    if (hasInvalidHeaderSyntax) errors += ValidationError.INVALID_HEADER_SYNTAX

    // placeholderを見本値へ展開した結果が有効なJSONになることだけを検証する。{{entries}}の見本は
    // 1件入りのarrayを使う(空arrayだと `"{{entries}}"` のように引用符で囲まれた壊れた記述も
    // valid扱いになってしまうため。詳細はWebhookBodyTemplateRenderer)。未知の {{...}} はそのまま
    // 残るため、それがJSONを壊す場合もここで弾かれる。
    if (!WebhookBodyTemplateRenderer.rendersValidJson(bodyTemplate)) errors += ValidationError.INVALID_BODY_TEMPLATE

    return Result(errors, normalizedHeaders)
  }

  // RFC 7230のtoken(field-nameの構文)。ASCII英数字とHEADER_NAME_EXTRA_TOKEN_CHARSのみ許可する。
  private fun String.isValidHeaderNameToken(): Boolean =
    isNotEmpty() && all { it.isAsciiAlphaNumeric() || it in HEADER_NAME_EXTRA_TOKEN_CHARS }

  private fun Char.isAsciiAlphaNumeric(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

  private fun String.containsCrOrLf(): Boolean = any { it == '\r' || it == '\n' }

  private fun isPostableHttpUrl(url: String): Boolean {
    val uri = try {
      URI(url)
    } catch (e: URISyntaxException) {
      return false
    }
    val scheme = uri.scheme?.lowercase()
    return (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
  }

  private const val CONTENT_TYPE_HEADER_NAME = "content-type"
  private const val HEADER_NAME_EXTRA_TOKEN_CHARS = "!#$%&'*+-.^_`|~"
}
