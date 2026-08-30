package info.bvlion.journalingpost.analysis

import info.bvlion.journalingpost.journal.JournalEntry
import kotlinx.serialization.Serializable

/**
 * Custom Webhookへ送る期間解析request。JournalEntryの原本表現(Instant)はISO-8601 UTC文字列へ
 * 変換して送る。schemaVersionは、将来Webhook受け口の形式を変えたときに互換を判断できるよう固定で送る。
 * 期間は `[periodStart, periodEnd)` の半開区間。
 */
@Serializable
internal data class PeriodAnalysisRequest(
  val schemaVersion: Int = SCHEMA_VERSION,
  val periodStart: String,
  val periodEnd: String,
  val entries: List<Entry>,
) {
  @Serializable
  internal data class Entry(
    val timestamp: String,
    val mood: Mood?,
    val note: String?,
  )

  @Serializable
  internal data class Mood(
    val id: String,
    val emoji: String,
    val label: String,
  )

  companion object {
    const val SCHEMA_VERSION = 1
  }
}

/** Custom Webhookからの期間解析response。bodyだけをAnalysisResultへ保存し、他フィールドは無視する。 */
@Serializable
internal data class PeriodAnalysisResponse(
  val body: String,
)

/** moodはmoodIdを持つentryだけに載せる。emoji/labelは記録時点のsnapshotをそのまま送る。 */
internal fun JournalEntry.toPeriodAnalysisEntry(): PeriodAnalysisRequest.Entry =
  PeriodAnalysisRequest.Entry(
    timestamp = timestamp.toString(),
    mood = moodId?.let { id ->
      PeriodAnalysisRequest.Mood(id = id, emoji = moodEmoji.orEmpty(), label = moodLabel.orEmpty())
    },
    note = note,
  )
