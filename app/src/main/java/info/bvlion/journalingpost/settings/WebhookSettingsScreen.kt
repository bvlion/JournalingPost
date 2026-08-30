package info.bvlion.journalingpost.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import info.bvlion.journalingpost.WebhookFormState
import info.bvlion.journalingpost.WebhookSettingsLoadState
import info.bvlion.journalingpost.ui.ScreenTopAppBar
import info.bvlion.journalingpost.ui.theme.JournalingPostTheme
import info.bvlion.journalingpost.webhook.WebhookBodyTemplateRenderer
import info.bvlion.journalingpost.webhook.WebhookHeader
import info.bvlion.journalingpost.webhook.WebhookSettingsValidator

/**
 * Custom Webhookの現在設定を、確認と編集を分けずにそのまま表示・変更する画面。
 * 変更は「保存する」で明示的に確定し、保存せずBack等で離脱した場合は保存済み設定を変更しない。
 */
@Composable
fun WebhookSettingsScreen(
  loadState: WebhookSettingsLoadState,
  formState: WebhookFormState,
  validationErrors: List<WebhookSettingsValidator.ValidationError>,
  saveFailed: Boolean,
  onUrlChange: (String) -> Unit,
  onHeaderAdd: () -> Unit,
  onHeaderRemove: (Int) -> Unit,
  onHeaderNameChange: (Int, String) -> Unit,
  onHeaderValueChange: (Int, String) -> Unit,
  onBodyTemplateChange: (String) -> Unit,
  onBodyTemplateReset: () -> Unit,
  onSave: () -> Unit,
  onBack: () -> Unit,
) {
  Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
    ScreenTopAppBar(title = "Webhook設定", onBack = onBack)

    Column(modifier = Modifier.padding(16.dp)) {
      if (saveFailed) {
        Text(
          text = "Webhook設定を保存できませんでした",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.padding(bottom = 4.dp),
        )
      }
      validationErrors.forEach { error ->
        Text(
          text = error.toMessage(),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.padding(bottom = 4.dp),
        )
      }

      when (loadState) {
        // authoritativeな状態が分かるまではフォームを表示しない
        // (既存設定を誤って空フォームで上書きしないため)。
        WebhookSettingsLoadState.LOADING -> Text(
          text = "Webhook設定を読み込んでいます",
          style = MaterialTheme.typography.bodyMedium,
        )

        WebhookSettingsLoadState.UNAVAILABLE -> Text(
          text = "Webhook設定を読み込めませんでした",
          style = MaterialTheme.typography.bodyMedium,
        )

        WebhookSettingsLoadState.READY -> WebhookSettingsForm(
          formState = formState,
          onUrlChange = onUrlChange,
          onHeaderAdd = onHeaderAdd,
          onHeaderRemove = onHeaderRemove,
          onHeaderNameChange = onHeaderNameChange,
          onHeaderValueChange = onHeaderValueChange,
          onBodyTemplateChange = onBodyTemplateChange,
          onBodyTemplateReset = onBodyTemplateReset,
          onSave = onSave,
        )
      }
    }
  }
}

@Composable
private fun WebhookSettingsForm(
  formState: WebhookFormState,
  onUrlChange: (String) -> Unit,
  onHeaderAdd: () -> Unit,
  onHeaderRemove: (Int) -> Unit,
  onHeaderNameChange: (Int, String) -> Unit,
  onHeaderValueChange: (Int, String) -> Unit,
  onBodyTemplateChange: (String) -> Unit,
  onBodyTemplateReset: () -> Unit,
  onSave: () -> Unit,
) {
  Column {
    OutlinedTextField(
      value = formState.url,
      onValueChange = onUrlChange,
      label = { Text("URL") },
      modifier = Modifier.fillMaxWidth(),
      singleLine = true,
    )

    Text(
      text = "HTTP Headers",
      style = MaterialTheme.typography.titleSmall,
      modifier = Modifier.padding(top = 16.dp),
    )
    formState.headers.forEachIndexed { index, header ->
      WebhookHeaderRow(
        header = header,
        onNameChange = { onHeaderNameChange(index, it) },
        onValueChange = { onHeaderValueChange(index, it) },
        onRemove = { onHeaderRemove(index) },
      )
    }
    TextButton(onClick = onHeaderAdd, modifier = Modifier.padding(top = 4.dp)) {
      Text("Headerを追加")
    }

    WebhookBodyTemplateField(
      bodyTemplate = formState.bodyTemplate,
      onBodyTemplateChange = onBodyTemplateChange,
      onBodyTemplateReset = onBodyTemplateReset,
    )

    WebhookContractReference()

    TextButton(onClick = onSave, modifier = Modifier.padding(top = 16.dp)) {
      Text("保存する")
    }
  }
}

