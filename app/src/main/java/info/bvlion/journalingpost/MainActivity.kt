package info.bvlion.journalingpost

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import info.bvlion.journalingpost.journal.JournalSource
import info.bvlion.journalingpost.journal.history.JournalHistoryScreen
import info.bvlion.journalingpost.settings.SettingsScreen
import info.bvlion.journalingpost.ui.theme.JournalingPostTheme
import info.bvlion.journalingpost.webhook.LegacyWebhookConfigProvider
import info.bvlion.journalingpost.webhook.WebhookSettingsMigrationCoordinator
import info.bvlion.journalingpost.webhook.WebhookSettingsRepositoryStore
import info.bvlion.journalingpost.widget.registerMoodWidgetPreviewOnce
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
  private val viewModel: MainViewModel by viewModels { MainViewModelFactory }
  private val historyViewModel: JournalHistoryViewModel by viewModels { JournalHistoryViewModelFactory }
  private val settingsViewModel: SettingsViewModel by viewModels { SettingsViewModelFactory }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    MainViewModelFactory.initialize(applicationContext)
    JournalHistoryViewModelFactory.initialize(applicationContext)
    SettingsViewModelFactory.initialize(applicationContext)
    enableEdgeToEdge()

    // Widget pickerのgenerated preview(Android 15+)登録。通常UI/入力フローには影響しない。
    lifecycleScope.launch { registerMoodWidgetPreviewOnce(applicationContext) }

    // debug buildの自分用Webhook設定を初回起動時のみCustom Webhookへ移行する。移行の完了自体は
    // WebhookJournalPoster側でも保証されるため、ここはWidget等より先にMainActivityが開かれた場合の
    // 早期実行(体感速度の改善)に過ぎない。両者は同じcoordinatorを通るため競合しても二重importしない。
    lifecycleScope.launch {
      WebhookSettingsMigrationCoordinator.ensureMigrated(
        repository = WebhookSettingsRepositoryStore.getInstance(applicationContext),
        legacyConfigProvider = LegacyWebhookConfigProvider::get,
      )
    }

    setContent {
      JournalingPostTheme {
        val snackbarHostState = remember { SnackbarHostState() }
        val uiState by viewModel.uiState.collectAsState()
        var screen by rememberSaveable { mutableStateOf(Screen.INPUT) }

        // INPUT表示中はActivityの標準Back動作(終了)を保つため、他画面表示中のみ有効化する。
        BackHandler(enabled = screen != Screen.INPUT) {
          screen = Screen.INPUT
        }

        Scaffold(
          modifier = Modifier.fillMaxSize().imePadding(),
          snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { innerPadding ->
          Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (screen) {
              Screen.INPUT -> Column(modifier = Modifier.fillMaxSize()) {
                Row(
                  modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                  horizontalArrangement = Arrangement.End,
                ) {
                  TextButton(
                    onClick = {
                      // 画面へ入り直すタイミングでのみ、前回の操作結果と編集フォームの展開状態を捨てる
                      // (画面内の再生成では呼ばれないため、回転で入力途中の値は失われない)。
                      settingsViewModel.onSettingsOpened()
                      screen = Screen.SETTINGS
                    },
                  ) {
                    Text("設定")
                  }
                  TextButton(
                    onClick = {
                      historyViewModel.onHistoryOpened()
                      screen = Screen.HISTORY
                    },
                  ) {
                    Text("履歴を見る")
                  }
                }
                Column(
                  modifier = Modifier.weight(1f).fillMaxWidth(),
                  verticalArrangement = Arrangement.Bottom,
                ) {
                  InputView(uiState) {
                    viewModel.record(note = it, source = JournalSource.APP)
                  }
                }
              }

              Screen.HISTORY -> {
                val historyUiState by historyViewModel.uiState.collectAsState()
                val deleteFailed by historyViewModel.deleteFailed.collectAsState()
                JournalHistoryScreen(
                  uiState = historyUiState,
                  deleteFailed = deleteFailed,
                  onDelete = historyViewModel::deleteEntry,
                  onBack = { screen = Screen.INPUT },
                )
              }

              Screen.SETTINGS -> {
                val analysisIntegration by settingsViewModel.analysisIntegration.collectAsState()
                val integrationSaveFailed by settingsViewModel.integrationSaveFailed.collectAsState()
                val webhookOverview by settingsViewModel.webhookOverview.collectAsState()
                val isWebhookEditing by settingsViewModel.isWebhookEditing.collectAsState()
                val webhookFormState by settingsViewModel.webhookFormState.collectAsState()
                val webhookValidationErrors by settingsViewModel.webhookValidationErrors.collectAsState()
                val webhookOperationFailure by settingsViewModel.webhookOperationFailure.collectAsState()
                SettingsScreen(
                  analysisIntegration = analysisIntegration,
                  integrationSaveFailed = integrationSaveFailed,
                  onAnalysisIntegrationChange = settingsViewModel::setAnalysisIntegration,
                  webhookOverview = webhookOverview,
                  isWebhookEditing = isWebhookEditing,
                  webhookFormState = webhookFormState,
                  webhookValidationErrors = webhookValidationErrors,
                  webhookOperationFailure = webhookOperationFailure,
                  onWebhookEditStart = settingsViewModel::startWebhookEdit,
                  onWebhookEditCancel = settingsViewModel::cancelWebhookEdit,
                  onWebhookUrlChange = settingsViewModel::updateWebhookUrl,
                  onWebhookHeaderAdd = settingsViewModel::addWebhookHeader,
                  onWebhookHeaderRemove = settingsViewModel::removeWebhookHeader,
                  onWebhookHeaderNameChange = settingsViewModel::updateWebhookHeaderName,
                  onWebhookHeaderValueChange = settingsViewModel::updateWebhookHeaderValue,
                  onWebhookBodyTemplateChange = settingsViewModel::updateWebhookBodyTemplate,
                  onWebhookSave = settingsViewModel::saveWebhookSettings,
                  onWebhookDelete = settingsViewModel::deleteWebhookSettings,
                  onBack = { screen = Screen.INPUT },
                )
              }
            }

            LoadingOverlay(uiState == MainViewModel.UiState.LOADING)

            LaunchedEffect(uiState) {
              when (uiState) {
                MainViewModel.UiState.INIT -> Unit
                MainViewModel.UiState.LOADING -> Unit

                MainViewModel.UiState.SUCCESS -> {
                  snackbarHostState.showSnackbar(
                    message = "登録に成功しました",
                    actionLabel = "閉じる",
                    duration = SnackbarDuration.Short,
                  )
                  viewModel.resetState()
                }

                MainViewModel.UiState.SUCCESS_DELIVERY_FAILED -> {
                  snackbarHostState.showSnackbar(
                    message = "記録は保存しましたが、Webhookの送信に失敗しました",
                    actionLabel = "閉じる",
                    duration = SnackbarDuration.Long,
                  )
                  viewModel.resetState()
                }

                MainViewModel.UiState.FAILURE -> {
                  snackbarHostState.showSnackbar(
                    message = "登録に失敗しました",
                    actionLabel = "閉じる",
                    duration = SnackbarDuration.Long,
                  )
                  viewModel.resetState()
                }
              }
            }
          }
        }
      }
    }
  }

  private enum class Screen {
    INPUT,
    HISTORY,
    SETTINGS,
  }
}

