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
 * Custom Webhookからの成功response。Hosted解析APIの `POST /v1/analyses` Response 200と同じschemaとして
 * 扱う(定義元はJournalingPostServerの `docs/hosted-analysis-api.md`)。ここで定義した必須fieldが揃って
 * parseできることを成功responseの条件とする。未知fieldはHosted契約どおり無視する。
 *
 * `entryCount` / `model` は現在の[AnalysisResult]に保存先が無いためparseするだけで永続化しない。
 */
@Serializable
internal data class WebhookAnalysisResponse(
  val analysis: Analysis,
) {
  @Serializable
  internal data class Analysis(
    val period: Period,
    val analyzedAt: String,
    val entryCount: Int,
    val model: String,
    val text: String,
  ) {
    @Serializable
    internal data class Period(
      val start: String,
      val end: String,
    )
  }
}

/** moodは記録時点のsnapshot(emoji/label)をそのまま載せる。noteはinsert時にblank→nullで正規化済み。 */
internal fun JournalEntry.toWebhookAnalysisEntry(): WebhookAnalysisEntry =
  WebhookAnalysisEntry(
    recordedAt = timestamp.toString(),
    mood = if (moodId != null) WebhookAnalysisEntry.Mood(moodEmoji.orEmpty(), moodLabel.orEmpty()) else null,
    note = note,
  )
