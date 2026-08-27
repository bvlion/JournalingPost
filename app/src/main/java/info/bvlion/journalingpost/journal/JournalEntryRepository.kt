package info.bvlion.journalingpost.journal

/** JournalRecorderはこのinterfaceのみへ依存する。 */
interface JournalEntryRepository {
  suspend fun insert(entry: JournalEntry): Long

  suspend fun updateDeliveryStatus(id: Long, status: DeliveryStatus)
}
