package info.bvlion.journalingpost.journal.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import info.bvlion.journalingpost.analysis.AnalysisResult
import info.bvlion.journalingpost.analysis.db.AnalysisResultDao
import info.bvlion.journalingpost.journal.JournalEntry

/**
 * version 2でanalysis_resultsテーブルを追加。公開前のためversion 1の開発用DBとの互換
 * migrationは持たず、旧DBが残る端末はアプリデータ削除または再インストールで対応する。
 */
@Database(entities = [JournalEntry::class, AnalysisResult::class], version = 2, exportSchema = true)
@TypeConverters(Converters::class)
internal abstract class JournalDatabase : RoomDatabase() {
  abstract fun journalEntryDao(): JournalEntryDao

  abstract fun analysisResultDao(): AnalysisResultDao

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
