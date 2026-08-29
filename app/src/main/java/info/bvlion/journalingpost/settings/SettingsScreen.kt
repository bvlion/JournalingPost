package info.bvlion.journalingpost.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import info.bvlion.journalingpost.WebhookFormState
import info.bvlion.journalingpost.WebhookOperationFailure
import info.bvlion.journalingpost.ui.ScreenTopAppBar
import info.bvlion.journalingpost.ui.theme.JournalingPostTheme
import info.bvlion.journalingpost.webhook.WebhookHeader
import info.bvlion.journalingpost.webhook.WebhookSettingsOverview
import info.bvlion.journalingpost.webhook.WebhookSettingsValidator

@Composable
fun SettingsScreen(
  selectedIntegration: AnalysisIntegration,
  integrationSaveFailed: Boolean,
  onAnalysisIntegrationChange: (AnalysisIntegration) -> Unit,
  webhookOverview: WebhookSettingsOverview,
  isWebhookEditing: Boolean,
  webhookFormState: WebhookFormState,
  webhookValidationErrors: List<WebhookSettingsValidator.ValidationError>,
  webhookOperationFailure: WebhookOperationFailure?,
  onWebhookEditStart: () -> Unit,
  onWebhookEditCancel: () -> Unit,
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
  val usesCustomWebhook = selectedIntegration == AnalysisIntegration.CUSTOM_WEBHOOK
  var showWebhookDeleteConfirm by rememberSaveable { mutableStateOf(false) }

  Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
    ScreenTopAppBar(title = "設定", onBack = onBack)

    Column(modifier = Modifier.padding(16.dp)) {
      Text(
        text = "記録の保存",
        style = MaterialTheme.typography.titleSmall,
      )
      Text(
        text = "記録はこの端末へ常に保存されます。下の解析・連携の設定にかかわらず、保存は行われます。",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 4.dp),
      )
    }

    HorizontalDivider()

    Column(modifier = Modifier.padding(16.dp)) {
      Text(
        text = "解析・連携",
        style = MaterialTheme.typography.titleSmall,
      )
      Text(
        text = "保存した記録を外部でどう扱うかを選びます。選んだ内容はすぐに反映されます。" +
          "（Custom Webhookは送信先を保存した時点で有効になります）",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 4.dp),
      )

      if (integrationSaveFailed) {
        Text(
          text = "解析・連携の設定を保存できませんでした",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.padding(top = 4.dp),
        )
      }

      Column(modifier = Modifier.padding(top = 8.dp).selectableGroup()) {
        AnalysisIntegrationOption(
          title = "使用しない",
          description = "記録は端末内にとどまります。外部へは送信しません。",
          selected = selectedIntegration == AnalysisIntegration.NONE,
          onClick = { onAnalysisIntegrationChange(AnalysisIntegration.NONE) },
        )
        AnalysisIntegrationOption(
          title = "Custom Webhook",
          description = "記録したときに、設定したWebhookへ送信します。送信先の設定が必要です。",
          selected = usesCustomWebhook,
          onClick = { onAnalysisIntegrationChange(AnalysisIntegration.CUSTOM_WEBHOOK) },
        )
      }

      // 使っておらず保存済み設定もない間は、Custom Webhookの設定UI自体を出さない。
      AnimatedVisibility(visible = usesCustomWebhook || webhookOverview is WebhookSettingsOverview.Configured) {
        CustomWebhookSection(
          usesCustomWebhook = usesCustomWebhook,
          webhookOverview = webhookOverview,
          isWebhookEditing = isWebhookEditing,
          webhookFormState = webhookFormState,
          webhookValidationErrors = webhookValidationErrors,
          webhookOperationFailure = webhookOperationFailure,
          onWebhookEditStart = onWebhookEditStart,
          onWebhookEditCancel = onWebhookEditCancel,
          onWebhookUrlChange = onWebhookUrlChange,
          onWebhookHeaderAdd = onWebhookHeaderAdd,
          onWebhookHeaderRemove = onWebhookHeaderRemove,
          onWebhookHeaderNameChange = onWebhookHeaderNameChange,
          onWebhookHeaderValueChange = onWebhookHeaderValueChange,
          onWebhookBodyTemplateChange = onWebhookBodyTemplateChange,
          onWebhookSave = onWebhookSave,
          onWebhookDeleteRequest = { showWebhookDeleteConfirm = true },
        )
      }
    }
  }

  if (showWebhookDeleteConfirm) {
    AlertDialog(
      onDismissRequest = { showWebhookDeleteConfirm = false },
      title = { Text("Custom Webhook設定を削除しますか？") },
      // URL・Header値・Body templateはsecretを含み得るため、確認Dialogには値そのものを出さない。
      text = {
        Text(
          "保存済みのURL・HTTP Header・JSON Body templateを削除します。" +
            "削除すると解析・連携は「使用しない」へ戻り、記録は端末内にのみ保存されます。",
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            showWebhookDeleteConfirm = false
            onWebhookDelete()
          },
        ) {
          Text(text = "削除", color = MaterialTheme.colorScheme.error)
        }
      },
      dismissButton = {
        TextButton(onClick = { showWebhookDeleteConfirm = false }) {
          Text("キャンセル")
        }
      },
    )
  }
}

