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

  /** header行に紐づくerror。画面ではsection末尾でなく該当行の下に出す。 */
  val HEADER_ERRORS: Set<ValidationError> = setOf(
    ValidationError.BLANK_HEADER_NAME,
    ValidationError.DUPLICATE_HEADER_NAME,
    ValidationError.RESERVED_CONTENT_TYPE_HEADER,
    ValidationError.INVALID_HEADER_SYNTAX,
  )

  // errorsへ足すheader error種別の並び(単一違反時の従来の順序を保つ)。
  private val HEADER_ERROR_ORDER: List<ValidationError> = listOf(
    ValidationError.BLANK_HEADER_NAME,
    ValidationError.DUPLICATE_HEADER_NAME,
    ValidationError.RESERVED_CONTENT_TYPE_HEADER,
    ValidationError.INVALID_HEADER_SYNTAX,
  )

  /**
   * normalizedHeadersはHeader名の前後空白をtrimしたもの。errorsが空の場合、保存にはこの値を使う
   * (validationはtrim済み名で判定しているため、永続化・送信でtrim前の元の値を使うと
   * 「validationは通ったのに実際の送信で使われる名前は別」という不整合が生じる)。
   *
   * headerErrorsはheaderリストのindex別の内訳。どの行の問題かを画面が示せるようにする。
   */
  data class Result(
    val errors: List<ValidationError>,
    val normalizedHeaders: List<WebhookHeader>,
    val headerErrors: Map<Int, List<ValidationError>> = emptyMap(),
  )

  fun validate(url: String, headers: List<WebhookHeader>, bodyTemplate: String): Result {
    val errors = mutableListOf<ValidationError>()
    if (!isPostableHttpUrl(url)) errors += ValidationError.INVALID_URL

    val normalizedHeaders = headers.map { it.copy(name = it.name.trim()) }
    val headerErrors = headerErrors(normalizedHeaders)
    HEADER_ERROR_ORDER.forEach { kind ->
      if (headerErrors.values.any { kind in it }) errors += kind
    }

    // placeholderを見本値へ展開した結果が有効なJSONになることだけを検証する。{{entries}}の見本は
    // 1件入りのarrayを使う(空arrayだと `"{{entries}}"` のように引用符で囲まれた壊れた記述も
    // valid扱いになってしまうため。詳細はWebhookBodyTemplateRenderer)。未知の {{...}} はそのまま
    // 残るため、それがJSONを壊す場合もここで弾かれる。
    if (!WebhookBodyTemplateRenderer.rendersValidJson(bodyTemplate)) errors += ValidationError.INVALID_BODY_TEMPLATE

    return Result(errors, normalizedHeaders, headerErrors)
  }

  private fun headerErrors(normalizedHeaders: List<WebhookHeader>): Map<Int, List<ValidationError>> {
    val lowerNames = normalizedHeaders.map { it.name.lowercase() }
    val duplicatedLowerNames = lowerNames
      .filter { it.isNotBlank() }
      .groupingBy { it }
      .eachCount()
      .filterValues { it > 1 }
      .keys

    return buildMap {
      normalizedHeaders.forEachIndexed { index, header ->
        val rowErrors = buildList {
          if (header.name.isBlank()) add(ValidationError.BLANK_HEADER_NAME)
          if (header.name.isNotBlank() && lowerNames[index] in duplicatedLowerNames) {
            add(ValidationError.DUPLICATE_HEADER_NAME)
          }
          if (lowerNames[index] == CONTENT_TYPE_HEADER_NAME) add(ValidationError.RESERVED_CONTENT_TYPE_HEADER)
          // HTTP field-nameとして送信できないnameは保存自体を拒否する。ここで弾かないと、保存は成功し
          // 送信時にHTTP clientがrequest構築時に例外を投げて解析がすべて失敗し続ける。
          // valueはtoken文字に制限せず、CR/LF(header injection)だけを拒否する。
          if ((header.name.isNotBlank() && !header.name.isValidHeaderNameToken()) || header.value.containsCrOrLf()) {
            add(ValidationError.INVALID_HEADER_SYNTAX)
          }
        }
        if (rowErrors.isNotEmpty()) put(index, rowErrors)
      }
    }
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
