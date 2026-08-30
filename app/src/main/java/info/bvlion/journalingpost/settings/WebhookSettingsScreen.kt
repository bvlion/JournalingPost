package info.bvlion.journalingpost.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import info.bvlion.journalingpost.R
import info.bvlion.journalingpost.WebhookFormState
import info.bvlion.journalingpost.WebhookSaveResult
import info.bvlion.journalingpost.WebhookSettingsLoadState
import info.bvlion.journalingpost.ui.ScreenTopAppBar
import info.bvlion.journalingpost.ui.theme.JournalingPostTheme
import info.bvlion.journalingpost.webhook.WebhookBodyTemplateRenderer
import info.bvlion.journalingpost.webhook.WebhookHeader
import info.bvlion.journalingpost.webhook.WebhookSettingsValidator

/**
 * Custom Webhookを設定する画面。この画面だけで、送信先・ヘッダー・リクエスト本文・期待する成功
 * レスポンスが分かるようにする。入力ごとのvalidationは該当欄の下に出し、保存操作そのものの結果
 * (成功・失敗・有効化失敗)はSnackbarで伝える。
 */
@Composable
fun WebhookSettingsScreen(
  loadState: WebhookSettingsLoadState,
  formState: WebhookFormState,
  validationErrors: List<WebhookSettingsValidator.ValidationError>,
  saveResult: WebhookSaveResult?,
  onShowMessage: (String) -> Unit,
  onSaveResultShown: () -> Unit,
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
  val saveResultMessage = saveResult?.let { stringResource(it.messageRes()) }
  LaunchedEffect(saveResult) {
    if (saveResultMessage != null) {
      onShowMessage(saveResultMessage)
      onSaveResultShown()
    }
  }

  Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
    ScreenTopAppBar(title = stringResource(R.string.settings_webhook_item), onBack = onBack)

    Column(modifier = Modifier.padding(16.dp)) {
      when (loadState) {
        // authoritativeな状態が分かるまではフォームを表示しない
        // (既存設定を誤って空フォームで上書きしないため)。
        WebhookSettingsLoadState.LOADING -> Text(
          text = stringResource(R.string.webhook_settings_loading),
          style = MaterialTheme.typography.bodyMedium,
        )

        WebhookSettingsLoadState.UNAVAILABLE -> Text(
          text = stringResource(R.string.webhook_settings_unavailable),
          style = MaterialTheme.typography.bodyMedium,
        )

        WebhookSettingsLoadState.READY -> WebhookSettingsForm(
          formState = formState,
          validationErrors = validationErrors,
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
  validationErrors: List<WebhookSettingsValidator.ValidationError>,
  onUrlChange: (String) -> Unit,
  onHeaderAdd: () -> Unit,
  onHeaderRemove: (Int) -> Unit,
  onHeaderNameChange: (Int, String) -> Unit,
  onHeaderValueChange: (Int, String) -> Unit,
  onBodyTemplateChange: (String) -> Unit,
  onBodyTemplateReset: () -> Unit,
  onSave: () -> Unit,
) {
  val urlErrors = validationErrors.filter { it == WebhookSettingsValidator.ValidationError.INVALID_URL }
  val headerErrors = validationErrors.filter { it in HEADER_VALIDATION_ERRORS }
  val bodyErrors = validationErrors.filter { it == WebhookSettingsValidator.ValidationError.INVALID_BODY_TEMPLATE }

  Column {
    OutlinedTextField(
      value = formState.url,
      onValueChange = onUrlChange,
      label = { Text(stringResource(R.string.webhook_settings_url_label)) },
      isError = urlErrors.isNotEmpty(),
      modifier = Modifier.fillMaxWidth(),
      singleLine = true,
    )
    FieldErrors(urlErrors)

    SectionHeading(stringResource(R.string.webhook_settings_headers_heading))
    formState.headers.forEachIndexed { index, header ->
      WebhookHeaderRow(
        header = header,
        onNameChange = { onHeaderNameChange(index, it) },
        onValueChange = { onHeaderValueChange(index, it) },
        onRemove = { onHeaderRemove(index) },
      )
    }
    TextButton(onClick = onHeaderAdd, modifier = Modifier.padding(top = 4.dp)) {
      Text(stringResource(R.string.webhook_settings_header_add))
    }
    FieldErrors(headerErrors)

    WebhookBodyTemplateField(
      bodyTemplate = formState.bodyTemplate,
      bodyErrors = bodyErrors,
      onBodyTemplateChange = onBodyTemplateChange,
      onBodyTemplateReset = onBodyTemplateReset,
    )

    // 入力欄と保存を1つのまとまりとして扱い、保存は画面の主操作として明示する。
    Button(onClick = onSave, modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
      Text(stringResource(R.string.action_save))
    }

    // 保存後の入力とは別の参照情報として、期待する成功レスポンスを常時表示する。
    SectionHeading(stringResource(R.string.webhook_settings_response_heading))
    MonospaceBlock(stringResource(R.string.webhook_settings_success_response_example))
  }
}

@Composable
private fun WebhookBodyTemplateField(
  bodyTemplate: String,
  bodyErrors: List<WebhookSettingsValidator.ValidationError>,
  onBodyTemplateChange: (String) -> Unit,
  onBodyTemplateReset: () -> Unit,
) {
  var showResetConfirm by remember { mutableStateOf(false) }

  SectionHeading(stringResource(R.string.webhook_settings_body_heading))
  // placeholderの意味とentriesの展開例を、本文を編集する前に読めるひとまとまりの参照情報にする。
  Column(modifier = Modifier.padding(top = 4.dp)) {
    Text(
      text = stringResource(R.string.webhook_settings_body_hint),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    PlaceholderLine(
      stringResource(R.string.webhook_settings_placeholder_period_start, WebhookBodyTemplateRenderer.PERIOD_EXAMPLE),
    )
    PlaceholderLine(stringResource(R.string.webhook_settings_placeholder_period_end))
    PlaceholderLine(stringResource(R.string.webhook_settings_placeholder_entries))
    Text(
      text = stringResource(R.string.webhook_settings_entries_example_heading),
      style = MaterialTheme.typography.labelMedium,
      modifier = Modifier.padding(top = 8.dp),
    )
    MonospaceBlock(stringResource(R.string.webhook_settings_entries_example))
  }
  OutlinedTextField(
    value = bodyTemplate,
    onValueChange = onBodyTemplateChange,
    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    isError = bodyErrors.isNotEmpty(),
    minLines = 6,
  )
  FieldErrors(bodyErrors)
  TextButton(onClick = { showResetConfirm = true }, modifier = Modifier.padding(top = 4.dp)) {
    Text(stringResource(R.string.webhook_settings_body_reset))
  }

  if (showResetConfirm) {
    AlertDialog(
      onDismissRequest = { showResetConfirm = false },
      title = { Text(stringResource(R.string.webhook_settings_body_reset_confirm_title)) },
      text = { Text(stringResource(R.string.webhook_settings_body_reset_confirm_body)) },
      confirmButton = {
        TextButton(
          onClick = {
            onBodyTemplateReset()
            showResetConfirm = false
          },
        ) {
          Text(stringResource(R.string.webhook_settings_body_reset))
        }
      },
      dismissButton = {
        TextButton(onClick = { showResetConfirm = false }) {
          Text(stringResource(R.string.action_cancel))
        }
      },
    )
  }
}

@Composable
private fun SectionHeading(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.titleSmall,
    modifier = Modifier.padding(top = 16.dp),
  )
}

@Composable
private fun FieldErrors(errors: List<WebhookSettingsValidator.ValidationError>) {
  errors.forEach { error ->
    Text(
      text = stringResource(error.messageRes()),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.error,
      modifier = Modifier.padding(top = 4.dp),
    )
  }
}

@Composable
private fun PlaceholderLine(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(top = 2.dp),
  )
}

@Composable
private fun MonospaceBlock(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.bodySmall,
    fontFamily = FontFamily.Monospace,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(top = 4.dp),
  )
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
      label = { Text(stringResource(R.string.webhook_settings_header_name_label)) },
      modifier = Modifier.weight(1f),
      singleLine = true,
    )
    OutlinedTextField(
      value = header.value,
      onValueChange = onValueChange,
      label = { Text(stringResource(R.string.webhook_settings_header_value_label)) },
      modifier = Modifier.weight(1f).padding(start = 8.dp),
      singleLine = true,
      // secretを含み得るためヘッダー値は常時平文表示しない。
      visualTransformation = PasswordVisualTransformation(),
    )
    TextButton(onClick = onRemove) {
      Text(stringResource(R.string.action_delete))
    }
  }
}

