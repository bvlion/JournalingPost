package info.bvlion.journalingpost.journal.history

import info.bvlion.journalingpost.analysis.AnalysisResult
import info.bvlion.journalingpost.journal.JournalEntry
import java.time.ZoneId

/**
 * [zoneId]における日付ごとに新しい順でグループ化する。timestampが同一のentryが複数
 * あっても、idの降順で二次的に順序を確定させるため、変換結果の並び順は呼び出しごとに
 * 揺れない。
 */
fun List<JournalEntry>.toHistoryGroups(
  zoneId: ZoneId,
  analysisResults: List<AnalysisResult> = emptyList(),
): List<JournalHistoryGroup> =
  sortedWith(compareByDescending<JournalEntry> { it.timestamp }.thenByDescending { it.id })
    .map { it.toHistoryItem(zoneId, analysisResults) }
    .groupBy { it.date }
    .map { (date, items) -> JournalHistoryGroup(date, items) }

private fun JournalEntry.toHistoryItem(
  zoneId: ZoneId,
  analysisResults: List<AnalysisResult>,
): JournalHistoryItem {
  val zoned = timestamp.atZone(zoneId)
  return JournalHistoryItem(
    id = id,
    date = zoned.toLocalDate(),
    time = zoned.toLocalTime(),
    moodEmoji = moodEmoji,
    moodLabel = moodLabel,
    note = note,
    isUsedInAnalysis = analysisResults.any { result ->
      !timestamp.isBefore(result.periodStart) && timestamp.isBefore(result.periodEnd)
    },
  )
}
