package info.bvlion.journalingpost

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import info.bvlion.journalingpost.settings.RecordModeRepository
import info.bvlion.journalingpost.settings.RecordModeRepositoryStore
import info.bvlion.journalingpost.webhook.WebhookSettingsRepository
import info.bvlion.journalingpost.webhook.WebhookSettingsRepositoryStore

/** RecordModeRepository/WebhookSettingsRepositoryはMainViewModelFactoryと同じsingletonを再利用する。 */
object SettingsViewModelFactory : ViewModelProvider.Factory {
  private lateinit var recordModeRepository: RecordModeRepository
  private lateinit var webhookSettingsRepository: WebhookSettingsRepository

  /** 各Activityのviewmodel取得より前に呼び出すこと。 */
  fun initialize(context: Context) {
    if (::recordModeRepository.isInitialized) return
    recordModeRepository = RecordModeRepositoryStore.getInstance(context)
    webhookSettingsRepository = WebhookSettingsRepositoryStore.getInstance(context)
  }

  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    check(::recordModeRepository.isInitialized) { "SettingsViewModelFactory.initialize(context) was not called" }
    @Suppress("UNCHECKED_CAST")
    return SettingsViewModel(recordModeRepository, webhookSettingsRepository) as T
  }
}
