package info.bvlion.journalingpost

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import info.bvlion.journalingpost.journal.JournalRecorder
import info.bvlion.journalingpost.journal.LocalWebhookJournalRecorder
import info.bvlion.journalingpost.journal.db.JournalDatabase
import info.bvlion.journalingpost.journal.db.RoomJournalEntryRepository
import info.bvlion.journalingpost.poster.WebhookJournalPoster
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

/** production依存の組み立てを行う。DI frameworkは導入せず、process内でHttpClient/JournalRecorderを1つだけ再利用する。 */
object MainViewModelFactory : ViewModelProvider.Factory {
  private val httpClient = HttpClient(CIO) {
    install(ContentNegotiation) {
      json()
    }
  }
  private val journalPoster = WebhookJournalPoster(httpClient)
  private lateinit var journalRecorder: JournalRecorder

  /** [Context]依存のRoom初期化を行う。各Activityのviewmodel取得より前に呼び出すこと。二重初期化は無視する。 */
  fun initialize(context: Context) {
    if (::journalRecorder.isInitialized) return
    val database = JournalDatabase.getInstance(context)
    val repository = RoomJournalEntryRepository(database.journalEntryDao())
    journalRecorder = LocalWebhookJournalRecorder(repository, journalPoster)
  }

  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    check(::journalRecorder.isInitialized) { "MainViewModelFactory.initialize(context) was not called" }
    @Suppress("UNCHECKED_CAST")
    return MainViewModel(journalRecorder = journalRecorder) as T
  }
}
