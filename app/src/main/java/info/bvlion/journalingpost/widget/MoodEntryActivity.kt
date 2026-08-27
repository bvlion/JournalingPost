package info.bvlion.journalingpost.widget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

        LaunchedEffect(uiState) {
          if (uiState == MainViewModel.UiState.SUCCESS) {
            finish()
          }
        }

        MoodEntryScreen(
          mood = mood,
          uiState = uiState,
          onRecord = { note -> viewModel.record(note = note, mood = moodSnapshot, source = JournalSource.WIDGET) },
          onRecordMoodOnly = { viewModel.record(note = "", mood = moodSnapshot, source = JournalSource.WIDGET) },
        )
      }
    }
  }

  companion object {
    const val EXTRA_MOOD = "info.bvlion.journalingpost.extra.MOOD"
  }
}

@Composable
fun MoodEntryScreen(
  mood: Mood,
  uiState: MainViewModel.UiState,
  onRecord: (String) -> Unit,
  onRecordMoodOnly: () -> Unit,
) {
  val note = rememberSaveable { mutableStateOf("") }
  val isLoading = uiState == MainViewModel.UiState.LOADING

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(Color.Black.copy(alpha = 0.4f))
      .padding(24.dp),
    contentAlignment = Alignment.Center,
  ) {
    Card(modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.padding(24.dp)) {
        Text(text = mood.emoji, style = MaterialTheme.typography.displayLarge)

        Spacer(Modifier.height(16.dp))

        Text(text = "何か残しますか？", style = MaterialTheme.typography.titleMedium)
        TextField(
          value = note.value,
          onValueChange = { note.value = it },
          modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
          enabled = !isLoading,
        )

        if (uiState == MainViewModel.UiState.FAILURE) {
          Text(
            text = "送信に失敗しました。もう一度お試しください",
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 8.dp),
          )
        }

        Row(
          modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
          horizontalArrangement = Arrangement.End,
        ) {
          TextButton(onClick = onRecordMoodOnly, enabled = !isLoading) {
            Text("気分だけ記録")
          }
          Spacer(Modifier.width(8.dp))
          Button(onClick = { onRecord(note.value) }, enabled = !isLoading) {
            if (isLoading) {
              CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
              Text("記録")
            }
          }
        }
      }
    }
  }
}

@Preview(showBackground = true)
@Composable
fun MoodEntryScreenPreview() {
  JournalingPostTheme {
    MoodEntryScreen(
      mood = Mood.HAPPY,
      uiState = MainViewModel.UiState.INIT,
      onRecord = {},
      onRecordMoodOnly = {},
    )
  }
}
