package info.bvlion.journalingpost.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import info.bvlion.journalingpost.ui.ScreenTopAppBar
import info.bvlion.journalingpost.ui.theme.JournalingPostTheme

/**
 * ここで利用者が決めるのは「解析・連携を何で行うか」だけ。JournalEntryが常にローカル保存される
 * ことや、DataStoreの内部状態はここへ説明として出さない。Custom Webhookの詳細(URL/Header/Body
 * template)は[WebhookSettingsScreen]側の責務で、この画面では現在の送信先を示す短い情報のみ扱う。
 */
@Composable
fun SettingsScreen(
  selectedIntegration: AnalysisIntegration,
  integrationSaveFailed: Boolean,
  onAnalysisIntegrationChange: (AnalysisIntegration) -> Unit,
  webhookDestinationLabel: String?,
  onWebhookSettingsOpen: () -> Unit,
  onBack: () -> Unit,
) {
  Column(modifier = Modifier.fillMaxWidth()) {
    ScreenTopAppBar(title = "設定", onBack = onBack)

    Column(modifier = Modifier.padding(16.dp)) {
      Text(
        text = "解析・連携",
        style = MaterialTheme.typography.titleSmall,
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
          selected = selectedIntegration == AnalysisIntegration.NONE,
          onClick = { onAnalysisIntegrationChange(AnalysisIntegration.NONE) },
        )
        AnalysisIntegrationOption(
          title = "Custom Webhook",
          selected = selectedIntegration == AnalysisIntegration.CUSTOM_WEBHOOK,
          onClick = { onAnalysisIntegrationChange(AnalysisIntegration.CUSTOM_WEBHOOK) },
        )
      }
    }

    // Custom Webhookが実際に有効(保存済み設定が存在する)な場合のみ、その設定項目を出す。
    if (webhookDestinationLabel != null) {
      ListItem(
        headlineContent = { Text("Webhook設定") },
        supportingContent = { Text(webhookDestinationLabel) },
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
      onAnalysisIntegrationChange = {},
      webhookDestinationLabel = "https://hooks.example.com",
      onWebhookSettingsOpen = {},
      onBack = {},
    )
  }
}
