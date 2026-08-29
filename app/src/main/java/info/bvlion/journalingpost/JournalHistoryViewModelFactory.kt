package info.bvlion.journalingpost

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import info.bvlion.journalingpost.journal.JournalEntryDeleter
import info.bvlion.journalingpost.journal.JournalEntryReader
import info.bvlion.journalingpost.journal.db.JournalDatabase
import info.bvlion.journalingpost.journal.db.RoomJournalEntryRepository

/** JournalDatabaseはMainViewModelFactoryと同じsingletonを再利用する。 */
object JournalHistoryViewModelFactory : ViewModelProvider.Factory {
  @Volatile
  private var dependencies: Dependencies? = null

  /** 各Activityのviewmodel取得より前に呼び出すこと。 */
  fun initialize(context: Context) {
    if (dependencies != null) return
    val database = JournalDatabase.getInstance(context)
    val repository = RoomJournalEntryRepository(database.journalEntryDao())
    dependencies = Dependencies(repository, repository)
  }

  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    val dependencies = checkNotNull(dependencies) { "JournalHistoryViewModelFactory.initialize(context) was not called" }
    @Suppress("UNCHECKED_CAST")
    return JournalHistoryViewModel(dependencies.reader, dependencies.deleter) as T
  }

  private class Dependencies(
    val reader: JournalEntryReader,
    val deleter: JournalEntryDeleter,
  )
}