// URLでも本文でもない、ヘッダー欄に紐づくvalidation。ヘッダーセクションの下へまとめて出す。
private val HEADER_VALIDATION_ERRORS = setOf(
  WebhookSettingsValidator.ValidationError.BLANK_HEADER_NAME,
  WebhookSettingsValidator.ValidationError.DUPLICATE_HEADER_NAME,
  WebhookSettingsValidator.ValidationError.RESERVED_CONTENT_TYPE_HEADER,
  WebhookSettingsValidator.ValidationError.INVALID_HEADER_SYNTAX,
)

private fun WebhookSaveResult.messageRes(): Int = when (this) {
  WebhookSaveResult.SUCCEEDED -> R.string.webhook_settings_save_succeeded
  WebhookSaveResult.FAILED -> R.string.webhook_settings_save_failed
  WebhookSaveResult.ACTIVATION_FAILED -> R.string.webhook_settings_activation_failed
}

private fun WebhookSettingsValidator.ValidationError.messageRes(): Int = when (this) {
  WebhookSettingsValidator.ValidationError.INVALID_URL -> R.string.webhook_settings_error_invalid_url
  WebhookSettingsValidator.ValidationError.BLANK_HEADER_NAME -> R.string.webhook_settings_error_blank_header_name
  WebhookSettingsValidator.ValidationError.DUPLICATE_HEADER_NAME -> R.string.webhook_settings_error_duplicate_header_name
  WebhookSettingsValidator.ValidationError.RESERVED_CONTENT_TYPE_HEADER ->
    R.string.webhook_settings_error_reserved_content_type
  WebhookSettingsValidator.ValidationError.INVALID_HEADER_SYNTAX -> R.string.webhook_settings_error_invalid_header_syntax
  WebhookSettingsValidator.ValidationError.INVALID_BODY_TEMPLATE -> R.string.webhook_settings_error_invalid_body_template
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
      saveResult = null,
      onShowMessage = {},
      onSaveResultShown = {},
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
