package info.bvlion.journalingpost.mood

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import info.bvlion.journalingpost.R
import info.bvlion.journalingpost.ui.theme.JournalingPostTheme

/**
 * Main画面の記録タブ。Moodを縦スクロールの一覧で表示し、タップすると[MoodRecordOverlay]で
 * Widgetと同じ記録ダイアログを開く。選択したMoodの保持やViewModel連携は呼び出し元が持つ。
 */
@Composable
fun MoodRecordScreen(
  moods: List<Mood>,
  onMoodClick: (Mood) -> Unit,
  modifier: Modifier = Modifier,
) {
  LazyColumn(
    modifier = modifier.fillMaxSize(),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    item {
      Text(
        text = stringResource(R.string.record_screen_heading),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(bottom = 8.dp),
      )
    }
    items(moods, key = { it.name }) { mood ->
      MoodRow(mood = mood, onClick = { onMoodClick(mood) })
    }
  }
}

@Composable
private fun MoodRow(
  mood: Mood,
  onClick: () -> Unit,
) {
  val description = stringResource(mood.descriptionRes)
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .semantics { contentDescription = description }
      .padding(horizontal = 8.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(text = mood.emoji, style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.width(16.dp))
    Text(text = stringResource(mood.labelRes), style = MaterialTheme.typography.bodyLarge)
  }
}

@Preview(showBackground = true)
@Composable
fun MoodRecordScreenPreview() {
  JournalingPostTheme {
    MoodRecordScreen(moods = moodCatalog, onMoodClick = {})
  }
}
