package info.bvlion.journalingpost.journal

import kotlinx.coroutines.flow.Flow

/** JournalEntry一覧の読み取りを担う境界。履歴表示はこのinterfaceのみへ依存する。 */
fun interface JournalEntryReader {
  fun observeAll(): Flow<List<JournalEntry>>
}
