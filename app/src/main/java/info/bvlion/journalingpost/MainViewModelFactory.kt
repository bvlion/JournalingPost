package info.bvlion.journalingpost

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import info.bvlion.journalingpost.poster.WebhookJournalPoster
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

/** production依存の組み立てを行う。DI frameworkは導入せずここへ閉じ込める。 */
class MainViewModelFactory : ViewModelProvider.Factory {
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    val httpClient = HttpClient(CIO) {
      install(ContentNegotiation) {
        json()
      }
    }

    @Suppress("UNCHECKED_CAST")
    return MainViewModel(journalPoster = WebhookJournalPoster(httpClient)) as T
  }
}
