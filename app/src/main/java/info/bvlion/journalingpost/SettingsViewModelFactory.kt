package info.bvlion.journalingpost

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import info.bvlion.journalingpost.settings.DataStoreRecordModeRepository
import info.bvlion.journalingpost.settings.RecordModeRepository
import info.bvlion.journalingpost.settings.RecordModeSettingsStore

/** RecordModeのDataStoreはMainViewModelFactoryと同じsingletonを再利用する。 */
object SettingsViewModelFactory : ViewModelProvider.Factory {
  private lateinit var recordModeRepository: RecordModeRepository

  /** 各Activityのviewmodel取得より前に呼び出すこと。 */
  fun initialize(context: Context) {
    if (::recordModeRepository.isInitialized) return
    recordModeRepository = DataStoreRecordModeRepository(RecordModeSettingsStore.getInstance(context))
  }

  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    check(::recordModeRepository.isInitialized) { "SettingsViewModelFactory.initialize(context) was not called" }
    @Suppress("UNCHECKED_CAST")
    return SettingsViewModel(recordModeRepository) as T
  }
}
