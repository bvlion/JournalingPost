package info.bvlion.journalingpost.webhook

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Body templateをJSONとしてparseし、文字列値の中に現れるplaceholderだけを置換して再serializeする。
 * raw String.replace()でJSON全体を書き換えないのは、messageに含まれる引用符・バックスラッシュ・改行等で
 * JSONが壊れるのを防ぐため。置換後の値はJsonPrimitiveとして組み立て直すため、再serialize時に
 * kotlinx.serializationが自動でエスケープする。
 *
 * `{{...}}`という記法自体はplaceholder構文として広く検出するが、置換対象として認めるのは
 * `message`/`timestamp`という厳密な名前のみ(空白・hyphen等を含む記法や大文字小文字違いは拒否する)。
 * さらにJSON object keyでの利用は、名前がmessage/timestampと一致していても常に拒否する
 * (placeholderはJSON文字列valueの中でのみ利用可能というIssue #11の契約を保証するため)。
 * `{{message}}`が引用符に囲まれていないraw JSON fragmentとしての利用(`{"value": {{message}}}`)は、
 * そもそも有効なJSONとして構文解析できないため、InvalidJsonとして自然に拒否される。
 */
object WebhookBodyTemplateRenderer {
  private val placeholderSyntax = Regex("""\{\{[^{}]*\}\}""")
  private val placeholderValues: Map<String, (message: String, timestamp: String) -> String> = mapOf(
    "message" to { message, _ -> message },
    "timestamp" to { _, timestamp -> timestamp },
  )

  sealed interface Result {
    data class Success(val json: String) : Result

    sealed interface Failure : Result {
      data object InvalidJson : Failure
      data class UnsupportedPlaceholder(val name: String) : Failure
    }
  }

  fun render(template: String, message: String, timestamp: String): Result {
    val root = try {
      Json.parseToJsonElement(template)
    } catch (e: SerializationException) {
      return Result.Failure.InvalidJson
    } catch (e: IllegalArgumentException) {
      return Result.Failure.InvalidJson
    }

    val unsupported = root.findUnsupportedPlaceholder()
    if (unsupported != null) return Result.Failure.UnsupportedPlaceholder(unsupported)

    val rendered = root.substitutePlaceholders(message, timestamp)
    return Result.Success(Json.encodeToString(JsonElement.serializer(), rendered))
  }

  private fun JsonElement.findUnsupportedPlaceholder(): String? = when (this) {
    is JsonObject -> entries.firstNotNullOfOrNull { (key, value) ->
      // key上の記法は、名前がmessage/timestampであっても常に不正扱いにする。
      placeholderSyntax.find(key)?.let { it.placeholderName() } ?: value.findUnsupportedPlaceholder()
    }
    is JsonArray -> firstNotNullOfOrNull { it.findUnsupportedPlaceholder() }
    is JsonPrimitive -> if (isString) {
      placeholderSyntax.findAll(content).map { it.placeholderName() }.firstOrNull { it !in placeholderValues }
    } else {
      null
    }
  }

  private fun JsonElement.substitutePlaceholders(message: String, timestamp: String): JsonElement = when (this) {
    is JsonObject -> JsonObject(mapValues { (_, value) -> value.substitutePlaceholders(message, timestamp) })
    is JsonArray -> JsonArray(map { it.substitutePlaceholders(message, timestamp) })
    is JsonPrimitive -> if (isString) {
      JsonPrimitive(
        placeholderSyntax.replace(content) { match -> placeholderValues.getValue(match.placeholderName())(message, timestamp) },
      )
    } else {
      this
    }
  }

  private fun MatchResult.placeholderName(): String = value.removeSurrounding("{{", "}}")
}
