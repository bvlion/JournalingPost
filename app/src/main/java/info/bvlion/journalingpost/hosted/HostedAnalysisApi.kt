package info.bvlion.journalingpost.hosted

import info.bvlion.journalingpost.journal.JournalEntry
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import kotlinx.serialization.Serializable

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

/**
 * `POST /v1/analyses` の Response 200。Hosted契約の必須fieldが揃ってparseできることを
 * 成功の条件とする。未知fieldは無視する。`entryCount` / `model` は現在の
 * [info.bvlion.journalingpost.analysis.AnalysisResult]に保存先が無いためparseするだけ。
 */
@Serializable
internal data class HostedAnalysisResponse(
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
    internal data class Period(val start: String, val end: String)
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

// Hosted契約のresponse timestampはUTC・秒精度の `2026-08-29T09:00:05Z` 表記に固定されている。
// offset付き(`+09:00`)や小数秒など他の表記は受け付けず、INVALID_RESPONSEとして扱う。
private val hostedResponseInstantFormatter: DateTimeFormatter =
  DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'").withResolverStyle(ResolverStyle.STRICT)

internal fun String.toHostedResponseInstantOrNull(): Instant? = try {
  LocalDateTime.parse(this, hostedResponseInstantFormatter).toInstant(ZoneOffset.UTC)
} catch (e: DateTimeParseException) {
  null
}
