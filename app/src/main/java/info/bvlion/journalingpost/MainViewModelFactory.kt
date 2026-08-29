package info.bvlion.journalingpost

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import info.bvlion.journalingpost.journal.IntegrationRoutingJournalRecorder
import info.bvlion.journalingpost.journal.JournalRecorder
import info.bvlion.journalingpost.journal.LocalOnlyJournalRecorder
import info.bvlion.journalingpost.journal.LocalWebhookJournalRecorder
import info.bvlion.journalingpost.journal.db.JournalDatabase
import info.bvlion.journalingpost.journal.db.RoomJournalEntryRepository
import info.bvlion.journalingpost.poster.WebhookJournalPoster
import info.bvlion.journalingpost.settings.AnalysisIntegrationRepositoryStore
import info.bvlion.journalingpost.webhook.WebhookSettingsRepositoryStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

/** DI frameworkは導入せず、process内でHttpClient/JournalRecorderを1つだけ再利用する。 */
object MainViewModelFactory : ViewModelProvider.Factory {
  private val httpClient = HttpClient(CIO) {
    install(ContentNegotiation) {
      json()
    }
  }
  private lateinit var journalRecorder: JournalRecorder

  /** 各Activityのviewmodel取得より前に呼び出すこと。 */
  fun initialize(context: Context) {
    if (::journalRecorder.isInitialized) return
    val database = JournalDatabase.getInstance(context)
    val repository = RoomJournalEntryRepository(database.journalEntryDao())
    val analysisIntegrationRepository = AnalysisIntegrationRepositoryStore.getInstance(context)
    val webhookSettingsRepository = WebhookSettingsRepositoryStore.getInstance(context)
    val journalPoster = WebhookJournalPoster(httpClient, webhookSettingsRepository)
    journalRecorder = IntegrationRoutingJournalRecorder(
      analysisIntegrationRepository = analysisIntegrationRepository,
      localOnlyRecorder = LocalOnlyJournalRecorder(repository),
      localWebhookRecorder = LocalWebhookJournalRecorder(repository, journalPoster),
    )
  }

  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    check(::journalRecorder.isInitialized) { "MainViewModelFactory.initialize(context) was not called" }
    @Suppress("UNCHECKED_CAST")
    return MainViewModel(journalRecorder = journalRecorder) as T
  }
}
