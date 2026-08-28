package info.bvlion.journalingpost.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import info.bvlion.journalingpost.ui.theme.JournalingPostTheme

@Composable
fun SettingsScreen(
  recordMode: RecordMode,
  isWebhookConfigured: Boolean,
  onRecordModeChange: (RecordMode) -> Unit,
  onBack: () -> Unit,
) {
  Column(modifier = Modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      TextButton(onClick = onBack) {
        Text("戻る")
      }
      Text(
        text = "設定",
        style = MaterialTheme.typography.titleMedium,
      )
    }

    Column(modifier = Modifier.padding(16.dp).selectableGroup()) {
      Text(
        text = "記録の保存・送信方法",
        style = MaterialTheme.typography.titleSmall,
      )

      RecordModeOption(
        title = "ローカル保存 + Webhook送信",
        description = "この端末に記録を保存したうえで、設定済みのWebhookへも送信します。",
        selected = recordMode == RecordMode.LOCAL_AND_WEBHOOK,
        onClick = { onRecordModeChange(RecordMode.LOCAL_AND_WEBHOOK) },
      )
      RecordModeOption(
        title = "ローカル保存のみ",
        description = "この端末にのみ記録を保存します。通信は行いません。",
        selected = recordMode == RecordMode.LOCAL_ONLY,
        onClick = { onRecordModeChange(RecordMode.LOCAL_ONLY) },
      )

      if (recordMode == RecordMode.LOCAL_AND_WEBHOOK) {
        Text(
          text = if (isWebhookConfigured) {
            "Webhook送信先: 設定済み"
          } else {
            "Webhook送信先: 未設定（記録はローカルにのみ保存されます）"
          },
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.padding(top = 16.dp),
        )
      }
    }
  }
}

@Composable
private fun RecordModeOption(
  title: String,
  description: String,
  selected: Boolean,
  onClick: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth()
      .padding(vertical = 8.dp)
      .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    RadioButton(selected = selected, onClick = onClick)
    Column(modifier = Modifier.padding(start = 8.dp)) {
      Text(text = title, style = MaterialTheme.typography.bodyLarge)
      Text(text = description, style = MaterialTheme.typography.bodySmall)
    }
  }
}

@Preview(showBackground = true)
@Composable
fun SettingsScreenPreview() {
  JournalingPostTheme {
    SettingsScreen(
      recordMode = RecordMode.LOCAL_AND_WEBHOOK,
      isWebhookConfigured = false,
      onRecordModeChange = {},
      onBack = {},
    )
  }
}
