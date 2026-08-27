package info.bvlion.journalingpost.journal.history

import info.bvlion.journalingpost.journal.JournalEntry
import java.time.ZoneId

/**
 * JournalEntryの一覧を、[zoneId]における日付・時刻順(新しい順)の表示用グループへ変換する。
 *
 * timestampが同一のentryが複数あっても、idの降順で二次的に順序を確定させるため、
 * 変換結果の並び順が呼び出しごとに揺れることはない。
 */
fun List<JournalEntry>.toHistoryGroups(zoneId: ZoneId): List<JournalHistoryGroup> =
  sortedWith(compareByDescending<JournalEntry> { it.timestamp }.thenByDescending { it.id })
    .map { it.toHistoryItem(zoneId) }
    .groupBy { it.date }
    .map { (date, items) -> JournalHistoryGroup(date, items) }

private fun JournalEntry.toHistoryItem(zoneId: ZoneId): JournalHistoryItem {
  val zoned = timestamp.atZone(zoneId)
  return JournalHistoryItem(
    id = id,
    date = zoned.toLocalDate(),
    time = zoned.toLocalTime(),
    moodEmoji = moodEmoji,
    moodLabel = moodLabel,
    note = note,
  )
}
