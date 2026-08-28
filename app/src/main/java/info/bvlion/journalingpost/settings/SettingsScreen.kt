package info.bvlion.journalingpost.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import info.bvlion.journalingpost.WebhookFormState
import info.bvlion.journalingpost.ui.theme.JournalingPostTheme
import info.bvlion.journalingpost.webhook.WebhookHeader
import info.bvlion.journalingpost.webhook.WebhookSettingsState
import info.bvlion.journalingpost.webhook.WebhookSettingsValidator

@Composable
fun SettingsScreen(
  recordMode: RecordMode,
  saveFailed: Boolean,
  onRecordModeChange: (RecordMode) -> Unit,
  webhookSettingsState: WebhookSettingsState,
  isWebhookFormVisible: Boolean,
  webhookFormState: WebhookFormState,
  webhookValidationErrors: List<WebhookSettingsValidator.ValidationError>,
  webhookSaveFailed: Boolean,
  onWebhookReveal: () -> Unit,
  onWebhookUrlChange: (String) -> Unit,
  onWebhookHeaderAdd: () -> Unit,
  onWebhookHeaderRemove: (Int) -> Unit,
  onWebhookHeaderNameChange: (Int, String) -> Unit,
  onWebhookHeaderValueChange: (Int, String) -> Unit,
  onWebhookBodyTemplateChange: (String) -> Unit,
  onWebhookSave: () -> Unit,
  onWebhookDelete: () -> Unit,
  onBack: () -> Unit,
) {
  val isWebhookConfigured = webhookSettingsState is WebhookSettingsState.Configured
  Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
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

      if (saveFailed) {
        Text(
          text = "設定を保存できませんでした",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.padding(top = 4.dp),
        )
      }

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

    HorizontalDivider()

    Column(modifier = Modifier.padding(16.dp)) {
      Text(
        text = "Custom Webhook設定",
        style = MaterialTheme.typography.titleSmall,
      )

      if (webhookSaveFailed) {
        Text(
          text = "Webhook設定を保存できませんでした",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.padding(top = 4.dp),
        )
      }
      webhookValidationErrors.forEach { error ->
        Text(
          text = error.toMessage(),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.padding(top = 4.dp),
        )
      }

      if (webhookSettingsState is WebhookSettingsState.Loading || webhookSettingsState is WebhookSettingsState.Unavailable) {
        // authoritativeな状態が分かるまでは新規設定フォームを確定表示しない
        // (既存設定を誤って空フォームで上書きしないため)。
        Text(
          text = if (webhookSettingsState is WebhookSettingsState.Unavailable) {
            "Webhook設定を読み込めませんでした"
          } else {
            "Webhook設定を読み込んでいます"
          },
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.padding(top = 12.dp),
        )
      } else if (isWebhookFormVisible) {
        OutlinedTextField(
          value = webhookFormState.url,
          onValueChange = onWebhookUrlChange,
          label = { Text("URL") },
          modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
          singleLine = true,
        )

        Text(
          text = "HTTP Headers",
          style = MaterialTheme.typography.titleSmall,
          modifier = Modifier.padding(top = 16.dp),
        )
        webhookFormState.headers.forEachIndexed { index, header ->
          WebhookHeaderRow(
            header = header,
            onNameChange = { onWebhookHeaderNameChange(index, it) },
            onValueChange = { onWebhookHeaderValueChange(index, it) },
            onRemove = { onWebhookHeaderRemove(index) },
          )
        }
        TextButton(onClick = onWebhookHeaderAdd, modifier = Modifier.padding(top = 4.dp)) {
          Text("Headerを追加")
        }

        Text(
          text = "JSON Body template",
          style = MaterialTheme.typography.titleSmall,
          modifier = Modifier.padding(top = 16.dp),
        )
        Text(
          text = "利用可能なplaceholder: {{message}} / {{timestamp}}",
          style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
          value = webhookFormState.bodyTemplate,
          onValueChange = onWebhookBodyTemplateChange,
          modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
          minLines = 5,
        )

        Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
          TextButton(onClick = onWebhookSave) {
            Text("Webhook設定を保存")
          }
          if (isWebhookConfigured) {
            TextButton(onClick = onWebhookDelete) {
              Text("Webhook設定を削除")
            }
          }
        }
      } else {
        // 保存済み設定にはHeader value・Body template等のsecretが含まれ得るため、画面を開いただけでは
        // 表示せず、ユーザーが明示的に選んだ場合だけ展開する。
        Text(
          text = "設定済み",
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.padding(top = 12.dp),
        )
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
          TextButton(onClick = onWebhookReveal) {
            Text("設定内容を表示して編集")
          }
          TextButton(onClick = onWebhookDelete) {
            Text("Webhook設定を削除")
          }
        }
      }
    }
  }
}

@Composable
private fun WebhookHeaderRow(
  header: WebhookHeader,
  onNameChange: (String) -> Unit,
  onValueChange: (String) -> Unit,
  onRemove: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    OutlinedTextField(
      value = header.name,
      onValueChange = onNameChange,
      label = { Text("Header名") },
      modifier = Modifier.weight(1f),
      singleLine = true,
    )
    OutlinedTextField(
      value = header.value,
      onValueChange = onValueChange,
      label = { Text("値") },
      modifier = Modifier.weight(1f).padding(start = 8.dp),
      singleLine = true,
      // secretを含み得るためHeader値は常時平文表示しない。
      visualTransformation = PasswordVisualTransformation(),
    )
    TextButton(onClick = onRemove) {
      Text("削除")
    }
  }
}

private fun WebhookSettingsValidator.ValidationError.toMessage(): String = when (this) {
  WebhookSettingsValidator.ValidationError.INVALID_URL -> "URLがhttpまたはhttpsのURLとして正しくありません"
  WebhookSettingsValidator.ValidationError.BLANK_HEADER_NAME -> "Header名が空になっている項目があります"
  WebhookSettingsValidator.ValidationError.DUPLICATE_HEADER_NAME -> "同じHeader名が複数あります"
  WebhookSettingsValidator.ValidationError.RESERVED_CONTENT_TYPE_HEADER -> "Content-TypeはHeaderとして指定できません"
  WebhookSettingsValidator.ValidationError.INVALID_HEADER_SYNTAX -> "Header名の形式が正しくないか、Headerに改行が含まれています"
  WebhookSettingsValidator.ValidationError.INVALID_BODY_TEMPLATE -> "Body templateが有効なJSON、またはサポート対象のplaceholderではありません"
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
    // 選択操作は親Rowのselectableへ集約し、TalkBack等が同じ選択肢を二重に読み上げないようにする。
    RadioButton(selected = selected, onClick = null)
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
      saveFailed = false,
      onRecordModeChange = {},
      webhookSettingsState = WebhookSettingsState.NotConfigured,
      isWebhookFormVisible = true,
      webhookFormState = WebhookFormState(),
      webhookValidationErrors = emptyList(),
      webhookSaveFailed = false,
      onWebhookReveal = {},
      onWebhookUrlChange = {},
      onWebhookHeaderAdd = {},
      onWebhookHeaderRemove = {},
      onWebhookHeaderNameChange = { _, _ -> },
      onWebhookHeaderValueChange = { _, _ -> },
      onWebhookBodyTemplateChange = {},
      onWebhookSave = {},
      onWebhookDelete = {},
      onBack = {},
    )
  }
}
