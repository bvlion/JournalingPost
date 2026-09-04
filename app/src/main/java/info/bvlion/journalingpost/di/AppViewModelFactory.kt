package info.bvlion.journalingpost.di

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import info.bvlion.journalingpost.AnalysisHistoryViewModel
import info.bvlion.journalingpost.AutoAnalysisSettingsViewModel
import info.bvlion.journalingpost.JournalHistoryViewModel
import info.bvlion.journalingpost.JournalingPostApplication
import info.bvlion.journalingpost.MainViewModel
import info.bvlion.journalingpost.MoodNoteInputViewModel
import info.bvlion.journalingpost.MoodSettingsViewModel
import info.bvlion.journalingpost.MoodViewModel
import info.bvlion.journalingpost.NoteOnlyEntryViewModel
import info.bvlion.journalingpost.OnboardingViewModel
import info.bvlion.journalingpost.SettingsViewModel
import info.bvlion.journalingpost.WebhookSettingsViewModel

/** 全ViewModelの生成をここへまとめる。依存関係は[AppContainer]からのみ取り出す。 */
val appViewModelFactory: ViewModelProvider.Factory = viewModelFactory {
  initializer {
    MainViewModel(journalRecorder = container.journalRecorder)
  }
  initializer {
    MoodViewModel(repository = container.moodRepository)
  }
  initializer {
    MoodNoteInputViewModel(repository = container.moodNoteInputRepository)
  }
  initializer {
    NoteOnlyEntryViewModel(repository = container.noteOnlyEntryRepository)
  }
  initializer {
    OnboardingViewModel(
      welcomeRepository = container.welcomeRepository,
      firstRecordRepository = container.firstRecordRepository,
      analysisIntroductionRepository = container.analysisIntroductionRepository,
      analysisIntegrationRepository = container.analysisIntegrationRepository,
    )
  }
  initializer {
    MoodSettingsViewModel(
      repository = container.moodRepository,
      refreshWidgets = container::refreshMoodWidgets,
    )
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
      journalEntryReader = container.journalEntryReader,
      periodJournalEntryReader = container.periodJournalEntryReader,
      periodAnalyzer = container.periodAnalyzer,
      analysisResultWriter = container.analysisResultWriter,
    )
  }
  initializer {
    SettingsViewModel(
      analysisIntegrationRepository = container.analysisIntegrationRepository,
      webhookSettingsRepository = container.webhookSettingsRepository,
      noteOnlyEntryRepository = container.noteOnlyEntryRepository,
      moodNoteInputRepository = container.moodNoteInputRepository,
      hostedConsentRepository = container.hostedConsentRepository,
      refreshWidgets = container::refreshMoodWidgets,
      debugFixtureSeeder = container.debugFixtureSeeder,
    )
  }
  initializer {
    AutoAnalysisSettingsViewModel(
      repository = container.autoAnalysisSettingsRepository,
      onSettingsChanged = container.autoAnalysisScheduler::reschedule,
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
