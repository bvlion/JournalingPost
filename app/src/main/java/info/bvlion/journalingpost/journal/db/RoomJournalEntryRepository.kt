package info.bvlion.journalingpost.journal.db

import info.bvlion.journalingpost.journal.DeliveryStatus
import info.bvlion.journalingpost.journal.JournalEntry
import info.bvlion.journalingpost.journal.JournalEntryReader
import info.bvlion.journalingpost.journal.JournalEntryRepository
import kotlinx.coroutines.flow.Flow

internal class RoomJournalEntryRepository(
  private val dao: JournalEntryDao,
) : JournalEntryRepository, JournalEntryReader {
  override suspend fun insert(entry: JournalEntry): Long = dao.insert(entry)

  override suspend fun updateDeliveryStatus(id: Long, status: DeliveryStatus) =
    dao.updateDeliveryStatus(id, status)

  override fun observeAll(): Flow<List<JournalEntry>> = dao.observeAll()
}
