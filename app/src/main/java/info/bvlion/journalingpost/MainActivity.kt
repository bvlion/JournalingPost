package info.bvlion.journalingpost

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import info.bvlion.journalingpost.analysis.AnalysisHistoryScreen
import info.bvlion.journalingpost.journal.JournalSource
import info.bvlion.journalingpost.journal.history.JournalHistoryScreen
import info.bvlion.journalingpost.mood.Mood
import info.bvlion.journalingpost.mood.MoodRecordOverlay
import info.bvlion.journalingpost.mood.MoodRecordScreen
import info.bvlion.journalingpost.mood.MoodSnapshot
import info.bvlion.journalingpost.mood.moodCatalog
import info.bvlion.journalingpost.settings.SettingsScreen
import info.bvlion.journalingpost.settings.WebhookSettingsScreen
import info.bvlion.journalingpost.ui.theme.JournalingPostTheme
import info.bvlion.journalingpost.widget.registerMoodWidgetPreviewOnce
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
  private val viewModel: MainViewModel by viewModels { MainViewModelFactory }
  private val historyViewModel: JournalHistoryViewModel by viewModels { JournalHistoryViewModelFactory }
  private val analysisHistoryViewModel: AnalysisHistoryViewModel by viewModels { AnalysisHistoryViewModelFactory }
  private val settingsViewModel: SettingsViewModel by viewModels { SettingsViewModelFactory }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    MainViewModelFactory.initialize(applicationContext)
    JournalHistoryViewModelFactory.initialize(applicationContext)
    AnalysisHistoryViewModelFactory.initialize(applicationContext)
    SettingsViewModelFactory.initialize(applicationContext)
    enableEdgeToEdge()

    // Widget pickerのgenerated preview(Android 15+)登録。通常UI/入力フローには影響しない。
    lifecycleScope.launch { registerMoodWidgetPreviewOnce(applicationContext) }

    setContent {
      JournalingPostTheme {
        val uiState by viewModel.uiState.collectAsState()

        var destination by rememberSaveable { mutableStateOf(MainDestination.RECORD) }
        var showWebhookSettings by rememberSaveable { mutableStateOf(false) }
        var selectedMood by rememberSaveable { mutableStateOf<Mood?>(null) }

        val snackbarHostState = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        // アプリ内画面の一時feedbackはSnackbarへ集約する。連続で出す場合は前のものを引き継がない。
        val showMessage: (String) -> Unit = { message ->
          scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
          }
        }

        // 記録処理中〜完了直後はダイアログの操作もタブ切り替えも受け付けない。MainViewModelは
        // INITへ戻さないため、SUCCESSもlock対象に含める。
        val recordInProgress = uiState == MainViewModel.UiState.LOADING ||
          uiState == MainViewModel.UiState.SUCCESS

        LaunchedEffect(destination) {
          if (destination == MainDestination.SETTINGS) settingsViewModel.onSettingsOpened()
        }

        BackHandler(
          enabled = selectedMood != null || showWebhookSettings || destination != MainDestination.RECORD,
        ) {
          when {
            selectedMood != null -> if (!recordInProgress) {
              selectedMood = null
              viewModel.resetState()
            }
            // Webhook設定はSettingsの下位画面のため、Backは1段階だけ戻す。
            showWebhookSettings -> {
              settingsViewModel.onWebhookSettingsScreenClosed()
              showWebhookSettings = false
            }
            else -> destination = MainDestination.RECORD
          }
        }

        Box(modifier = Modifier.fillMaxSize()) {
          Scaffold(
            modifier = Modifier.fillMaxSize().imePadding(),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
              if (!showWebhookSettings) {
                NavigationBar {
                  MainDestination.entries.forEach { item ->
                    NavigationBarItem(
                      selected = destination == item,
                      onClick = {
                        if (item != destination) {
                          selectedMood = null
                          destination = item
                        }
                      },
                      icon = { Icon(painterResource(item.icon), contentDescription = null) },
                      label = { Text(stringResource(item.labelRes)) },
                    )
                  }
                }
              }
            },
          ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
              if (showWebhookSettings) {
                // process recreationでこの画面がそのまま復元された場合に備えたフォールバック。
                LaunchedEffect(Unit) {
                  settingsViewModel.ensureWebhookSettingsScreenOpened()
                }
                val webhookSettingsLoadState by settingsViewModel.webhookSettingsLoadState.collectAsState()
                val webhookFormState by settingsViewModel.webhookFormState.collectAsState()
                val webhookValidationErrors by settingsViewModel.webhookValidationErrors.collectAsState()
                val webhookSaveResult by settingsViewModel.webhookSaveResult.collectAsState()
                WebhookSettingsScreen(
                  loadState = webhookSettingsLoadState,
                  formState = webhookFormState,
                  validationErrors = webhookValidationErrors,
                  saveResult = webhookSaveResult,
                  onShowMessage = showMessage,
                  onSaveResultShown = settingsViewModel::consumeWebhookSaveResult,
                  onUrlChange = settingsViewModel::updateWebhookUrl,
                  onHeaderAdd = settingsViewModel::addWebhookHeader,
                  onHeaderRemove = settingsViewModel::removeWebhookHeader,
                  onHeaderNameChange = settingsViewModel::updateWebhookHeaderName,
                  onHeaderValueChange = settingsViewModel::updateWebhookHeaderValue,
                  onBodyTemplateChange = settingsViewModel::updateWebhookBodyTemplate,
                  onBodyTemplateReset = settingsViewModel::resetWebhookBodyTemplate,
                  onSave = settingsViewModel::saveWebhookSettings,
                  onBack = {
                    settingsViewModel.onWebhookSettingsScreenClosed()
                    showWebhookSettings = false
                  },
                )
              } else {
                when (destination) {
                  MainDestination.RECORD -> MoodRecordScreen(
                    moods = moodCatalog,
                    onMoodClick = { mood ->
                      viewModel.resetState()
                      selectedMood = mood
                    },
                  )

                  MainDestination.JOURNAL_HISTORY -> {
                    val historyUiState by historyViewModel.uiState.collectAsState()
                    val deleteFailed by historyViewModel.deleteFailed.collectAsState()
                    JournalHistoryScreen(
                      uiState = historyUiState,
                      deleteFailed = deleteFailed,
                      onShowMessage = showMessage,
                      onDeleteFailedShown = historyViewModel::consumeDeleteFailed,
                      onDelete = historyViewModel::deleteEntry,
                    )
                  }

                  MainDestination.ANALYSIS_HISTORY -> {
                    val analysisHistoryUiState by analysisHistoryViewModel.uiState.collectAsState()
                    val canRunAnalysis by analysisHistoryViewModel.canRunAnalysis.collectAsState()
                    val analysisRunState by analysisHistoryViewModel.analysisRunState.collectAsState()
                    val candidateDay by analysisHistoryViewModel.candidateDay.collectAsState()
                    AnalysisHistoryScreen(
                      uiState = analysisHistoryUiState,
                      canRunAnalysis = canRunAnalysis,
                      runState = analysisRunState,
                      candidateDay = candidateDay,
                      onShowMessage = showMessage,
                      onRunResultShown = analysisHistoryViewModel::consumeRunResult,
                      onCandidateDayChange = analysisHistoryViewModel::checkCandidateDay,
                      onCandidateDayClear = analysisHistoryViewModel::clearCandidateDay,
                      onAnalyze = analysisHistoryViewModel::analyze,
                    )
                  }

                  MainDestination.SETTINGS -> {
                    val selectedIntegration by settingsViewModel.selectedAnalysisIntegration.collectAsState()
                    val integrationSaveFailed by settingsViewModel.integrationSaveFailed.collectAsState()
                    val webhookConfigured by settingsViewModel.webhookConfigured.collectAsState()
                    val webhookDestinationLabel by settingsViewModel.webhookDestinationLabel.collectAsState()
                    val webhookSetupRequested by settingsViewModel.webhookSetupRequested.collectAsState()

                    LaunchedEffect(webhookSetupRequested) {
                      if (webhookSetupRequested) {
                        settingsViewModel.consumeWebhookSetupRequest()
                        settingsViewModel.onWebhookSettingsScreenOpened()
                        showWebhookSettings = true
                      }
                    }

                    SettingsScreen(
                      selectedIntegration = selectedIntegration,
                      integrationSaveFailed = integrationSaveFailed,
                      onShowMessage = showMessage,
                      onIntegrationSaveFailedShown = settingsViewModel::consumeIntegrationSaveFailed,
                      onAnalysisIntegrationChange = settingsViewModel::setAnalysisIntegration,
                      webhookConfigured = webhookConfigured,
                      webhookDestinationLabel = webhookDestinationLabel,
                      onWebhookSettingsOpen = {
                        settingsViewModel.onWebhookSettingsScreenOpened()
                        showWebhookSettings = true
                      },
                    )
                  }
                }
              }
            }
          }

          selectedMood?.let { mood ->
            val moodLabel = stringResource(mood.labelRes)
            val successMessage = stringResource(R.string.record_success)
            MoodRecordOverlay(
              moodEmoji = mood.emoji,
              moodLabel = moodLabel,
              isInteractionLocked = recordInProgress,
              hasFailure = uiState == MainViewModel.UiState.FAILURE,
              onRecord = { note ->
                viewModel.record(
                  note = note,
                  mood = MoodSnapshot(id = mood.name, emoji = mood.emoji, label = moodLabel),
                  source = JournalSource.APP,
                )
              },
              onDismiss = {
                selectedMood = null
                viewModel.resetState()
              },
            )

            LaunchedEffect(uiState) {
              if (uiState == MainViewModel.UiState.SUCCESS) {
                selectedMood = null
                viewModel.resetState()
                showMessage(successMessage)
              }
            }
          }
        }
      }
    }
  }
}

enum class MainDestination(
  @param:StringRes val labelRes: Int,
  val icon: Int,
) {
  RECORD(R.string.tab_record, R.drawable.ic_nav_record),
  JOURNAL_HISTORY(R.string.tab_journal_history, R.drawable.ic_nav_journal_history),
  ANALYSIS_HISTORY(R.string.tab_analysis_history, R.drawable.ic_nav_analysis_history),
  SETTINGS(R.string.tab_settings, R.drawable.ic_nav_settings),
}
