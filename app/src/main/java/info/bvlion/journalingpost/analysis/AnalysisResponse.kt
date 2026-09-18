package info.bvlion.journalingpost.analysis

import info.bvlion.journalingpost.settings.AnalysisIntegration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * HostedとCustom Webhookが共有する成功responseのschema。必須fieldが揃い、未知fieldを無視して
 * parseできることを成功条件とする。`entryCount` / `model` は現在の[AnalysisResult]に保存先が
 * 無いためparseするだけで永続化しない。
 */
@Serializable
internal data class AnalysisResponse(
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

private val analysisResponseJson = Json { ignoreUnknownKeys = true }

internal fun parseAnalysisSuccessResponse(
  responseText: String,
  integration: AnalysisIntegration,
): PeriodAnalysisOutcome.Success? {
  val analysis = try {
    analysisResponseJson.decodeFromString<AnalysisResponse>(responseText).analysis
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    return null
  }
  if (analysis.text.isBlank()) return null

  return PeriodAnalysisOutcome.Success(
    periodStart = analysis.period.start.toAnalysisResponseInstantOrNull() ?: return null,
    periodEnd = analysis.period.end.toAnalysisResponseInstantOrNull() ?: return null,
    analyzedAt = analysis.analyzedAt.toAnalysisResponseInstantOrNull() ?: return null,
    body = analysis.text,
    integration = integration,
  )
}

// 成功responseのtimestampはUTC・秒精度の `2026-08-29T09:00:05Z` 表記に固定されている。
// offset付き(`+09:00`)や小数秒など他の表記は受け付けない。
private val analysisResponseInstantFormatter: DateTimeFormatter =
  DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'").withResolverStyle(ResolverStyle.STRICT)

private fun String.toAnalysisResponseInstantOrNull(): Instant? = try {
  LocalDateTime.parse(this, analysisResponseInstantFormatter).toInstant(ZoneOffset.UTC)
} catch (e: DateTimeParseException) {
  null
}
