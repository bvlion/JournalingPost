package info.bvlion.journalingpost.journal.db

import info.bvlion.journalingpost.journal.JournalEntry
import info.bvlion.journalingpost.journal.JournalEntryDeleter
import info.bvlion.journalingpost.journal.JournalEntryReader
import info.bvlion.journalingpost.journal.JournalEntryRepository
import info.bvlion.journalingpost.journal.PeriodJournalEntryReader
import java.time.Instant
import kotlinx.coroutines.flow.Flow

internal class RoomJournalEntryRepository(
  private val dao: JournalEntryDao,
) : JournalEntryRepository, JournalEntryReader, JournalEntryDeleter, PeriodJournalEntryReader {
  override suspend fun insert(entry: JournalEntry): Long = dao.insert(entry)

  override fun observeAll(): Flow<List<JournalEntry>> = dao.observeAll()

  override suspend fun delete(id: Long) = dao.deleteById(id)

  override suspend fun entriesInPeriod(periodStart: Instant, periodEnd: Instant): List<JournalEntry> =
    dao.entriesInPeriod(periodStart, periodEnd)
}
