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
 * version 2でanalysis_resultsテーブルを追加。version 3でjournal_entriesのdeliveryStatus列を削除
 * (記録都度のWebhook配送を廃止し期間解析へ移行したため)。公開前のため互換migrationは持たず、
 * 旧DBが残る端末はアプリデータ削除または再インストールで対応する。
 */
@Database(entities = [JournalEntry::class, AnalysisResult::class], version = 3, exportSchema = true)
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
      ).build()
  }
}
