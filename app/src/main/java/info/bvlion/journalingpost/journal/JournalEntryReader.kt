package info.bvlion.journalingpost.journal

import kotlinx.coroutines.flow.Flow

/** 履歴表示はこのinterfaceのみへ依存する。 */
fun interface JournalEntryReader {
  fun observeAll(): Flow<List<JournalEntry>>
}
