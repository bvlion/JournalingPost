package info.bvlion.journalingpost.analysis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import info.bvlion.journalingpost.AnalysisRunState
import info.bvlion.journalingpost.R
import info.bvlion.journalingpost.ui.ScreenTopAppBar
import info.bvlion.journalingpost.ui.theme.JournalingPostTheme
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

// 期間は時間帯まで指定され得るため(#40)、日付だけでなく時刻も表示する。
private val analysisDateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/M/d HH:mm", Locale.JAPAN)

private val analysisDayFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.JAPAN)

/**
 * 解析履歴の遷移先。#37で保存したAnalysisResultを新しい順の一覧で表示する。
 *
 * Custom Webhookが解析先として有効な場合([canRunAnalysis])だけ、上部に「解析する」導線を出し、
 * 対象日を1日選んで手動解析を実行できる。実行結果はSnackbarで伝える。対象日の記録が0件かどうかは
 * 実行後の解析処理側で判定する。
 */
@Composable
fun AnalysisHistoryScreen(
  uiState: AnalysisHistoryUiState,
  canRunAnalysis: Boolean,
  runState: AnalysisRunState,
  onShowMessage: (String) -> Unit,
  onRunResultShown: () -> Unit,
  onAnalyze: (LocalDate) -> Unit,
) {
  val completedMessage = stringResource(R.string.analysis_completed)
  val failureMessage = (runState as? AnalysisRunState.Failed)?.let { failed ->
    when (failed.failure) {
      // 対象日をSnackbar側で持ち直さず、失敗結果に含まれる日をそのまま文言へ入れる。
      PeriodAnalysisOutcome.Failure.NO_ENTRIES ->
        stringResource(failed.messageRes(), failed.day.format(analysisDayFormatter))

      else -> stringResource(failed.messageRes())
    }
  }
  LaunchedEffect(runState) {
    when (runState) {
      is AnalysisRunState.Succeeded -> {
        onShowMessage(completedMessage)
        onRunResultShown()
      }

      is AnalysisRunState.Failed -> {
        failureMessage?.let(onShowMessage)
        onRunResultShown()
      }

      else -> Unit
    }
  }

  Column(modifier = Modifier.fillMaxSize()) {
    ScreenTopAppBar(title = stringResource(R.string.tab_analysis_history))

    if (canRunAnalysis || runState is AnalysisRunState.Running) {
      AnalysisTrigger(
        canRunAnalysis = canRunAnalysis,
        isRunning = runState is AnalysisRunState.Running,
        onAnalyze = onAnalyze,
      )
    }

    when (uiState) {
      AnalysisHistoryUiState.Loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
      }

      AnalysisHistoryUiState.Empty -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
          text = stringResource(R.string.analysis_history_empty),
          style = MaterialTheme.typography.bodyMedium,
        )
      }

      is AnalysisHistoryUiState.Content -> LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        items(uiState.items, key = { it.id }) { item ->
          AnalysisHistoryCard(item)
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnalysisTrigger(
  canRunAnalysis: Boolean,
  isRunning: Boolean,
  onAnalyze: (LocalDate) -> Unit,
) {
  var showDatePicker by remember { mutableStateOf(false) }

  Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
    when {
      isRunning -> Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(
          text = stringResource(R.string.analysis_running),
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.padding(start = 8.dp),
        )
      }

      canRunAnalysis -> TextButton(
        onClick = { showDatePicker = true },
        contentPadding = PaddingValues(0.dp),
      ) {
        Text(stringResource(R.string.analysis_run_button))
      }
    }
  }

  if (showDatePicker && canRunAnalysis) {
    val zoneId = remember { ZoneId.systemDefault() }
    val datePickerState = rememberDatePickerState(
      initialSelectedDateMillis = LocalDate.now(zoneId).toUtcMillis(),
    )
    val pickedDay = datePickerState.selectedDateMillis?.toLocalDateFromUtc()

    fun close() {
      showDatePicker = false
    }

    DatePickerDialog(
      onDismissRequest = { close() },
      confirmButton = {
        TextButton(
          enabled = pickedDay != null,
          onClick = {
            if (pickedDay != null) onAnalyze(pickedDay)
            close()
          },
        ) {
          Text(stringResource(R.string.analysis_run_button))
        }
      },
      dismissButton = {
        TextButton(onClick = { close() }) {
          Text(stringResource(R.string.action_cancel))
        }
      },
    ) {
      DatePicker(state = datePickerState)
    }
  }
}

// Material3 DatePickerはUTC基準のmillisで日付を扱う。端末timezoneのカレンダー日と相互変換する。
private fun LocalDate.toUtcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toLocalDateFromUtc(): LocalDate =
  Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

private fun AnalysisRunState.Failed.messageRes(): Int = when (failure) {
  PeriodAnalysisOutcome.Failure.WEBHOOK_UNAVAILABLE -> R.string.analysis_failure_webhook_unavailable
  PeriodAnalysisOutcome.Failure.NO_ENTRIES -> R.string.analysis_failure_no_entries
  PeriodAnalysisOutcome.Failure.LOCAL_READ -> R.string.analysis_failure_local_read
  PeriodAnalysisOutcome.Failure.NETWORK -> R.string.analysis_failure_network
  PeriodAnalysisOutcome.Failure.SERVER_ERROR -> R.string.analysis_failure_server_error
  PeriodAnalysisOutcome.Failure.INVALID_RESPONSE -> R.string.analysis_failure_invalid_response
  null -> R.string.analysis_failure_save
}

@Composable
private fun AnalysisHistoryCard(item: AnalysisHistoryItem) {
  Column(modifier = Modifier.fillMaxWidth()) {
    Text(
      text = stringResource(
        R.string.analysis_card_period,
        item.periodStart.format(analysisDateTimeFormatter),
        item.periodEnd.format(analysisDateTimeFormatter),
      ),
      style = MaterialTheme.typography.titleSmall,
    )
    Text(
      text = stringResource(R.string.analysis_card_analyzed_at, item.analyzedAt.format(analysisDateTimeFormatter)),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 2.dp),
    )
    Text(
      text = item.body,
      style = MaterialTheme.typography.bodyMedium,
      modifier = Modifier.padding(top = 8.dp),
    )
  }
}

@Preview(showBackground = true)
@Composable
fun AnalysisHistoryScreenPreview() {
  JournalingPostTheme {
    AnalysisHistoryScreen(
      uiState = AnalysisHistoryUiState.Content(
        listOf(
          AnalysisHistoryItem(
            id = 1,
            periodStart = LocalDateTime.of(2026, 8, 23, 0, 0),
            periodEnd = LocalDateTime.of(2026, 8, 24, 0, 0),
            analyzedAt = LocalDateTime.of(2026, 8, 24, 7, 0),
            body = "今日は落ち着いた記録が多めでした。",
          ),
        ),
      ),
      canRunAnalysis = true,
      runState = AnalysisRunState.Idle,
      onShowMessage = {},
      onRunResultShown = {},
      onAnalyze = {},
    )
  }
}
