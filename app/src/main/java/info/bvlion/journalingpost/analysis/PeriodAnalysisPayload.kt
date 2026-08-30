package info.bvlion.journalingpost.analysis

import info.bvlion.journalingpost.journal.JournalEntry
import kotlinx.serialization.Serializable

/**
 * `{{entries}}` placeholderへ展開するJournalEntryのJSON表現。Hosted解析APIのrequest
 * `entries[]` と同じ形にする(共通schemaの定義元はJournalingPostServerの
 * `docs/hosted-analysis-api.md`)。`moodId` やAndroid内部IDは送らない。
 */
@Serializable
internal data class WebhookAnalysisEntry(
  val recordedAt: String,
  val mood: Mood? = null,
  val note: String? = null,
) {
  @Serializable
  internal data class Mood(
    val emoji: String,
    val label: String,
  )
}

/**
 * Custom Webhookからの成功response。Hosted解析APIの成功response(`analysis.text` ほか)と同じ契約に
 * 揃える。Android側で使うのは `analysis.text`(振り返り本文)だけで、他フィールドは無視する。
 */
@Serializable
internal data class WebhookAnalysisResponse(
  val analysis: Analysis,
) {
  @Serializable
  internal data class Analysis(
    val text: String,
  )
}

/** moodは記録時点のsnapshot(emoji/label)をそのまま載せる。noteはinsert時にblank→nullで正規化済み。 */
internal fun JournalEntry.toWebhookAnalysisEntry(): WebhookAnalysisEntry =
  WebhookAnalysisEntry(
    recordedAt = timestamp.toString(),
    mood = if (moodId != null) WebhookAnalysisEntry.Mood(moodEmoji.orEmpty(), moodLabel.orEmpty()) else null,
    note = note,
  )
