package info.bvlion.journalingpost

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import info.bvlion.journalingpost.analysis.AnalysisResultReader
import info.bvlion.journalingpost.analysis.db.RoomAnalysisResultRepository
import info.bvlion.journalingpost.journal.db.JournalDatabase

/** JournalDatabaseはMainViewModelFactoryと同じsingletonを再利用する。 */
object AnalysisHistoryViewModelFactory : ViewModelProvider.Factory {
  @Volatile
  private var reader: AnalysisResultReader? = null

  /** 各Activityのviewmodel取得より前に呼び出すこと。 */
  fun initialize(context: Context) {
    if (reader != null) return
    val database = JournalDatabase.getInstance(context)
    reader = RoomAnalysisResultRepository(database.analysisResultDao())
  }

  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    val reader = checkNotNull(reader) { "AnalysisHistoryViewModelFactory.initialize(context) was not called" }
    @Suppress("UNCHECKED_CAST")
    return AnalysisHistoryViewModel(reader) as T
  }
}
