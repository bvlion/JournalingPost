package info.bvlion.journalingpost.mood

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
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
import info.bvlion.journalingpost.ui.TopLevelScreen
import info.bvlion.journalingpost.ui.highlightedSection
import info.bvlion.journalingpost.ui.theme.JournalingPostTheme
import info.bvlion.journalingpost.ui.topLevelListContentPadding

/**
 * Main画面の記録タブ。Moodを縦スクロールの一覧で表示し、タップすると[MoodRecordOverlay]で
 * Widgetと同じ記録ダイアログを開く。選択したMoodの保持やViewModel連携は呼び出し元が持つ。
 *
 * 下部NavigationBarが現在地(記録)を示すため画面名の固定見出しは持たず、Mood選択のコンテンツから
 * 直接始める。コンテンツはedge-to-edgeでstatus barの下まで流れ、上端は[TopLevelScreen]が保護する。
 *
 * @param isNoteOnlyEntryVisible 設定で有効にしている場合だけ、Mood一覧の末尾へ「メモだけ記録」を出す。
 * @param highlightMoodSelection ウェルカムダイアログを閉じてから最初の記録が完了するまでの間だけtrue(#67)。
 * 気分を選ぶ場所そのものを枠線・背景色で一時的に強調する。
 */
@Composable
fun MoodRecordScreen(
  moods: List<Mood>,
  isNoteOnlyEntryVisible: Boolean,
  highlightMoodSelection: Boolean,
  onMoodClick: (Mood) -> Unit,
  onNoteOnlyClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  TopLevelScreen(modifier = modifier) {
    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      contentPadding = topLevelListContentPadding(),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      // 強調の枠線・背景を一覧全体へ1つの領域として描くため、LazyColumnのitemsではなく
      // 1つのitem内でColumnにまとめる。件数は最大10件(MoodValidator.MAX_MOOD_COUNT)のため
      // 個々の行を遅延生成する恩恵は薄い。
      item(key = MOOD_LIST_ITEM_KEY) {
        Column(modifier = Modifier.highlightedSection(highlightMoodSelection)) {
          moods.forEach { mood ->
            MoodRow(mood = mood, onClick = { onMoodClick(mood) })
          }
        }
      }
      if (isNoteOnlyEntryVisible) {
        item(key = NOTE_ONLY_ITEM_KEY) {
          NoteOnlyRow(onClick = onNoteOnlyClick)
        }
      }
    }
  }
}

@Composable
private fun MoodRow(
  mood: Mood,
  onClick: () -> Unit,
) {
  val description = stringResource(R.string.mood_accessibility_description, mood.displayText)
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .semantics { contentDescription = description }
      .padding(horizontal = 8.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (mood.emoji.isNotBlank()) {
      Text(text = mood.emoji, style = MaterialTheme.typography.headlineSmall)
    }
    if (mood.emoji.isNotBlank() && mood.label.isNotBlank()) {
      Spacer(Modifier.width(16.dp))
    }
    if (mood.label.isNotBlank()) {
      Text(text = mood.label, style = MaterialTheme.typography.bodyLarge)
    }
  }
}

/** Moodの選択肢と混ざらないよう、区切り線を挟んで一覧の末尾へ置く。 */
@Composable
private fun NoteOnlyRow(onClick: () -> Unit) {
  Column {
    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    Text(
      text = stringResource(R.string.record_note_only_entry),
      style = MaterialTheme.typography.bodyLarge,
      modifier = Modifier
        .fillMaxWidth()
        .clickable(onClick = onClick)
        .padding(horizontal = 8.dp, vertical = 14.dp),
    )
  }
}

private const val MOOD_LIST_ITEM_KEY = "mood-list"
private const val NOTE_ONLY_ITEM_KEY = "note-only-entry"

@Preview(showBackground = true)
@Composable
fun MoodRecordScreenPreview() {
  JournalingPostTheme {
    MoodRecordScreen(
      moods = listOf(
        Mood(id = "1", emoji = "🤩", label = "ワクワク"),
        Mood(id = "2", emoji = "", label = "集中"),
      ),
      isNoteOnlyEntryVisible = true,
      highlightMoodSelection = true,
      onMoodClick = {},
      onNoteOnlyClick = {},
    )
  }
}
