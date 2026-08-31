package info.bvlion.journalingpost.journal.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import info.bvlion.journalingpost.journal.JournalEntry
import java.time.Instant
import kotlinx.coroutines.flow.Flow

@Dao
internal interface JournalEntryDao {
  @Insert
  suspend fun insert(entry: JournalEntry): Long

  @Query("DELETE FROM journal_entries WHERE id = :id")
  suspend fun deleteById(id: Long)

  /** 同一timestampのentryがあり得るため、idを二次キーにして順序を安定させる。 */
  @Query("SELECT * FROM journal_entries ORDER BY timestamp DESC, id DESC")
  fun observeAll(): Flow<List<JournalEntry>>

  /** 期間解析の対象抽出。`[periodStart, periodEnd)` の半開区間で、古い順に取得する。 */
  @Query(
    "SELECT * FROM journal_entries WHERE timestamp >= :periodStart AND timestamp < :periodEnd " +
      "ORDER BY timestamp ASC, id ASC",
  )
  suspend fun entriesInPeriod(periodStart: Instant, periodEnd: Instant): List<JournalEntry>
}
