package info.bvlion.journalingpost.hosted

import info.bvlion.journalingpost.journal.JournalEntry
import kotlinx.serialization.Serializable

/** `POST /v1/installations` のrequest body。Play Integrity tokenはこの登録時だけ送る。 */
@Serializable
internal data class HostedInstallationRequest(
  val registrationId: String,
  val integrityToken: String,
)

/**
 * `POST /v1/installations` の Response 201。AndroidはapiKeyだけを保存し、Server内部の
 * installation IDは受け取っても保持しない(契約上返らない)。
 */
@Serializable
internal data class HostedInstallationResponse(
  val installation: Installation,
) {
  @Serializable
  internal data class Installation(val apiKey: String)
}

/**
 * `POST /v1/analyses` のrequest body。共通schemaの定義元はJournalingPostServerの
 * `docs/hosted-analysis-api.md`(Server #2 / #11)。
 *
 * moodはMoodがあるentryだけに載せる。emoji/labelはどちらも文字列で、片方が空文字でよいが、
 * 両方が空のMoodは送らない。Android内部の識別子(moodId)はwireに無い。
 */
@Serializable
internal data class HostedAnalysisRequest(
  val analysisDate: String,
  val period: Period,
  val entries: List<Entry>,
) {
  @Serializable
  internal data class Period(val start: String, val end: String)

  @Serializable
  internal data class Entry(
    val recordedAt: String,
    val mood: Mood? = null,
    val note: String? = null,
  ) {
    @Serializable
    internal data class Mood(val emoji: String, val label: String)
  }
}

/** エラー応答の共通形。`code` で分岐する(Server `docs/hosted-analysis-api.md` の Error response)。 */
@Serializable
internal data class HostedErrorResponse(
  val error: HostedError? = null,
) {
  @Serializable
  internal data class HostedError(val code: String? = null)
}

/**
 * moodはemoji/labelのsnapshotで決まる(moodIdはAndroid内部用でwireに無い)。#42のMood
 * カスタマイズで絵文字だけ・名称だけのMoodがあり得るため、空白の側は空文字として送り、
 * 両方空ならmood自体を載せない。noteはinsert時にblank→nullへ正規化済み。
 */
internal fun JournalEntry.toHostedAnalysisEntry(): HostedAnalysisRequest.Entry {
  val emoji = moodEmoji?.takeIf { it.isNotBlank() }
  val label = moodLabel?.takeIf { it.isNotBlank() }
  val mood = if (emoji != null || label != null) {
    HostedAnalysisRequest.Entry.Mood(emoji = emoji.orEmpty(), label = label.orEmpty())
  } else {
    null
  }
  return HostedAnalysisRequest.Entry(recordedAt = timestamp.toString(), mood = mood, note = note)
}
