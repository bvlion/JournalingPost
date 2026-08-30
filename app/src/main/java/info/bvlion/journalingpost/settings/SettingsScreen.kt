package info.bvlion.journalingpost.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import info.bvlion.journalingpost.R
import info.bvlion.journalingpost.ui.ScreenTopAppBar
import info.bvlion.journalingpost.ui.theme.JournalingPostTheme

/**
 * ここで利用者が決めるのは「解析・連携を何で行うか」だけ。JournalEntryが常にローカル保存される
 * ことや、DataStoreの内部状態はここへ説明として出さない。Custom Webhookの詳細(URL/ヘッダー/本文)は
 * [WebhookSettingsScreen]側の責務で、この画面では現在の送信先を示す短い情報のみ扱う。
 */
@Composable
fun SettingsScreen(
  selectedIntegration: AnalysisIntegration?,
  integrationSaveFailed: Boolean,
  onShowMessage: (String) -> Unit,
  onIntegrationSaveFailedShown: () -> Unit,
  onAnalysisIntegrationChange: (AnalysisIntegration) -> Unit,
  webhookConfigured: Boolean,
  webhookDestinationLabel: String?,
  onWebhookSettingsOpen: () -> Unit,
) {
  val integrationSaveFailedMessage = stringResource(R.string.settings_integration_save_failed)
  LaunchedEffect(integrationSaveFailed) {
    if (integrationSaveFailed) {
      onShowMessage(integrationSaveFailedMessage)
      onIntegrationSaveFailedShown()
    }
  }

  Column(modifier = Modifier.fillMaxWidth()) {
    ScreenTopAppBar(title = stringResource(R.string.tab_settings))

    Column(modifier = Modifier.padding(16.dp)) {
      Text(
        text = stringResource(R.string.settings_analysis_integration_heading),
        style = MaterialTheme.typography.titleSmall,
      )

      Column(modifier = Modifier.padding(top = 8.dp).selectableGroup()) {
        AnalysisIntegrationOption(
          title = stringResource(R.string.settings_integration_none),
          selected = selectedIntegration == AnalysisIntegration.NONE,
          onClick = { onAnalysisIntegrationChange(AnalysisIntegration.NONE) },
        )
        AnalysisIntegrationOption(
          title = stringResource(R.string.settings_integration_custom_webhook),
          selected = selectedIntegration == AnalysisIntegration.CUSTOM_WEBHOOK,
          onClick = { onAnalysisIntegrationChange(AnalysisIntegration.CUSTOM_WEBHOOK) },
        )
      }
    }

    // Custom Webhookが実際に有効(保存済み設定が存在する)な場合のみ、その設定項目を出す。
    if (webhookConfigured) {
      ListItem(
        headlineContent = { Text(stringResource(R.string.settings_webhook_item)) },
        supportingContent = {
          Text(webhookDestinationLabel ?: stringResource(R.string.settings_webhook_destination_unknown))
        },
        trailingContent = {
          Icon(painter = painterResource(R.drawable.ic_chevron_right), contentDescription = null)
        },
        modifier = Modifier.clickable(onClick = onWebhookSettingsOpen),
      )
    }
  }
}

@Composable
private fun AnalysisIntegrationOption(
  title: String,
  selected: Boolean,
  onClick: () -> Unit,
) {
  ListItem(
    headlineContent = { Text(title) },
    leadingContent = { RadioButton(selected = selected, onClick = null) },
    modifier = Modifier.selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
  )
}

@Preview(showBackground = true)
@Composable
fun SettingsScreenPreview() {
  JournalingPostTheme {
    SettingsScreen(
      selectedIntegration = AnalysisIntegration.CUSTOM_WEBHOOK,
      integrationSaveFailed = false,
      onShowMessage = {},
      onIntegrationSaveFailedShown = {},
      onAnalysisIntegrationChange = {},
      webhookConfigured = true,
      webhookDestinationLabel = "https://hooks.example.com",
      onWebhookSettingsOpen = {},
    )
  }
}
