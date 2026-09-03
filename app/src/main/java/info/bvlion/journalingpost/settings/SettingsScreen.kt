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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import info.bvlion.journalingpost.AutoAnalysisSettingsUiState
import info.bvlion.journalingpost.R
import info.bvlion.journalingpost.SettingsUiState
import info.bvlion.journalingpost.ui.ScreenTopAppBar
import info.bvlion.journalingpost.ui.theme.JournalingPostTheme
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Mood・記録導線と解析・連携の各設定への入口。JournalEntryが常にローカル保存されることや、DataStoreの
 * 内部状態はここへ説明として出さない。Custom Webhookの詳細(URL/ヘッダー/本文)は
 * [WebhookSettingsScreen]側の責務で、この画面では現在の送信先を示す短い情報のみ扱う。
 */
@Composable
fun SettingsScreen(
  uiState: SettingsUiState,
  /** 「自動解析」セクションの状態。読み込み確定前はnullで、その間はセクションを出さない。 */
  autoAnalysisUiState: AutoAnalysisSettingsUiState?,
  /** 初回案内(#67)から「設定する」で遷移した直後だけtrue。解析・連携セクションの場所を一度だけ示す。 */
  highlightAnalysisIntegration: Boolean,
  onAnalysisIntegrationChange: (AnalysisIntegration) -> Unit,
  onNoteOnlyEntryChange: (Boolean) -> Unit,
  onAutoAnalysisEnabledChange: (Boolean) -> Unit,
  onAutoAnalysisScheduleChange: (LocalTime, AutoAnalysisTargetDay) -> Unit,
  onMoodSettingsOpen: () -> Unit,
  onWebhookSettingsOpen: () -> Unit,
  /** debugビルドでのみ非null。動作確認用fixtureの投入導線を出すかどうかを兼ねる。 */
  onSeedDebugFixtures: (() -> Unit)? = null,
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
        if (highlightAnalysisIntegration) {
          Text(
            text = stringResource(R.string.settings_analysis_integration_highlight),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp),
          )
        }

        Column(modifier = Modifier.padding(top = 8.dp).selectableGroup()) {
          AnalysisIntegrationOption(
            title = stringResource(R.string.settings_integration_none),
            selected = uiState.selectedIntegration == AnalysisIntegration.NONE,
            onClick = { onAnalysisIntegrationChange(AnalysisIntegration.NONE) },
          )
          AnalysisIntegrationOption(
            title = stringResource(R.string.settings_integration_custom_webhook),
            description = stringResource(R.string.settings_integration_custom_webhook_description),
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

      // 解析先(Custom WebhookまたはHosted)が有効なときだけ「自動解析」を出す。
      val integration = uiState.selectedIntegration
      if (
        autoAnalysisUiState != null &&
        (integration == AnalysisIntegration.CUSTOM_WEBHOOK || integration == AnalysisIntegration.HOSTED)
      ) {
        AutoAnalysisSection(
          uiState = autoAnalysisUiState,
          onEnabledChange = onAutoAnalysisEnabledChange,
          onScheduleChange = onAutoAnalysisScheduleChange,
        )
      }

      if (onSeedDebugFixtures != null) {
        Column(modifier = Modifier.padding(16.dp)) {
          Text(
            text = stringResource(R.string.settings_debug_heading),
            style = MaterialTheme.typography.titleSmall,
          )
        }
        ListItem(
          headlineContent = { Text(stringResource(R.string.settings_debug_seed_fixtures_item)) },
          supportingContent = { Text(stringResource(R.string.settings_debug_seed_fixtures_description)) },
          modifier = Modifier.clickable(onClick = onSeedDebugFixtures),
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

private val autoAnalysisTimeFormatter = DateTimeFormatter.ofPattern("H:mm")

@Composable
private fun autoAnalysisTargetDayLabel(targetDay: AutoAnalysisTargetDay): String = stringResource(
  when (targetDay) {
    AutoAnalysisTargetDay.YESTERDAY -> R.string.settings_auto_analysis_target_day_yesterday
    AutoAnalysisTargetDay.TODAY -> R.string.settings_auto_analysis_target_day_today
  },
)

/**
 * 選んだ解析先へ対象日の記録を「指定の時刻ごろ」に自動で送るかどうかの設定(Issue #59)。
 * 有効なときだけ、時刻と対象日(当日/前日)を1つのダイアログでまとめて設定できる。
 */
@Composable
private fun AutoAnalysisSection(
  uiState: AutoAnalysisSettingsUiState,
  onEnabledChange: (Boolean) -> Unit,
  onScheduleChange: (LocalTime, AutoAnalysisTargetDay) -> Unit,
) {
  var showScheduleDialog by remember { mutableStateOf(false) }

  Column(modifier = Modifier.padding(top = 8.dp)) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
      Text(
        text = stringResource(R.string.settings_auto_analysis_heading),
        style = MaterialTheme.typography.titleSmall,
      )
    }

    ListItem(
      headlineContent = { Text(stringResource(R.string.settings_auto_analysis_item)) },
      supportingContent = { Text(stringResource(R.string.settings_auto_analysis_item_description)) },
      trailingContent = {
        Switch(checked = uiState.enabled, onCheckedChange = null)
      },
      modifier = Modifier.toggleable(
        value = uiState.enabled,
        onValueChange = onEnabledChange,
        role = Role.Switch,
      ),
    )

    if (uiState.enabled) {
      ListItem(
        headlineContent = { Text(stringResource(R.string.settings_auto_analysis_schedule_item)) },
        supportingContent = { Text(stringResource(R.string.settings_auto_analysis_time_note)) },
        trailingContent = {
          Text(
            stringResource(
              R.string.settings_auto_analysis_schedule_summary,
              uiState.timeOfDay.format(autoAnalysisTimeFormatter),
              autoAnalysisTargetDayLabel(uiState.targetDay),
            ),
          )
        },
        modifier = Modifier.clickable { showScheduleDialog = true },
      )
    }
  }

  if (showScheduleDialog) {
    AutoAnalysisScheduleDialog(
      initialTime = uiState.timeOfDay,
      initialTargetDay = uiState.targetDay,
      onConfirm = { time, targetDay ->
        onScheduleChange(time, targetDay)
        showScheduleDialog = false
      },
      onDismiss = { showScheduleDialog = false },
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutoAnalysisScheduleDialog(
  initialTime: LocalTime,
  initialTargetDay: AutoAnalysisTargetDay,
  onConfirm: (LocalTime, AutoAnalysisTargetDay) -> Unit,
  onDismiss: () -> Unit,
) {
  val timeState = rememberTimePickerState(
    initialHour = initialTime.hour,
    initialMinute = initialTime.minute,
    is24Hour = true,
  )
  var targetDay by remember { mutableStateOf(initialTargetDay) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.settings_auto_analysis_schedule_item)) },
    text = {
      Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        TimeInput(state = timeState)

        Text(
          text = stringResource(R.string.settings_auto_analysis_target_day_heading),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 8.dp),
        )
        Column(modifier = Modifier.padding(top = 4.dp).selectableGroup()) {
          AutoAnalysisTargetDayOption(
            title = stringResource(R.string.settings_auto_analysis_target_day_yesterday),
            selected = targetDay == AutoAnalysisTargetDay.YESTERDAY,
            onClick = { targetDay = AutoAnalysisTargetDay.YESTERDAY },
          )
          AutoAnalysisTargetDayOption(
            title = stringResource(R.string.settings_auto_analysis_target_day_today),
            selected = targetDay == AutoAnalysisTargetDay.TODAY,
            onClick = { targetDay = AutoAnalysisTargetDay.TODAY },
          )
        }
      }
    },
    confirmButton = {
      TextButton(onClick = { onConfirm(LocalTime.of(timeState.hour, timeState.minute), targetDay) }) {
        Text(stringResource(R.string.action_save))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
    },
  )
}

@Composable
private fun AutoAnalysisTargetDayOption(title: String, selected: Boolean, onClick: () -> Unit) {
  ListItem(
    headlineContent = { Text(title) },
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
      autoAnalysisUiState = AutoAnalysisSettingsUiState(
        enabled = true,
        timeOfDay = LocalTime.of(3, 0),
        targetDay = AutoAnalysisTargetDay.YESTERDAY,
      ),
      highlightAnalysisIntegration = false,
      onAnalysisIntegrationChange = {},
      onNoteOnlyEntryChange = {},
      onAutoAnalysisEnabledChange = {},
      onAutoAnalysisScheduleChange = { _, _ -> },
      onMoodSettingsOpen = {},
      onWebhookSettingsOpen = {},
    )
  }
}