@Composable
private fun CustomWebhookSection(
  usesCustomWebhook: Boolean,
  webhookOverview: WebhookSettingsOverview,
  isWebhookEditing: Boolean,
  webhookFormState: WebhookFormState,
  webhookValidationErrors: List<WebhookSettingsValidator.ValidationError>,
  webhookOperationFailure: WebhookOperationFailure?,
  onWebhookEditStart: () -> Unit,
  onWebhookEditCancel: () -> Unit,
  onWebhookUrlChange: (String) -> Unit,
  onWebhookHeaderAdd: () -> Unit,
  onWebhookHeaderRemove: (Int) -> Unit,
  onWebhookHeaderNameChange: (Int, String) -> Unit,
  onWebhookHeaderValueChange: (Int, String) -> Unit,
  onWebhookBodyTemplateChange: (String) -> Unit,
  onWebhookSave: () -> Unit,
  onWebhookDeleteRequest: () -> Unit,
) {
  Column(modifier = Modifier.padding(top = 16.dp)) {
    Text(
      text = "Custom Webhookの設定",
      style = MaterialTheme.typography.titleSmall,
    )

    if (webhookOperationFailure != null) {
      Text(
        text = when (webhookOperationFailure) {
          WebhookOperationFailure.SAVE -> "Webhook設定を保存できませんでした"
          WebhookOperationFailure.DELETE -> "Webhook設定を削除できませんでした"
        },
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

    // 確認と編集は同じ場所で切り替わるため、内容が突然入れ替わらないようcross fadeと高さのアニメーションを挟む。
    AnimatedContent(
      targetState = isWebhookEditing,
      transitionSpec = {
        (fadeIn(tween(durationMillis = 180, delayMillis = 60)) togetherWith fadeOut(tween(durationMillis = 90)))
          .using(SizeTransform(clip = false))
      },
      label = "customWebhookEditing",
    ) { editing ->
      if (editing) {
        WebhookEditForm(
          webhookFormState = webhookFormState,
          canCancel = webhookOverview is WebhookSettingsOverview.Configured,
          canDelete = webhookOverview is WebhookSettingsOverview.Configured,
          onWebhookEditCancel = onWebhookEditCancel,
          onWebhookUrlChange = onWebhookUrlChange,
          onWebhookHeaderAdd = onWebhookHeaderAdd,
          onWebhookHeaderRemove = onWebhookHeaderRemove,
          onWebhookHeaderNameChange = onWebhookHeaderNameChange,
          onWebhookHeaderValueChange = onWebhookHeaderValueChange,
          onWebhookBodyTemplateChange = onWebhookBodyTemplateChange,
          onWebhookSave = onWebhookSave,
          onWebhookDeleteRequest = onWebhookDeleteRequest,
        )
      } else {
        WebhookOverviewContent(
          usesCustomWebhook = usesCustomWebhook,
          webhookOverview = webhookOverview,
          onWebhookEditStart = onWebhookEditStart,
          onWebhookDeleteRequest = onWebhookDeleteRequest,
        )
      }
    }
  }
}

@Composable
private fun WebhookOverviewContent(
  usesCustomWebhook: Boolean,
  webhookOverview: WebhookSettingsOverview,
  onWebhookEditStart: () -> Unit,
  onWebhookDeleteRequest: () -> Unit,
) {
  Column {
    when (webhookOverview) {
      // authoritativeな状態が分かるまでは新規設定フォームを確定表示しない
      // (既存設定を誤って空フォームで上書きしないため)。
      WebhookSettingsOverview.Loading -> SectionBodyText("Webhook設定を読み込んでいます")

      WebhookSettingsOverview.Unavailable -> SectionBodyText("Webhook設定を読み込めませんでした")

      WebhookSettingsOverview.NotConfigured -> SectionBodyText("送信先はまだ設定されていません。")

      is WebhookSettingsOverview.Configured -> if (usesCustomWebhook) {
        ConfiguredWebhookSummary(webhookOverview)
        TextButton(onClick = onWebhookEditStart, modifier = Modifier.padding(top = 4.dp)) {
          Text("設定を編集")
        }
      } else {
        SectionBodyText(
          "現在は使用していませんが、保存済みの設定は残しています。" +
            "再びCustom Webhookを選ぶと、この設定のまま送信を再開します。",
        )
        TextButton(onClick = onWebhookDeleteRequest, modifier = Modifier.padding(top = 4.dp)) {
          Text("保存済み設定を削除")
        }
      }
    }
  }
}

/**
 * secretを含み得る値(URLのpath以降・Header値・Body template本文)は出さず、
 * 何が設定されているかだけが分かる粒度で表示する。
 */
@Composable
private fun ConfiguredWebhookSummary(overview: WebhookSettingsOverview.Configured) {
  SectionBodyText("送信先: ${overview.destination}")
  SectionBodyText(
    if (overview.headerNames.isEmpty()) {
      "HTTP Header: なし"
    } else {
      "HTTP Header: ${overview.headerNames.joinToString(", ")}（値は表示しません）"
    },
  )
  SectionBodyText(
    if (overview.bodyTemplatePlaceholders.isEmpty()) {
      "JSON Body template: 設定済み（placeholderは使用していません）"
    } else {
      "JSON Body template: 設定済み（${overview.bodyTemplatePlaceholders.joinToString(", ") { "{{$it}}" }} を使用）"
    },
  )
  Text(
    text = "URLの詳細・Header値・Body templateの内容は、編集を開くと確認できます。",
    style = MaterialTheme.typography.bodySmall,
    modifier = Modifier.padding(top = 4.dp),
  )
}

@Composable
private fun SectionBodyText(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.bodyMedium,
    modifier = Modifier.padding(top = 8.dp),
  )
}

@Composable
private fun WebhookEditForm(
  webhookFormState: WebhookFormState,
  canCancel: Boolean,
  canDelete: Boolean,
  onWebhookEditCancel: () -> Unit,
  onWebhookUrlChange: (String) -> Unit,
  onWebhookHeaderAdd: () -> Unit,
  onWebhookHeaderRemove: (Int) -> Unit,
  onWebhookHeaderNameChange: (Int, String) -> Unit,
  onWebhookHeaderValueChange: (Int, String) -> Unit,
  onWebhookBodyTemplateChange: (String) -> Unit,
  onWebhookSave: () -> Unit,
  onWebhookDeleteRequest: () -> Unit,
) {
  Column {
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

    Text(
      text = if (canCancel) {
        "編集した内容は「保存する」を押すまで反映されません。"
      } else {
        // まだ有効化していない選択なので、保存しなければ「使用しない」のままであることを明示する。
        "「保存する」を押すとCustom Webhookが有効になります。保存せずに戻ると「使用しない」のままです。"
      },
      style = MaterialTheme.typography.bodySmall,
      modifier = Modifier.padding(top = 12.dp),
    )
    Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
      TextButton(onClick = onWebhookSave) {
        Text("保存する")
      }
      // 未設定からの新規入力には戻り先の確認状態がないため、キャンセルは保存済みの場合だけ出す。
      if (canCancel) {
        TextButton(onClick = onWebhookEditCancel) {
          Text("編集をやめる")
        }
      }
    }
    // 削除は設定内容を表示している状態からのみ行い、確認Dialogを挟む。
    if (canDelete) {
      TextButton(onClick = onWebhookDeleteRequest) {
        Text(text = "Webhook設定を削除", color = MaterialTheme.colorScheme.error)
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
private fun AnalysisIntegrationOption(
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
      selectedIntegration = AnalysisIntegration.CUSTOM_WEBHOOK,
      integrationSaveFailed = false,
      onAnalysisIntegrationChange = {},
      webhookOverview = WebhookSettingsOverview.Configured(
        destination = "https://hooks.example.com",
        headerNames = listOf("Authorization"),
        bodyTemplatePlaceholders = listOf("message"),
      ),
      isWebhookEditing = false,
      webhookFormState = WebhookFormState(),
      webhookValidationErrors = emptyList(),
      webhookOperationFailure = null,
      onWebhookEditStart = {},
      onWebhookEditCancel = {},
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
