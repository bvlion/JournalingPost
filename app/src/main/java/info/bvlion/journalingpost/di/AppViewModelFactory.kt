package info.bvlion.journalingpost.di

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import info.bvlion.journalingpost.AnalysisHistoryViewModel
import info.bvlion.journalingpost.JournalHistoryViewModel
import info.bvlion.journalingpost.JournalingPostApplication
import info.bvlion.journalingpost.MainViewModel
import info.bvlion.journalingpost.SettingsViewModel
import info.bvlion.journalingpost.WebhookSettingsViewModel

/** 全ViewModelの生成をここへまとめる。依存関係は[AppContainer]からのみ取り出す。 */
val appViewModelFactory: ViewModelProvider.Factory = viewModelFactory {
  initializer {
    MainViewModel(journalRecorder = container.journalRecorder)
  }
  initializer {
    JournalHistoryViewModel(
      reader = container.journalEntryReader,
      deleter = container.journalEntryDeleter,
    )
  }
  initializer {
    AnalysisHistoryViewModel(
      reader = container.analysisResultReader,
      analysisIntegrationRepository = container.analysisIntegrationRepository,
      periodJournalEntryReader = container.periodJournalEntryReader,
      periodAnalyzer = container.periodAnalyzer,
      analysisResultWriter = container.analysisResultWriter,
    )
  }
  initializer {
    SettingsViewModel(
      analysisIntegrationRepository = container.analysisIntegrationRepository,
      webhookSettingsRepository = container.webhookSettingsRepository,
    )
  }
  initializer {
    WebhookSettingsViewModel(
      webhookSettingsRepository = container.webhookSettingsRepository,
      analysisIntegrationRepository = container.analysisIntegrationRepository,
    )
  }
}

private val CreationExtras.container: AppContainer
  get() = (this[APPLICATION_KEY] as JournalingPostApplication).container
