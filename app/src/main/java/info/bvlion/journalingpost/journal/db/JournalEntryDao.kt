package info.bvlion.journalingpost.journal.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import info.bvlion.journalingpost.journal.DeliveryStatus
import info.bvlion.journalingpost.journal.JournalEntry

@Dao
internal interface JournalEntryDao {
  @Insert
  suspend fun insert(entry: JournalEntry): Long

  @Query("UPDATE journal_entries SET deliveryStatus = :status WHERE id = :id")
  suspend fun updateDeliveryStatus(id: Long, status: DeliveryStatus)
}
