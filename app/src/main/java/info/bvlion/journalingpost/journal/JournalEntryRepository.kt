package info.bvlion.journalingpost.journal

/** JournalEntryのローカル永続化を担う境界。JournalRecorderはこのinterfaceのみへ依存する。 */
interface JournalEntryRepository {
  suspend fun insert(entry: JournalEntry): Long

  suspend fun updateDeliveryStatus(id: Long, status: DeliveryStatus)
}
