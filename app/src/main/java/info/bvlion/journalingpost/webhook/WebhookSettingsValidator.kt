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

  fun validate(url: String, headers: List<WebhookHeader>, bodyTemplate: String): List<ValidationError> {
    val errors = mutableListOf<ValidationError>()
    if (!isPostableHttpUrl(url)) errors += ValidationError.INVALID_URL

    val names = headers.map { it.name.trim() }
    if (names.any { it.isBlank() }) errors += ValidationError.BLANK_HEADER_NAME

    val nonBlankLowerNames = names.filter { it.isNotBlank() }.map { it.lowercase() }
    if (nonBlankLowerNames.toSet().size != nonBlankLowerNames.size) errors += ValidationError.DUPLICATE_HEADER_NAME
    if (nonBlankLowerNames.any { it == CONTENT_TYPE_HEADER_NAME }) errors += ValidationError.RESERVED_CONTENT_TYPE_HEADER

    // CR/LFはHTTP header framingを壊す(header injection)ため、保存前に拒否する。ここで弾かないと
    // 保存自体は成功し、送信時にHTTP clientがrequest構築時に例外を投げて全ての記録がFAILEDになり続ける。
    if (headers.any { it.name.containsCrOrLf() || it.value.containsCrOrLf() }) errors += ValidationError.INVALID_HEADER_SYNTAX

    // 実際の置換値には依存しない失敗種別(InvalidJson/UnsupportedPlaceholder)だけを検証するため、
    // renderへ渡す message/timestamp はダミー値で構わない。
    val rendered = WebhookBodyTemplateRenderer.render(bodyTemplate, message = "", timestamp = "")
    if (rendered is WebhookBodyTemplateRenderer.Result.Failure) errors += ValidationError.INVALID_BODY_TEMPLATE

    return errors
  }

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
}
