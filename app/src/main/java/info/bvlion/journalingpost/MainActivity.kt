package info.bvlion.journalingpost

import android.os.Bundle
import androidx.activity.ComponentActivity
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
import info.bvlion.journalingpost.ui.theme.JournalingPostTheme
import info.bvlion.journalingpost.widget.registerMoodWidgetPreviewOnce
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
  private val viewModel: MainViewModel by viewModels { MainViewModelFactory }
  private val historyViewModel: JournalHistoryViewModel by viewModels { JournalHistoryViewModelFactory }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    MainViewModelFactory.initialize(applicationContext)
    JournalHistoryViewModelFactory.initialize(applicationContext)
    enableEdgeToEdge()

    // Widget pickerのgenerated preview(Android 15+)登録。通常UI/入力フローには影響しない。
    lifecycleScope.launch { registerMoodWidgetPreviewOnce(applicationContext) }

    setContent {
      JournalingPostTheme {
        val snackbarHostState = remember { SnackbarHostState() }
        val uiState by viewModel.uiState.collectAsState()
        var screen by rememberSaveable { mutableStateOf(Screen.INPUT) }

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
                  TextButton(onClick = { screen = Screen.HISTORY }) {
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
                val historyGroups by historyViewModel.historyGroups.collectAsState()
                JournalHistoryScreen(
                  groups = historyGroups,
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
    if (uiState == MainViewModel.UiState.SUCCESS) {
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