@Composable
private fun WebhookBodyTemplateField(
  bodyTemplate: String,
  onBodyTemplateChange: (String) -> Unit,
  onBodyTemplateReset: () -> Unit,
) {
  var showResetConfirm by remember { mutableStateOf(false) }

  Text(
    text = "Request body (POST / application/json)",
    style = MaterialTheme.typography.titleSmall,
    modifier = Modifier.padding(top = 16.dp),
  )
  Text(
    text = "利用できるplaceholder: {{periodStart}} / {{periodEnd}} / {{entries}}",
    style = MaterialTheme.typography.bodySmall,
  )
  OutlinedTextField(
    value = bodyTemplate,
    onValueChange = onBodyTemplateChange,
    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    minLines = 6,
  )
  TextButton(onClick = { showResetConfirm = true }, modifier = Modifier.padding(top = 4.dp)) {
    Text("初期値に戻す")
  }

  if (showResetConfirm) {
    AlertDialog(
      onDismissRequest = { showResetConfirm = false },
      title = { Text("Body templateを初期値に戻す") },
      text = { Text("現在編集中のBody templateは破棄されます。よろしいですか？") },
      confirmButton = {
        TextButton(
          onClick = {
            onBodyTemplateReset()
            showResetConfirm = false
          },
        ) {
          Text("初期値に戻す")
        }
      },
      dismissButton = {
        TextButton(onClick = { showResetConfirm = false }) {
          Text("キャンセル")
        }
      },
    )
  }
}

@Composable
private fun WebhookContractReference() {
  var expanded by remember { mutableStateOf(false) }

  Text(
    text = if (expanded) "契約の詳細を隠す" else "契約の詳細を表示",
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier
      .padding(top = 12.dp)
      .clickable { expanded = !expanded },
  )

  if (expanded) {
    Column(modifier = Modifier.padding(top = 4.dp)) {
      ContractLine("送信方法", "POST / Content-Type: application/json")
      ContractLine(
        "{{periodStart}} / {{periodEnd}}",
        "対象期間の境界。RFC 3339のUTC文字列に展開されます（例: ${WebhookBodyTemplateRenderer.PERIOD_EXAMPLE}）。" +
          "template側では引用符で囲んで使います。",
      )
      ContractLine(
        "{{entries}}",
        "対象期間のJournalEntryのJSON arrayに、引用符なしのraw JSONとして展開されます。" +
          "展開後のbody全体が有効なJSONである必要があります。",
      )
      ContractLine(
        "{{entries}} の各要素",
        """{ "recordedAt": "<RFC 3339>", "mood": { "emoji": "…", "label": "…" }, "note": "…" }。""" +
          "moodのみ / noteのみの要素もあり、その場合はそのkeyが省略されます。",
      )
      ContractLine(
        "上記以外の {{...}}",
        "placeholderとして扱われず、そのままの文字列で送信されます。",
      )
      ContractLine(
        "レスポンス（成功時）",
        """{ "analysis": { "text": "振り返り本文", "…": "…" } }。HTTP 2xxで analysis.text が空でなければ成功として保存します。""" +
          "共通schemaの定義元はJournalingPostServerの docs/hosted-analysis-api.md です。",
      )
    }
  }
}

@Composable
private fun ContractLine(term: String, description: String) {
  Column(modifier = Modifier.padding(top = 6.dp)) {
    Text(text = term, style = MaterialTheme.typography.labelMedium)
    Text(
      text = description,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
  WebhookSettingsValidator.ValidationError.INVALID_BODY_TEMPLATE ->
    "Body templateが、{{entries}}を展開したときに有効なJSONになりません"
}

@Preview(showBackground = true)
@Composable
fun WebhookSettingsScreenPreview() {
  JournalingPostTheme {
    WebhookSettingsScreen(
      loadState = WebhookSettingsLoadState.READY,
      formState = WebhookFormState(
        url = "https://hooks.example.com/services/xxx",
        headers = listOf(WebhookHeader("Authorization", "Bearer xxxxx")),
      ),
      validationErrors = emptyList(),
      saveFailed = false,
      onUrlChange = {},
      onHeaderAdd = {},
      onHeaderRemove = {},
      onHeaderNameChange = { _, _ -> },
      onHeaderValueChange = { _, _ -> },
      onBodyTemplateChange = {},
      onBodyTemplateReset = {},
      onSave = {},
      onBack = {},
    )
  }
}
