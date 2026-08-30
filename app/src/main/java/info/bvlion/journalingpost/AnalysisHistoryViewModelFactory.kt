package info.bvlion.journalingpost

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import info.bvlion.journalingpost.analysis.AnalysisResultReader
import info.bvlion.journalingpost.analysis.AnalysisResultWriter
import info.bvlion.journalingpost.analysis.PeriodAnalyzer
import info.bvlion.journalingpost.analysis.WebhookPeriodAnalyzer
import info.bvlion.journalingpost.analysis.db.RoomAnalysisResultRepository
import info.bvlion.journalingpost.journal.db.JournalDatabase
import info.bvlion.journalingpost.journal.db.RoomJournalEntryRepository
import info.bvlion.journalingpost.settings.AnalysisIntegrationRepository
import info.bvlion.journalingpost.settings.AnalysisIntegrationRepositoryStore
import info.bvlion.journalingpost.webhook.WebhookSettingsRepositoryStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

/**
 * JournalDatabase / 各RepositoryはMainViewModelFactory等と同じsingletonを再利用する。
 * 期間解析のHTTP通信はこのFactoryだけが行うため、HttpClientもここで1つだけ保持する。
 */
object AnalysisHistoryViewModelFactory : ViewModelProvider.Factory {
  private val httpClient = HttpClient(CIO) {
    install(ContentNegotiation) {
      json()
    }
    // 利用者が設定したendpoint自身のresponseで2xx判定するため、リダイレクトは追わない
    // (追うと、301/302の転送先が200を返した場合に成功として保存されてしまう)。
    followRedirects = false
  }

  @Volatile
  private var dependencies: Dependencies? = null

  /** 各Activityのviewmodel取得より前に呼び出すこと。 */
  fun initialize(context: Context) {
    if (dependencies != null) return
    val database = JournalDatabase.getInstance(context)
    val analysisRepository = RoomAnalysisResultRepository(database.analysisResultDao())
    val journalRepository = RoomJournalEntryRepository(database.journalEntryDao())
    val analysisIntegrationRepository = AnalysisIntegrationRepositoryStore.getInstance(context)
    val analyzer = WebhookPeriodAnalyzer(
      httpClient = httpClient,
      analysisIntegrationRepository = analysisIntegrationRepository,
      webhookSettingsRepository = WebhookSettingsRepositoryStore.getInstance(context),
      periodJournalEntryReader = journalRepository,
    )
    dependencies = Dependencies(
      reader = analysisRepository,
      writer = analysisRepository,
      analysisIntegrationRepository = analysisIntegrationRepository,
      analyzer = analyzer,
    )
  }

  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    val dependencies = checkNotNull(dependencies) { "AnalysisHistoryViewModelFactory.initialize(context) was not called" }
    @Suppress("UNCHECKED_CAST")
    return AnalysisHistoryViewModel(
      reader = dependencies.reader,
      analysisIntegrationRepository = dependencies.analysisIntegrationRepository,
      periodAnalyzer = dependencies.analyzer,
      analysisResultWriter = dependencies.writer,
    ) as T
  }

  private class Dependencies(
    val reader: AnalysisResultReader,
    val writer: AnalysisResultWriter,
    val analysisIntegrationRepository: AnalysisIntegrationRepository,
    val analyzer: PeriodAnalyzer,
  )
}