@Composable
fun InputView(
  uiState: MainViewModel.UiState,
  postMessage: (String) -> Unit,
) {
  val text = rememberSaveable { mutableStateOf("") }
  val focusRequester = remember { FocusRequester() }

  LaunchedEffect(Unit) {
    focusRequester.requestFocus()
  }
  LaunchedEffect(uiState) {
    if (uiState == MainViewModel.UiState.SUCCESS || uiState == MainViewModel.UiState.SUCCESS_DELIVERY_FAILED) {
      text.value = ""
    }
  }

  Column(modifier = Modifier.padding(16.dp)) {
    Text(
      text = "考えてること",
      style = MaterialTheme.typography.titleMedium,
    )
    TextField(
      modifier = Modifier.padding(0.dp, 16.dp).fillMaxWidth().focusRequester(focusRequester),
      value = text.value,
      onValueChange = { text.value = it },
    )
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.End
    ) {
      Button(
        onClick = {
          postMessage(text.value)
        }
      ) {
        Text("登録")
      }
    }
  }
}

@Composable
fun LoadingOverlay(isLoading: Boolean) {
  if (isLoading) {
    Box(
      modifier = Modifier.fillMaxSize()
        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)),
      contentAlignment = Alignment.Center
    ) {
      CircularProgressIndicator()
    }
  }
}

@Preview(showBackground = true)
@Composable
fun InputViewPreview() {
  JournalingPostTheme {
    InputView(MainViewModel.UiState.LOADING) {}
  }
}