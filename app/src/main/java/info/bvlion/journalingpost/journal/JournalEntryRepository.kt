package info.bvlion.journalingpost.journal

/** JournalRecorderはこのinterfaceのみへ依存する。 */
fun interface JournalEntryRepository {
  suspend fun insert(entry: JournalEntry): Long
}
