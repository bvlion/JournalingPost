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
 * `{{message}}`が引用符に囲まれていないraw JSON fragmentとしての利用(`{"value": {{message}}}`)は、
 * そもそも有効なJSONとして構文解析できないため、InvalidJsonとして自然に拒否される。
 */
object WebhookBodyTemplateRenderer {
  private val placeholderRegex = Regex("""\{\{(\w+)\}\}""")
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
    is JsonObject -> values.firstNotNullOfOrNull { it.findUnsupportedPlaceholder() }
    is JsonArray -> firstNotNullOfOrNull { it.findUnsupportedPlaceholder() }
    is JsonPrimitive -> if (isString) {
      placeholderRegex.findAll(content).map { it.groupValues[1] }.firstOrNull { it !in placeholderValues }
    } else {
      null
    }
  }

  private fun JsonElement.substitutePlaceholders(message: String, timestamp: String): JsonElement = when (this) {
    is JsonObject -> JsonObject(mapValues { (_, value) -> value.substitutePlaceholders(message, timestamp) })
    is JsonArray -> JsonArray(map { it.substitutePlaceholders(message, timestamp) })
    is JsonPrimitive -> if (isString) {
      JsonPrimitive(
        placeholderRegex.replace(content) { match -> placeholderValues.getValue(match.groupValues[1])(message, timestamp) },
      )
    } else {
      this
    }
  }
}
