package info.bvlion.journalingpost.journal

import java.time.Instant

/**
 * 期間解析の対象抽出はこのinterfaceのみへ依存する。履歴表示用の[JournalEntryReader]と分けるのは、
 * 全件observeしてメモリ上でfilterするのではなく、対象期間だけをRoom側で絞って取得するため。
 * 期間は `[periodStart, periodEnd)` の半開区間で扱う。
 */
fun interface PeriodJournalEntryReader {
  suspend fun entriesInPeriod(periodStart: Instant, periodEnd: Instant): List<JournalEntry>
}
