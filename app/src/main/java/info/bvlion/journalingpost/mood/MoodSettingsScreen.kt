package info.bvlion.journalingpost.mood

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import info.bvlion.journalingpost.MoodDraft
import info.bvlion.journalingpost.MoodSettingsUiState
import info.bvlion.journalingpost.R
import info.bvlion.journalingpost.ui.ScreenTopAppBar
import info.bvlion.journalingpost.ui.theme.JournalingPostTheme

@Composable
fun MoodSettingsScreen(
  uiState: MoodSettingsUiState,
  onEmojiChange: (id: String, emoji: String) -> Unit,
  onLabelChange: (id: String, label: String) -> Unit,
  onMoveUp: (String) -> Unit,
  onMoveDown: (String) -> Unit,
  onAdd: () -> Unit,
  onRemove: (String) -> Unit,
  onSave: () -> Unit,
  onBack: () -> Unit,
) {
  Column(modifier = Modifier.fillMaxSize()) {
    ScreenTopAppBar(title = stringResource(R.string.mood_settings_title), onBack = onBack)

    if (uiState.isLoading) {
      Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
      ) {
        CircularProgressIndicator()
      }
      return@Column
    }

    LazyColumn(
      modifier = Modifier.weight(1f),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      item {
        Text(
          text = stringResource(R.string.mood_settings_description),
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.padding(horizontal = 16.dp),
        )
      }
      itemsIndexed(uiState.moods, key = { _, mood -> mood.id }) { index, mood ->
        Column(modifier = Modifier.animateItem()) {
          MoodEditor(
            mood = mood,
            index = index,
            count = uiState.moods.size,
            isEnabled = !uiState.isSaving,
            onEmojiChange = { onEmojiChange(mood.id, it) },
            onLabelChange = { onLabelChange(mood.id, it) },
            onMoveUp = { onMoveUp(mood.id) },
            onMoveDown = { onMoveDown(mood.id) },
            onRemove = { onRemove(mood.id) },
          )
          HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
        }
      }
    }

    Column(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        text = stringResource(R.string.mood_settings_count, uiState.moods.size, MoodValidator.MAX_MOOD_COUNT),
        style = MaterialTheme.typography.bodySmall,
      )
      OutlinedButton(
        onClick = onAdd,
        modifier = Modifier.fillMaxWidth(),
        enabled = !uiState.isSaving && uiState.moods.size < MoodValidator.MAX_MOOD_COUNT,
      ) {
        Text(stringResource(R.string.mood_settings_add))
      }
      Button(
        onClick = onSave,
        modifier = Modifier.fillMaxWidth(),
        enabled = uiState.canSave,
      ) {
        if (uiState.isSaving) {
          CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
          Text(stringResource(R.string.action_save))
        }
      }
    }
  }
}

@Composable
private fun MoodEditor(
  mood: MoodDraft,
  index: Int,
  count: Int,
  isEnabled: Boolean,
  onEmojiChange: (String) -> Unit,
  onLabelChange: (String) -> Unit,
  onMoveUp: () -> Unit,
  onMoveDown: () -> Unit,
  onRemove: () -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(
      text = stringResource(R.string.mood_settings_item, index + 1),
      style = MaterialTheme.typography.titleSmall,
    )
    OutlinedTextField(
      value = mood.emoji,
      onValueChange = onEmojiChange,
      modifier = Modifier.fillMaxWidth(),
      enabled = isEnabled,
      isError = mood.isEmojiInvalid,
      label = { Text(stringResource(R.string.mood_settings_emoji_label)) },
      supportingText = if (mood.isEmojiInvalid) {
        { Text(stringResource(R.string.mood_settings_emoji_error)) }
      } else {
        null
      },
      singleLine = true,
    )
    OutlinedTextField(
      value = mood.label,
      onValueChange = onLabelChange,
      modifier = Modifier.fillMaxWidth(),
      enabled = isEnabled,
      isError = mood.isContentBlank,
      label = { Text(stringResource(R.string.mood_settings_name_label)) },
      supportingText = if (mood.isContentBlank) {
        { Text(stringResource(R.string.mood_settings_content_error)) }
      } else {
        null
      },
      singleLine = true,
    )
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.End,
    ) {
      TextButton(onClick = onMoveUp, enabled = isEnabled && index > 0) {
        Text(stringResource(R.string.mood_settings_move_up))
      }
      TextButton(onClick = onMoveDown, enabled = isEnabled && index < count - 1) {
        Text(stringResource(R.string.mood_settings_move_down))
      }
      TextButton(onClick = onRemove, enabled = isEnabled && count > MoodValidator.MIN_MOOD_COUNT) {
        Text(stringResource(R.string.action_delete))
      }
    }
  }
}

@Preview(showBackground = true)
@Composable
fun MoodSettingsScreenPreview() {
  JournalingPostTheme {
    MoodSettingsScreen(
      uiState = MoodSettingsUiState(
        moods = listOf(
          MoodDraft(id = "1", emoji = "🤩", label = "ワクワク"),
          MoodDraft(id = "2", emoji = "", label = "集中"),
        ),
        isLoading = false,
      ),
      onEmojiChange = { _, _ -> },
      onLabelChange = { _, _ -> },
      onMoveUp = {},
      onMoveDown = {},
      onAdd = {},
      onRemove = {},
      onSave = {},
      onBack = {},
    )
  }
}
