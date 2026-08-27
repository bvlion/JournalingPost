package info.bvlion.journalingpost.journal.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import info.bvlion.journalingpost.journal.JournalEntry

@Database(entities = [JournalEntry::class], version = 1, exportSchema = true)
@TypeConverters(Converters::class)
internal abstract class JournalDatabase : RoomDatabase() {
  abstract fun journalEntryDao(): JournalEntryDao

  companion object {
    @Volatile
    private var instance: JournalDatabase? = null

    fun getInstance(context: Context): JournalDatabase =
      instance ?: synchronized(this) {
        instance ?: Room.databaseBuilder(
          context.applicationContext,
          JournalDatabase::class.java,
          "journal.db",
        ).build().also { instance = it }
      }
  }
}
