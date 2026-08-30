package info.bvlion.journalingpost.webhook

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Custom WebhookのBody template。利用者がrequest body全体をJSONで編集し、送信時に次のplaceholderを
 * 展開する。
 *
 * - [PERIOD_START] / [PERIOD_END]: 対象期間の境界を、そのままRFC 3339のUTC文字列として差し込む
 *   (例: `2026-08-30T00:00:00Z`)。template側で引用符に囲んで使う想定。
 * - [ENTRIES]: 唯一のraw JSON placeholder。JournalEntryのJSON arrayとして、引用符なしで差し込む
 *   (`"entries": {{entries}}`)。
 * - 上記以外の `{{...}}` は置換せず、そのまま文字列として送信する。
 *
 * [render]は純粋な文字列置換だけを行い、JSONの構造を前提にしない([ENTRIES]がvalue位置へ引用符なしで
 * 入るため、template単体では有効なJSONにならないことがある)。展開後bodyが有効なJSONになるかの検証は
 * 保存時に[rendersValidJson]で行う。それ以外の特別扱いはしない。
 */
object WebhookBodyTemplateRenderer {
  const val PERIOD_START = "{{periodStart}}"
  const val PERIOD_END = "{{periodEnd}}"
  const val ENTRIES = "{{entries}}"

  /** 展開後の形式を説明・検証で示すための見本値。 */
  const val PERIOD_EXAMPLE = "2026-08-30T00:00:00Z"
  private const val SAMPLE_PERIOD_END = "2026-08-31T00:00:00Z"

  // 空arrayではなく1件入りにするのは、{{entries}}を引用符で囲むと展開結果のJSONが壊れることを
  // 保存時に検出できるようにするため(空arrayだと `"{{entries}}"` → `"[]"` が有効なJSONになってしまう)。
  private const val SAMPLE_ENTRIES_JSON = """[{"recordedAt":"2026-08-30T01:00:00Z","note":"sample"}]"""

  /** 新規設定時の初期Body template。Hostedと共通のrequest schemaに合わせている。 */
  val DEFAULT_TEMPLATE: String = """
    {
      "period": {
        "start": "$PERIOD_START",
        "end": "$PERIOD_END"
      },
      "entries": $ENTRIES
    }
  """.trimIndent()

  fun render(template: String, periodStart: String, periodEnd: String, entriesJson: String): String =
    template
      .replace(PERIOD_START, periodStart)
      .replace(PERIOD_END, periodEnd)
      .replace(ENTRIES, entriesJson)

  /** [ENTRIES]をraw JSON値、期間placeholderを見本文字列として展開した結果が有効なJSONか。 */
  fun rendersValidJson(template: String): Boolean {
    val rendered = render(template, PERIOD_EXAMPLE, SAMPLE_PERIOD_END, SAMPLE_ENTRIES_JSON)
    return try {
      Json.parseToJsonElement(rendered)
      true
    } catch (e: SerializationException) {
      false
    } catch (e: IllegalArgumentException) {
      false
    }
  }
}
