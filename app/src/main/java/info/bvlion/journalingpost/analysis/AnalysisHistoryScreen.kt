package info.bvlion.journalingpost.analysis

import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import info.bvlion.journalingpost.AnalysisRunState
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

/**
 * 解析履歴の遷移先。#37で保存したAnalysisResultを新しい順の一覧で表示する。
 *
 * Custom Webhookが解析先として有効な場合([canRunAnalysis])だけ、上部に「解析する」導線を出し、
 * 対象日を1日選んで手動解析を実行できる。一覧の主目的は振り返りのため、導線は最小限に留める。
 */
@Composable
fun AnalysisHistoryScreen(
  uiState: AnalysisHistoryUiState,
  canRunAnalysis: Boolean,
  runState: AnalysisRunState,
  onAnalyze: (LocalDate) -> Unit,
  onRunResultShown: () -> Unit,
) {
  val context = LocalContext.current
  LaunchedEffect(runState) {
    if (runState is AnalysisRunState.Succeeded) {
      Toast.makeText(context.applicationContext, "解析結果を保存しました", Toast.LENGTH_SHORT).show()
      onRunResultShown()
    }
  }

  Column(modifier = Modifier.fillMaxSize()) {
    ScreenTopAppBar(title = "解析履歴")

    if (canRunAnalysis) {
      AnalysisTrigger(runState = runState, onAnalyze = onAnalyze)
    }

    when (uiState) {
      AnalysisHistoryUiState.Loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
      }

      AnalysisHistoryUiState.Empty -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
          text = "まだ解析結果がありません",
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
  runState: AnalysisRunState,
  onAnalyze: (LocalDate) -> Unit,
) {
  var showDatePicker by remember { mutableStateOf(false) }

  Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
    if (runState is AnalysisRunState.Running) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(
          text = "解析中…",
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.padding(start = 8.dp),
        )
      }
    } else {
      TextButton(onClick = { showDatePicker = true }, contentPadding = PaddingValues(0.dp)) {
        Text("解析する")
      }
    }

    if (runState is AnalysisRunState.Failed) {
      Text(
        text = runState.toMessage(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
      )
    }
  }

  if (showDatePicker) {
    val zoneId = remember { ZoneId.systemDefault() }
    val datePickerState = rememberDatePickerState(
      initialSelectedDateMillis = LocalDate.now(zoneId).toUtcMillis(),
    )
    DatePickerDialog(
      onDismissRequest = { showDatePicker = false },
      confirmButton = {
        TextButton(
          onClick = {
            datePickerState.selectedDateMillis?.let { millis ->
              onAnalyze(millis.toLocalDateFromUtc())
            }
            showDatePicker = false
          },
        ) {
          Text("解析する")
        }
      },
      dismissButton = {
        TextButton(onClick = { showDatePicker = false }) {
          Text("キャンセル")
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

private fun AnalysisRunState.Failed.toMessage(): String = when (failure) {
  PeriodAnalysisOutcome.Failure.WEBHOOK_UNAVAILABLE -> "Custom Webhookの設定を確認できませんでした"
  PeriodAnalysisOutcome.Failure.LOCAL_READ -> "対象期間の記録を読み込めませんでした"
  PeriodAnalysisOutcome.Failure.NETWORK -> "解析リクエストの送受信に失敗しました"
  PeriodAnalysisOutcome.Failure.SERVER_ERROR -> "解析先からエラーが返されました"
  PeriodAnalysisOutcome.Failure.INVALID_RESPONSE -> "解析結果を受け取れませんでした"
  null -> "解析結果を保存できませんでした"
}

@Composable
private fun AnalysisHistoryCard(item: AnalysisHistoryItem) {
  Column(modifier = Modifier.fillMaxWidth()) {
    Text(
      text = "対象期間: ${item.periodStart.format(analysisDateTimeFormatter)}" +
        " 〜 ${item.periodEnd.format(analysisDateTimeFormatter)}",
      style = MaterialTheme.typography.titleSmall,
    )
    Text(
      text = "解析日時: ${item.analyzedAt.format(analysisDateTimeFormatter)}",
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
      onAnalyze = {},
      onRunResultShown = {},
    )
  }
}
