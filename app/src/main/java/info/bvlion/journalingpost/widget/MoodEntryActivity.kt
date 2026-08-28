package info.bvlion.journalingpost.widget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import info.bvlion.journalingpost.MainViewModel
import info.bvlion.journalingpost.MainViewModelFactory
import info.bvlion.journalingpost.journal.JournalSource
import info.bvlion.journalingpost.mood.Mood
import info.bvlion.journalingpost.mood.MoodSnapshot
import info.bvlion.journalingpost.ui.theme.JournalingPostTheme

/** 既存MainViewModel/JournalRecorderを再利用する。 */
class MoodEntryActivity : ComponentActivity() {
  private val viewModel: MainViewModel by viewModels { MainViewModelFactory }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    MainViewModelFactory.initialize(applicationContext)

    val mood = Mood.fromExtraValue(intent.getStringExtra(EXTRA_MOOD))
    if (mood == null) {
      finish()
      return
    }
    val moodSnapshot = MoodSnapshot(id = mood.name, emoji = mood.emoji, label = getString(mood.labelRes))

    setContent {
      JournalingPostTheme {
        val uiState by viewModel.uiState.collectAsState()

        MoodEntryDialog(
          mood = mood,
          uiState = uiState,
          onRecord = { note -> viewModel.record(note = note, mood = moodSnapshot, source = JournalSource.WIDGET) },
          onClose = { finish() },
        )
      }
    }
  }

  companion object {
    const val EXTRA_MOOD = "info.bvlion.journalingpost.extra.MOOD"
  }
}

@Composable
fun MoodEntryDialog(
  mood: Mood,
  uiState: MainViewModel.UiState,
  onRecord: (String) -> Unit,
  onClose: () -> Unit,
) {
  var note by rememberSaveable { mutableStateOf("") }
  var isNoteVisible by rememberSaveable { mutableStateOf(false) }
  val isRecording = uiState == MainViewModel.UiState.LOADING
  val hasFailure = uiState == MainViewModel.UiState.FAILURE

  LaunchedEffect(uiState) {
    // Webhook配送失敗を記録自体の失敗として扱わず、SUCCESSと同様に画面を閉じる。
    if (uiState == MainViewModel.UiState.SUCCESS || uiState == MainViewModel.UiState.SUCCESS_DELIVERY_FAILED) {
      onClose()
    }
  }

  AlertDialog(
    // dismissするとActivityごとfinishしてViewModelのcoroutineを破棄するため、記録処理中は
    // DialogPropertiesに加えてonDismissRequest自体でもonCloseを呼ばないようにする。
    onDismissRequest = { if (!isRecording) onClose() },
    properties = DialogProperties(
      dismissOnBackPress = !isRecording,
      dismissOnClickOutside = !isRecording,
    ),
    title = {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = mood.emoji, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.width(8.dp))
        Text(text = stringResource(mood.labelRes), style = MaterialTheme.typography.titleMedium)
      }
    },
    text = if (isNoteVisible || hasFailure) {
      {
        Column {
          if (isNoteVisible) {
            val focusRequester = remember { FocusRequester() }
            // 「メモを追加」を選んだ直後だけfocusを移し、ソフトキーボードを表示させる。
            // 初期表示から入力欄を出さないのは、文章を書かなくても記録が成立することを
            // UI自体で表現するため。
            LaunchedEffect(Unit) {
              focusRequester.requestFocus()
            }
            TextField(
              value = note,
              onValueChange = { note = it },
              modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
              enabled = !isRecording,
              maxLines = 4,
            )
          }
          if (hasFailure) {
            Text(
              text = "記録に失敗しました。もう一度お試しください",
              color = MaterialTheme.colorScheme.error,
              style = MaterialTheme.typography.bodySmall,
              modifier = Modifier.padding(top = 8.dp),
            )
          }
        }
      }
    } else {
      null
    },
    dismissButton = if (isNoteVisible) {
      null
    } else {
      {
        TextButton(onClick = { isNoteVisible = true }, enabled = !isRecording) {
          Text("メモを追加")
        }
      }
    },
    confirmButton = {
      Button(onClick = { onRecord(note) }, enabled = !isRecording) {
        if (isRecording) {
          CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        } else {
          Text("記録")
        }
      }
    },
  )
}

@Preview(showBackground = true)
@Composable
fun MoodEntryDialogPreview() {
  JournalingPostTheme {
    MoodEntryDialog(
      mood = Mood.HAPPY,
      uiState = MainViewModel.UiState.INIT,
      onRecord = {},
      onClose = {},
    )
  }
}
