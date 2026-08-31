package info.bvlion.journalingpost

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import info.bvlion.journalingpost.journal.JournalRecorder
import info.bvlion.journalingpost.journal.LocalOnlyJournalRecorder
import info.bvlion.journalingpost.journal.db.JournalDatabase
import info.bvlion.journalingpost.journal.db.RoomJournalEntryRepository

/** DI frameworkは導入せず、process内でJournalRecorderを1つだけ再利用する。 */
object MainViewModelFactory : ViewModelProvider.Factory {
  private lateinit var journalRecorder: JournalRecorder

  /** 各Activityのviewmodel取得より前に呼び出すこと。 */
  fun initialize(context: Context) {
    if (::journalRecorder.isInitialized) return
    val database = JournalDatabase.getInstance(context)
    journalRecorder = LocalOnlyJournalRecorder(RoomJournalEntryRepository(database.journalEntryDao()))
  }

  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    check(::journalRecorder.isInitialized) { "MainViewModelFactory.initialize(context) was not called" }
    @Suppress("UNCHECKED_CAST")
    return MainViewModel(journalRecorder = journalRecorder) as T
  }
}
