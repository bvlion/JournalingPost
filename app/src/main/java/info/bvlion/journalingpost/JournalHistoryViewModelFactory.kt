package info.bvlion.journalingpost

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import info.bvlion.journalingpost.journal.JournalEntryReader
import info.bvlion.journalingpost.journal.db.JournalDatabase
import info.bvlion.journalingpost.journal.db.RoomJournalEntryRepository

/** JournalDatabaseはMainViewModelFactoryと同じsingletonを再利用する。 */
object JournalHistoryViewModelFactory : ViewModelProvider.Factory {
  private lateinit var journalEntryReader: JournalEntryReader

  /** 各Activityのviewmodel取得より前に呼び出すこと。 */
  fun initialize(context: Context) {
    if (::journalEntryReader.isInitialized) return
    val database = JournalDatabase.getInstance(context)
    journalEntryReader = RoomJournalEntryRepository(database.journalEntryDao())
  }

  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    check(::journalEntryReader.isInitialized) { "JournalHistoryViewModelFactory.initialize(context) was not called" }
    @Suppress("UNCHECKED_CAST")
    return JournalHistoryViewModel(journalEntryReader) as T
  }
}
