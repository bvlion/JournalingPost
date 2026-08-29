package info.bvlion.journalingpost

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import info.bvlion.journalingpost.settings.AnalysisIntegrationRepository
import info.bvlion.journalingpost.settings.AnalysisIntegrationRepositoryStore
import info.bvlion.journalingpost.webhook.WebhookSettingsRepository
import info.bvlion.journalingpost.webhook.WebhookSettingsRepositoryStore

/** AnalysisIntegrationRepository/WebhookSettingsRepositoryはMainViewModelFactoryと同じsingletonを再利用する。 */
object SettingsViewModelFactory : ViewModelProvider.Factory {
  @Volatile
  private var dependencies: Dependencies? = null

  /** 各Activityのviewmodel取得より前に呼び出すこと。 */
  fun initialize(context: Context) {
    if (dependencies != null) return
    // 両方取得できてから1つの参照としてまとめて確定させる。片方ずつfieldへ代入すると、後続の取得が
    // 例外になった際に部分初期化状態がprocess内へ残り、次回initialize()も初期化済みとして早期returnして
    // create()が未初期化のdependencyを参照してしまう。
    val analysisIntegrationRepository = AnalysisIntegrationRepositoryStore.getInstance(context)
    val webhookSettingsRepository = WebhookSettingsRepositoryStore.getInstance(context)
    dependencies = Dependencies(analysisIntegrationRepository, webhookSettingsRepository)
  }

  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    val dependencies = checkNotNull(dependencies) { "SettingsViewModelFactory.initialize(context) was not called" }
    @Suppress("UNCHECKED_CAST")
    return SettingsViewModel(dependencies.analysisIntegrationRepository, dependencies.webhookSettingsRepository) as T
  }

  private class Dependencies(
    val analysisIntegrationRepository: AnalysisIntegrationRepository,
    val webhookSettingsRepository: WebhookSettingsRepository,
  )
}
