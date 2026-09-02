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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import info.bvlion.journalingpost.analysis.AnalysisHistoryScreen
import info.bvlion.journalingpost.di.appViewModelFactory
import info.bvlion.journalingpost.journal.JournalSource
import info.bvlion.journalingpost.journal.history.JournalHistoryScreen
import info.bvlion.journalingpost.mood.MoodRecordOverlay
import info.bvlion.journalingpost.mood.MoodRecordScreen
import info.bvlion.journalingpost.mood.MoodSettingsScreen
import info.bvlion.journalingpost.mood.MoodSnapshot
import info.bvlion.journalingpost.settings.HostedConsentDialog
import info.bvlion.journalingpost.settings.SettingsScreen
import info.bvlion.journalingpost.settings.WebhookSettingsScreen
import info.bvlion.journalingpost.ui.EventEffect
import info.bvlion.journalingpost.ui.theme.JournalingPostTheme
import info.bvlion.journalingpost.widget.registerMoodWidgetPreviewOnce
import java.util.Locale
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
  private val viewModel: MainViewModel by viewModels { appViewModelFactory }
  private val historyViewModel: JournalHistoryViewModel by viewModels { appViewModelFactory }
  private val analysisHistoryViewModel: AnalysisHistoryViewModel by viewModels { appViewModelFactory }
  private val settingsViewModel: SettingsViewModel by viewModels { appViewModelFactory }
  private val autoAnalysisSettingsViewModel: AutoAnalysisSettingsViewModel by viewModels { appViewModelFactory }
  private val moodViewModel: MoodViewModel by viewModels { appViewModelFactory }
  private val noteOnlyEntryViewModel: NoteOnlyEntryViewModel by viewModels { appViewModelFactory }
  private val moodSettingsViewModel: MoodSettingsViewModel by viewModels { appViewModelFactory }
  private val webhookSettingsViewModel: WebhookSettingsViewModel by viewModels { appViewModelFactory }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    // Widget pickerのgenerated preview(Android 15+)登録。通常UI/入力フローには影響しない。
    lifecycleScope.launch { registerMoodWidgetPreviewOnce(applicationContext) }

    // 自動解析(#59)の予約を現在の設定へ合わせる。既存の予約は時刻を計算し直さない。
    lifecycleScope.launch {
      (application as JournalingPostApplication).container.autoAnalysisScheduler.syncFromSettings()
    }

    setContent {
      JournalingPostTheme {
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()
        val moods by moodViewModel.moods.collectAsStateWithLifecycle()
        val isNoteOnlyEntryEnabled by noteOnlyEntryViewModel.isEnabled.collectAsStateWithLifecycle()

        var destination by rememberSaveable { mutableStateOf(MainDestination.RECORD) }
        var showWebhookSettings by rememberSaveable { mutableStateOf(false) }
        var showMoodSettings by rememberSaveable { mutableStateOf(false) }
        var moodSettingsScreenSessionId by rememberSaveable { mutableIntStateOf(0) }
        // Settingsで保存済み設定が無いままCustom Webhookを選んで来た場合、Webhook設定画面で既存設定が
        // 見つかればその場で有効化する。利用者が自分で設定項目を開いた場合は有効化しない。
        var webhookSetupPending by rememberSaveable { mutableStateOf(false) }
        var showHostedConsent by rememberSaveable { mutableStateOf(false) }
        var selectedMoodId by rememberSaveable { mutableStateOf<String?>(null) }
        // 「メモだけ記録」はMoodを持たないため、Mood選択とは別の状態として扱う。
        var isNoteOnlyRecording by rememberSaveable { mutableStateOf(false) }
        val selectedMood = moods?.firstOrNull { it.id == selectedMoodId }
        val isRecordOverlayVisible = selectedMood != null || isNoteOnlyRecording

        val snackbarHostState = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val density = LocalDensity.current
        // 下部ナビはScaffoldの外にあるSnackbarHostへpaddingを渡せないため、実測した高さ(システムの
        // navigation bar inset込み)ぶんSnackbarを持ち上げる。Material3のNavigationBar高さ定数は
        // 非公開なので値を複製しない。
        var navigationBarHeight by remember { mutableStateOf(0.dp) }
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

        val openWebhookSettings: (Boolean) -> Unit = { activatePendingSelection ->
          webhookSetupPending = activatePendingSelection
          webhookSettingsViewModel.onScreenOpened(activatePendingSelection)
          showWebhookSettings = true
        }
        val closeWebhookSettings: () -> Unit = {
          webhookSettingsViewModel.onScreenClosed()
          settingsViewModel.onWebhookSettingsClosed()
          webhookSetupPending = false
          showWebhookSettings = false
        }
        val closeRecordOverlay: () -> Unit = {
          selectedMoodId = null
          isNoteOnlyRecording = false
          viewModel.resetState()
        }
        val closeMoodSettings: () -> Unit = { showMoodSettings = false }
        val openMoodSettings: () -> Unit = {
          moodSettingsScreenSessionId++
          showMoodSettings = true
        }

        LaunchedEffect(destination) {
          if (destination == MainDestination.SETTINGS) {
            showHostedConsent = false
            settingsViewModel.onSettingsOpened()
          }
        }

        BackHandler(
          enabled = selectedMoodId != null || isNoteOnlyRecording || showWebhookSettings || showMoodSettings ||
            destination != MainDestination.RECORD,
        ) {
          when {
            selectedMoodId != null || isNoteOnlyRecording -> if (!recordInProgress) closeRecordOverlay()
            // Webhook設定はSettingsの下位画面のため、Backは1段階だけ戻す。
            showWebhookSettings -> closeWebhookSettings()
            showMoodSettings -> closeMoodSettings()
            else -> destination = MainDestination.RECORD
          }
        }

        Box(modifier = Modifier.fillMaxSize()) {
          Scaffold(
            modifier = Modifier.fillMaxSize().imePadding(),
            bottomBar = {
              if (!showWebhookSettings && !showMoodSettings) {
                NavigationBar(
                  modifier = Modifier.onGloballyPositioned {
                    navigationBarHeight = with(density) { it.size.height.toDp() }
                  },
                ) {
                  MainDestination.entries.forEach { item ->
                    NavigationBarItem(
                      selected = destination == item,
                      onClick = {
                        if (item != destination) {
                          selectedMoodId = null
                          isNoteOnlyRecording = false
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
              if (showMoodSettings) {
                LaunchedEffect(Unit) { moodSettingsViewModel.onScreenOpened(moodSettingsScreenSessionId) }
                val moodSettingsUiState by moodSettingsViewModel.uiState.collectAsStateWithLifecycle()
                val savedMessage = stringResource(R.string.mood_settings_save_succeeded)
                val saveFailedMessage = stringResource(R.string.mood_settings_save_failed)

                EventEffect(moodSettingsViewModel.events) { event ->
                  when (event) {
                    MoodSettingsEvent.Saved -> showMessage(savedMessage)
                    MoodSettingsEvent.SaveFailed -> showMessage(saveFailedMessage)
                  }
                }

                MoodSettingsScreen(
                  uiState = moodSettingsUiState,
                  onEmojiChange = moodSettingsViewModel::updateEmoji,
                  onLabelChange = moodSettingsViewModel::updateLabel,
                  onMoveUp = moodSettingsViewModel::moveUp,
                  onMoveDown = moodSettingsViewModel::moveDown,
                  onAdd = moodSettingsViewModel::addMood,
                  onRemove = moodSettingsViewModel::removeMood,
                  onSave = moodSettingsViewModel::save,
                  onBack = closeMoodSettings,
                )
              } else if (showWebhookSettings) {
                // process recreationでこの画面がそのまま復元された場合に備えたフォールバック。
                LaunchedEffect(Unit) {
                  webhookSettingsViewModel.ensureScreenOpened(webhookSetupPending)
                }
                val webhookSettingsUiState by webhookSettingsViewModel.uiState.collectAsStateWithLifecycle()
                WebhookSettingsScreen(
                  uiState = webhookSettingsUiState,
                  saveResults = webhookSettingsViewModel.saveResults,
                  onShowMessage = showMessage,
                  onUrlChange = webhookSettingsViewModel::updateUrl,
                  onHeaderAdd = webhookSettingsViewModel::addHeader,
                  onHeaderRemove = webhookSettingsViewModel::removeHeader,
                  onHeaderNameChange = webhookSettingsViewModel::updateHeaderName,
                  onHeaderValueChange = webhookSettingsViewModel::updateHeaderValue,
                  onBodyTemplateChange = webhookSettingsViewModel::updateBodyTemplate,
                  onBodyTemplateReset = webhookSettingsViewModel::resetBodyTemplate,
                  onSave = webhookSettingsViewModel::save,
                  onBack = closeWebhookSettings,
                )
              } else {
                when (destination) {
                  // Mood一覧と「メモだけ記録」の表示設定が揃うまでは、記録の選択肢を欠けたまま出さない。
                  MainDestination.RECORD -> if (moods == null || isNoteOnlyEntryEnabled == null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                      CircularProgressIndicator()
                    }
                  } else {
                    MoodRecordScreen(
                      moods = requireNotNull(moods),
                      isNoteOnlyEntryVisible = isNoteOnlyEntryEnabled == true,
                      onMoodClick = { mood ->
                        viewModel.resetState()
                        selectedMoodId = mood.id
                      },
                      onNoteOnlyClick = {
                        viewModel.resetState()
                        isNoteOnlyRecording = true
                      },
                    )
                  }

                  MainDestination.JOURNAL_HISTORY -> {
                    val historyUiState by historyViewModel.uiState.collectAsStateWithLifecycle()
                    JournalHistoryScreen(
                      uiState = historyUiState,
                      deleteFailures = historyViewModel.deleteFailures,
                      onShowMessage = showMessage,
                      onDelete = historyViewModel::deleteEntry,
                      onPreviousDay = historyViewModel::showPreviousDay,
                      onNextDay = historyViewModel::showNextDay,
                      onToday = historyViewModel::showToday,
                      onSelectDate = historyViewModel::selectDate,
                    )
                  }

                  MainDestination.ANALYSIS_HISTORY -> {
                    val analysisHistoryUiState by analysisHistoryViewModel.uiState.collectAsStateWithLifecycle()
                    val canRunAnalysis by analysisHistoryViewModel.canRunAnalysis.collectAsStateWithLifecycle()
                    val isAnalysisRunning by analysisHistoryViewModel.isAnalysisRunning.collectAsStateWithLifecycle()
                    val selectableDays by analysisHistoryViewModel.selectableDays.collectAsStateWithLifecycle()
                    AnalysisHistoryScreen(
                      uiState = analysisHistoryUiState,
                      canRunAnalysis = canRunAnalysis,
                      isRunning = isAnalysisRunning,
                      selectableDays = selectableDays,
                      runResults = analysisHistoryViewModel.runResults,
                      onShowMessage = showMessage,
                      onAnalyze = analysisHistoryViewModel::analyze,
                    )
                  }

                  MainDestination.SETTINGS -> {
                    val settingsUiState by settingsViewModel.uiState.collectAsStateWithLifecycle()
                    val autoAnalysisUiState by autoAnalysisSettingsViewModel.uiState.collectAsStateWithLifecycle()
                    val integrationSaveFailedMessage =
                      stringResource(R.string.settings_integration_save_failed)
                    val autoAnalysisSaveFailedMessage =
                      stringResource(R.string.settings_auto_analysis_save_failed)
                    val noteOnlySaveFailedMessage =
                      stringResource(R.string.settings_note_only_save_failed)
                    val debugFixturesSeededTemplate =
                      stringResource(R.string.settings_debug_fixtures_seeded)
                    val debugFixturesAlreadySeededMessage =
                      stringResource(R.string.settings_debug_fixtures_already_seeded)
                    val debugFixturesSeedFailedMessage =
                      stringResource(R.string.settings_debug_fixtures_seed_failed)

                    // Snackbar表示と下位画面への遷移はこの画面の外側が持つため、Settingsの
                    // 一時的な結果はここで受け取る。
                    EventEffect(autoAnalysisSettingsViewModel.events) { event ->
                      when (event) {
                        AutoAnalysisSettingsEvent.SaveFailed -> showMessage(autoAnalysisSaveFailedMessage)
                      }
                    }
                    EventEffect(settingsViewModel.events) { event ->
                      when (event) {
                        SettingsEvent.IntegrationSaveFailed -> showMessage(integrationSaveFailedMessage)
                        SettingsEvent.NoteOnlyEntrySaveFailed -> showMessage(noteOnlySaveFailedMessage)
                        SettingsEvent.WebhookSetupRequested -> openWebhookSettings(true)
                        SettingsEvent.HostedConsentRequested -> showHostedConsent = true
                        is SettingsEvent.DebugFixturesSeeded -> showMessage(
                          String.format(
                            Locale.getDefault(),
                            debugFixturesSeededTemplate,
                            event.entryCount,
                            event.analysisResultCount,
                          ),
                        )
                        SettingsEvent.DebugFixturesAlreadySeeded ->
                          showMessage(debugFixturesAlreadySeededMessage)
                        SettingsEvent.DebugFixturesSeedFailed ->
                          showMessage(debugFixturesSeedFailedMessage)
                      }
                    }

                    SettingsScreen(
                      uiState = settingsUiState,
                      autoAnalysisUiState = autoAnalysisUiState,
                      onAnalysisIntegrationChange = settingsViewModel::setAnalysisIntegration,
                      onNoteOnlyEntryChange = settingsViewModel::setNoteOnlyEntryEnabled,
                      onAutoAnalysisEnabledChange = autoAnalysisSettingsViewModel::setEnabled,
                      onAutoAnalysisScheduleChange = autoAnalysisSettingsViewModel::setSchedule,
                      onMoodSettingsOpen = openMoodSettings,
                      onWebhookSettingsOpen = { openWebhookSettings(false) },
                      onSeedDebugFixtures = if (BuildConfig.DEBUG) settingsViewModel::seedDebugFixtures else null,
                    )

                    if (showHostedConsent) {
                      HostedConsentDialog(
                        onConfirm = {
                          settingsViewModel.confirmHostedIntegration()
                          showHostedConsent = false
                        },
                        onDismiss = {
                          settingsViewModel.dismissHostedConsent()
                          showHostedConsent = false
                        },
                      )
                    }
                  }
                }
              }
            }
          }

          if (isRecordOverlayVisible) {
            val successMessage = stringResource(R.string.record_success)
            val failureMessage = stringResource(R.string.record_failure)
            val recordingMood = if (isNoteOnlyRecording) null else selectedMood
            MoodRecordOverlay(
              mood = recordingMood,
              isInteractionLocked = recordInProgress,
              // アプリ内では失敗もSnackbarで伝えるため、ダイアログ内のinline表示は使わない
              // (dialogは開いたままで、入力内容を保持して再試行できる)。
              hasFailure = false,
              onRecord = { note ->
                viewModel.record(
                  note = note,
                  mood = recordingMood?.let { MoodSnapshot(id = it.id, emoji = it.emoji, label = it.label) },
                  source = JournalSource.APP,
                )
              },
              onDismiss = closeRecordOverlay,
            )

            LaunchedEffect(uiState) {
              when (uiState) {
                MainViewModel.UiState.SUCCESS -> {
                  closeRecordOverlay()
                  showMessage(successMessage)
                }

                MainViewModel.UiState.FAILURE -> {
                  showMessage(failureMessage)
                  viewModel.resetState()
                }

                else -> Unit
              }
            }
          }

          // Snackbarは記録ダイアログより手前へ描く。通常時は下部ナビの上へ、
          // ダイアログ表示中や下位設定画面ではナビが無い/隠れているので底へ寄せる。
          SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
              .align(Alignment.BottomCenter)
              .imePadding()
              .then(
                if (showWebhookSettings || showMoodSettings || isRecordOverlayVisible) {
                  Modifier.navigationBarsPadding()
                } else {
                  Modifier.padding(bottom = navigationBarHeight)
                },
              ),
          )
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
