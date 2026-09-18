package info.bvlion.journalingpost.analysis

import info.bvlion.journalingpost.journal.JournalEntry
import kotlinx.serialization.Serializable

/**
 * `{{entries}}` placeholderへ展開するJournalEntryのJSON表現。Hosted解析APIのrequest
 * `entries[]` と同じ形にする(共通schemaの定義元はJournalingPostServerの
 * `docs/hosted-analysis-api.md`)。wire上のmoodはemoji/labelのsnapshotだけで、
 * Android内部の識別子(moodId等)は送らない。
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
 * moodはwire契約に合わせemoji/labelのsnapshotが揃っているときだけ載せる(moodIdはAndroid内部用で
 * wireに無いため判定に使わない)。noteはinsert時にblank→nullで正規化済み。
 */
internal fun JournalEntry.toWebhookAnalysisEntry(): WebhookAnalysisEntry =
  WebhookAnalysisEntry(
    recordedAt = timestamp.toString(),
    mood = if (moodEmoji != null && moodLabel != null) WebhookAnalysisEntry.Mood(moodEmoji, moodLabel) else null,
    note = note,
  )
