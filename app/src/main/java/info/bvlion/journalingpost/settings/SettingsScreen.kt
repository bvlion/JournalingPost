package info.bvlion.journalingpost.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import info.bvlion.journalingpost.R
import info.bvlion.journalingpost.SettingsUiState
import info.bvlion.journalingpost.ui.ScreenTopAppBar
import info.bvlion.journalingpost.ui.theme.JournalingPostTheme

/**
 * Mood・記録導線と解析・連携の各設定への入口。JournalEntryが常にローカル保存されることや、DataStoreの
 * 内部状態はここへ説明として出さない。Custom Webhookの詳細(URL/ヘッダー/本文)は
 * [WebhookSettingsScreen]側の責務で、この画面では現在の送信先を示す短い情報のみ扱う。
 */
@Composable
fun SettingsScreen(
  uiState: SettingsUiState,
  onAnalysisIntegrationChange: (AnalysisIntegration) -> Unit,
  onNoteOnlyEntryChange: (Boolean) -> Unit,
  onMoodSettingsOpen: () -> Unit,
  onWebhookSettingsOpen: () -> Unit,
) {
  Column(modifier = Modifier.fillMaxSize()) {
    ScreenTopAppBar(title = stringResource(R.string.tab_settings))

    Column(
      modifier = Modifier
        .fillMaxWidth()
        .weight(1f)
        .verticalScroll(rememberScrollState()),
    ) {
      ListItem(
        headlineContent = { Text(stringResource(R.string.settings_mood_item)) },
        supportingContent = { Text(stringResource(R.string.settings_mood_item_description)) },
        trailingContent = {
          Icon(painter = painterResource(R.drawable.ic_chevron_right), contentDescription = null)
        },
        modifier = Modifier.clickable(onClick = onMoodSettingsOpen),
      )

      // 読み込み確定前は現在値を断定できないため、操作を受け付けない。
      val noteOnlyEntryEnabled = uiState.noteOnlyEntryEnabled
      ListItem(
        headlineContent = { Text(stringResource(R.string.settings_note_only_item)) },
        supportingContent = { Text(stringResource(R.string.settings_note_only_item_description)) },
        trailingContent = {
          Switch(
            checked = noteOnlyEntryEnabled == true,
            onCheckedChange = null,
            enabled = noteOnlyEntryEnabled != null,
          )
        },
        modifier = Modifier.toggleable(
          value = noteOnlyEntryEnabled == true,
          enabled = noteOnlyEntryEnabled != null,
          onValueChange = onNoteOnlyEntryChange,
          role = Role.Switch,
        ),
      )

      Column(modifier = Modifier.padding(16.dp)) {
        Text(
          text = stringResource(R.string.settings_analysis_integration_heading),
          style = MaterialTheme.typography.titleSmall,
        )

        Column(modifier = Modifier.padding(top = 8.dp).selectableGroup()) {
          AnalysisIntegrationOption(
            title = stringResource(R.string.settings_integration_none),
            selected = uiState.selectedIntegration == AnalysisIntegration.NONE,
            onClick = { onAnalysisIntegrationChange(AnalysisIntegration.NONE) },
          )
          AnalysisIntegrationOption(
            title = stringResource(R.string.settings_integration_custom_webhook),
            selected = uiState.selectedIntegration == AnalysisIntegration.CUSTOM_WEBHOOK,
            onClick = { onAnalysisIntegrationChange(AnalysisIntegration.CUSTOM_WEBHOOK) },
          )
          AnalysisIntegrationOption(
            title = stringResource(R.string.settings_integration_hosted),
            description = stringResource(R.string.settings_integration_hosted_description),
            selected = uiState.selectedIntegration == AnalysisIntegration.HOSTED,
            onClick = { onAnalysisIntegrationChange(AnalysisIntegration.HOSTED) },
          )
        }
      }

      // Custom Webhookが実際に有効(保存済み設定が存在する)な場合のみ、その設定項目を出す。
      if (uiState.webhookConfigured) {
        ListItem(
          headlineContent = { Text(stringResource(R.string.settings_webhook_item)) },
          supportingContent = {
            Text(uiState.webhookDestinationLabel ?: stringResource(R.string.settings_webhook_destination_unknown))
          },
          trailingContent = {
            Icon(painter = painterResource(R.drawable.ic_chevron_right), contentDescription = null)
          },
          modifier = Modifier.clickable(onClick = onWebhookSettingsOpen),
        )
      }
    }
  }
}

@Composable
private fun AnalysisIntegrationOption(
  title: String,
  selected: Boolean,
  onClick: () -> Unit,
  description: String? = null,
) {
  ListItem(
    headlineContent = { Text(title) },
    supportingContent = description?.let { { Text(it) } },
    leadingContent = { RadioButton(selected = selected, onClick = null) },
    modifier = Modifier.selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
  )
}

/**
 * Hostedを選んだときに一度だけ確認する外部送信の同意ダイアログ。対象期間のJournalEntryが
 * AI解析のためJournalingPostのサーバーへ送信されること、原本と解析結果は端末に残ることを伝える。
 */
@Composable
fun HostedConsentDialog(
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.settings_hosted_consent_title)) },
    text = { Text(stringResource(R.string.settings_hosted_consent_body)) },
    confirmButton = {
      TextButton(onClick = onConfirm) { Text(stringResource(R.string.settings_hosted_consent_confirm)) }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
    },
  )
}

@Preview(showBackground = true)
@Composable
fun SettingsScreenPreview() {
  JournalingPostTheme {
    SettingsScreen(
      uiState = SettingsUiState(
        selectedIntegration = AnalysisIntegration.CUSTOM_WEBHOOK,
        webhookConfigured = true,
        webhookDestinationLabel = "https://hooks.example.com",
        noteOnlyEntryEnabled = true,
      ),
      onAnalysisIntegrationChange = {},
      onNoteOnlyEntryChange = {},
      onMoodSettingsOpen = {},
      onWebhookSettingsOpen = {},
    )
  }
}
