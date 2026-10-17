package info.bvlion.journalingpost.journal.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import info.bvlion.journalingpost.analysis.AnalysisResult
import info.bvlion.journalingpost.analysis.db.AnalysisResultDao
import info.bvlion.journalingpost.journal.JournalEntry

@Database(entities = [JournalEntry::class, AnalysisResult::class], version = 4, exportSchema = true)
@TypeConverters(Converters::class)
internal abstract class JournalDatabase : RoomDatabase() {
  abstract fun journalEntryDao(): JournalEntryDao

  abstract fun analysisResultDao(): AnalysisResultDao

  companion object {
    /** process内で1つだけ生成すること。生成箇所はAppContainerへ集約している。 */
    fun create(context: Context): JournalDatabase =
      Room.databaseBuilder(
        context.applicationContext,
        JournalDatabase::class.java,
        "journal.db",
      ).addMigrations(
        *(1..3).map { version ->
          Migration(version, 4) { database ->
            if (version < 3) {
              // 削除済みの記録IDも再利用しないよう、テーブル再作成時に採番の上限を引き継ぐ。
              val lastEntryId = database.query("SELECT seq FROM sqlite_sequence WHERE name = 'journal_entries'")
                .use { if (it.moveToFirst()) it.getLong(0) else 0L }
              database.execSQL(
                "CREATE TABLE journal_entries_updated (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                  "timestamp INTEGER NOT NULL, moodId TEXT, moodEmoji TEXT, moodLabel TEXT, note TEXT, " +
                  "source TEXT NOT NULL, supplement TEXT)",
              )
              database.execSQL(
                "INSERT INTO journal_entries_updated (id, timestamp, moodId, moodEmoji, moodLabel, note, source) " +
                  "SELECT id, timestamp, moodId, moodEmoji, moodLabel, note, source FROM journal_entries",
              )
              database.execSQL("DROP TABLE journal_entries")
              database.execSQL("ALTER TABLE journal_entries_updated RENAME TO journal_entries")
              database.execSQL("DELETE FROM sqlite_sequence WHERE name = 'journal_entries'")
              database.execSQL(
                "INSERT INTO sqlite_sequence (name, seq) VALUES ('journal_entries', ?)",
                arrayOf(lastEntryId),
              )
            } else {
              database.execSQL("ALTER TABLE journal_entries ADD COLUMN supplement TEXT")
            }
            if (version == 1) {
              database.execSQL(
                "CREATE TABLE IF NOT EXISTS analysis_results (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                  "periodStart INTEGER NOT NULL, periodEnd INTEGER NOT NULL, analyzedAt INTEGER NOT NULL, " +
                  "body TEXT NOT NULL)",
              )
            }
          }
        }.toTypedArray(),
      ).build()
  }
}
