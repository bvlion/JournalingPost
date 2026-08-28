package info.bvlion.journalingpost.widget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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

        MoodEntrySheet(
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoodEntrySheet(
  mood: Mood,
  uiState: MainViewModel.UiState,
  onRecord: (String) -> Unit,
  onClose: () -> Unit,
) {
  val note = rememberSaveable { mutableStateOf("") }
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val isRecording = uiState == MainViewModel.UiState.LOADING

  LaunchedEffect(uiState) {
    // Webhook配送失敗を記録自体の失敗として扱わず、SUCCESSと同様に画面を閉じる。
    if (uiState == MainViewModel.UiState.SUCCESS || uiState == MainViewModel.UiState.SUCCESS_DELIVERY_FAILED) {
      sheetState.hide()
      onClose()
    }
  }

  ModalBottomSheet(
    // dismissするとActivityごとfinishしてViewModelのcoroutineを破棄するため、記録処理中は
    // swipe / scrim tap / Backのどれでも閉じられないようにする。sheetGesturesEnabled /
    // shouldDismissOnBackPressはswipeとBackしか止めないため、scrim tapが呼ぶ
    // onDismissRequest自体もisRecording中はonCloseを呼ばないよう明示的に無視する。
    onDismissRequest = { if (!isRecording) onClose() },
    sheetState = sheetState,
    sheetGesturesEnabled = !isRecording,
    properties = ModalBottomSheetProperties(shouldDismissOnBackPress = !isRecording),
  ) {
    MoodEntrySheetContent(
      mood = mood,
      uiState = uiState,
      note = note.value,
      onNoteChange = { note.value = it },
      onRecord = { onRecord(note.value) },
    )
  }
}

@Composable
private fun MoodEntrySheetContent(
  mood: Mood,
  uiState: MainViewModel.UiState,
  note: String,
  onNoteChange: (String) -> Unit,
  onRecord: () -> Unit,
) {
  val isRecording = uiState == MainViewModel.UiState.LOADING

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 24.dp)
      .padding(bottom = 24.dp)
      .imePadding(),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(text = mood.emoji, style = MaterialTheme.typography.headlineSmall)
      Spacer(Modifier.width(8.dp))
      Text(text = stringResource(mood.labelRes), style = MaterialTheme.typography.titleMedium)
    }

    // 文章を書かなくても記録が成立することを崩さないため、初期表示ではfocusを要求せず
    // ソフトキーボードも出さない。入力欄をタップしたときだけ通常どおり表示させる。
    TextField(
      value = note,
      onValueChange = onNoteChange,
      modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
      label = { Text("メモ（任意）") },
      enabled = !isRecording,
    )

    if (uiState == MainViewModel.UiState.FAILURE) {
      Text(
        text = "記録に失敗しました。もう一度お試しください",
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 8.dp),
      )
    }

    Row(
      modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
      horizontalArrangement = Arrangement.End,
    ) {
      Button(onClick = onRecord, enabled = !isRecording) {
        if (isRecording) {
          CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        } else {
          Text("記録")
        }
      }
    }
  }
}

@Preview(showBackground = true)
@Composable
fun MoodEntrySheetContentPreview() {
  JournalingPostTheme {
    MoodEntrySheetContent(
      mood = Mood.HAPPY,
      uiState = MainViewModel.UiState.INIT,
      note = "",
      onNoteChange = {},
      onRecord = {},
    )
  }
}
