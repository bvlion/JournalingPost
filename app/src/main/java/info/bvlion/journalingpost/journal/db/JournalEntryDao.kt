package info.bvlion.journalingpost.journal.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import info.bvlion.journalingpost.journal.DeliveryStatus
import info.bvlion.journalingpost.journal.JournalEntry
import kotlinx.coroutines.flow.Flow

@Dao
internal interface JournalEntryDao {
  @Insert
  suspend fun insert(entry: JournalEntry): Long

  @Query("UPDATE journal_entries SET deliveryStatus = :status WHERE id = :id")
  suspend fun updateDeliveryStatus(id: Long, status: DeliveryStatus)

  @Query("DELETE FROM journal_entries WHERE id = :id")
  suspend fun deleteById(id: Long)

  /** 同一timestampのentryがあり得るため、idを二次キーにして順序を安定させる。 */
  @Query("SELECT * FROM journal_entries ORDER BY timestamp DESC, id DESC")
  fun observeAll(): Flow<List<JournalEntry>>
}
