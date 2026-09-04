package info.bvlion.journalingpost.analysis

import android.content.res.Resources
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import info.bvlion.journalingpost.AnalysisRunResult
import info.bvlion.journalingpost.R
import info.bvlion.journalingpost.ui.EventEffect
import info.bvlion.journalingpost.ui.TopLevelScreen
import info.bvlion.journalingpost.ui.theme.HistoryReadingTextStyle
import info.bvlion.journalingpost.ui.theme.JournalingPostTheme
import info.bvlion.journalingpost.ui.topLevelListContentPadding
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

// 期間は時間帯まで指定され得るため(#40)、日付だけでなく時刻も表示する。
private val analysisDateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/M/d HH:mm", Locale.JAPAN)

private val analysisDayFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.JAPAN)

/**
 * 解析履歴の遷移先。#37で保存したAnalysisResultを新しい順の一覧で表示する。
 *
 * 下部NavigationBarが現在地(解析履歴)を示すため画面名の固定タイトルは持たず、コンテンツを
 * edge-to-edgeでstatus barの下まで流す。「解析する」導線も画面固有の操作コンテンツとして、
 * 解析結果一覧と一緒にスクロールさせる。
 *
 * 解析先(Custom WebhookまたはHosted)が有効な場合([canRunAnalysis])だけ「解析する」導線を出し、
 * 選択可能な日([selectableDays])から1日選んで手動解析を実行できる。実行結果はSnackbarで伝える。
 * 対象日の記録が0件かどうかは実行後の解析処理側でも判定する(防御的なエラーハンドリングは残す)。
 *
 * 解析に成功して新しいAnalysisResultが一覧の先頭へ追加されたら、一覧を先頭へスクロールして、
 * 生成された結果をそのまま確認できる状態にする。
 */
@Composable
fun AnalysisHistoryScreen(
  uiState: AnalysisHistoryUiState,
  canRunAnalysis: Boolean,
  isRunning: Boolean,
  selectableDays: Set<LocalDate>,
  runResults: Flow<AnalysisRunResult>,
  onShowMessage: (String) -> Unit,
  onAnalyze: (LocalDate) -> Unit,
) {
  val resources = LocalResources.current
  val completedMessage = stringResource(R.string.analysis_completed)
  val listState = rememberLazyListState()

  // 選べる日が無いときは「解析する」を出さない。Hostedでは当日と解析済みの日を除くと対象が
  // 無くなることがある(その場合は自動解析か翌日以降に委ねる)。導線を出す場合は一覧の先頭itemが
  // 導線になり、生成結果はその次のindexになる。
  val showTrigger = (canRunAnalysis && selectableDays.isNotEmpty()) || isRunning

  // 保存した結果はRoomのFlow経由で一覧へ反映される。反映のタイミングは成功通知の前後どちらもあり得る
  // ため、保存した行のidが一覧の先頭に来たことを条件にして、そこで初めて生成結果itemへ寄せる。
  val firstItemId = (uiState as? AnalysisHistoryUiState.Content)?.items?.firstOrNull()?.id
  var scrollToResultId by remember { mutableStateOf<Long?>(null) }
  EventEffect(runResults) { result ->
    when (result) {
      is AnalysisRunResult.Succeeded -> {
        onShowMessage(completedMessage)
        scrollToResultId = result.savedResultId
      }

      is AnalysisRunResult.Failed -> onShowMessage(resources.failureMessage(result))
    }
  }
  LaunchedEffect(scrollToResultId, firstItemId, showTrigger) {
    if (scrollToResultId != null && firstItemId == scrollToResultId) {
      // 生成結果は一覧の先頭。「解析する」導線を出しているときはその1つ下。
      listState.animateScrollToItem(if (showTrigger) 1 else 0)
      scrollToResultId = null
    }
  }

  TopLevelScreen {
    when (uiState) {
      AnalysisHistoryUiState.Loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
      }

      AnalysisHistoryUiState.Empty -> Column(
        modifier = Modifier.fillMaxSize().padding(topLevelListContentPadding()),
      ) {
        // 解析先が有効なら結果が無くても「解析する」導線は残し、空状態メッセージは記録履歴と
        // 揃えて残りの領域の中央へ置く。
        if (showTrigger) {
          AnalysisTrigger(
            canRunAnalysis = canRunAnalysis,
            isRunning = isRunning,
            selectableDays = selectableDays,
            onAnalyze = onAnalyze,
          )
        }
        Box(
          modifier = Modifier.weight(1f).fillMaxWidth(),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = stringResource(R.string.analysis_history_empty),
            style = HistoryReadingTextStyle,
          )
        }
      }

      is AnalysisHistoryUiState.Content -> LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = topLevelListContentPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        if (showTrigger) {
          item(key = ANALYSIS_TRIGGER_ITEM_KEY) {
            AnalysisTrigger(
              canRunAnalysis = canRunAnalysis,
              isRunning = isRunning,
              selectableDays = selectableDays,
              onAnalyze = onAnalyze,
            )
          }
        }

        items(uiState.items, key = { it.id }) { item ->
          AnalysisHistoryCard(item)
        }
      }
    }
  }
}

private const val ANALYSIS_TRIGGER_ITEM_KEY = "analysis-trigger"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnalysisTrigger(
  canRunAnalysis: Boolean,
  isRunning: Boolean,
  selectableDays: Set<LocalDate>,
  onAnalyze: (LocalDate) -> Unit,
) {
  var showDatePicker by remember { mutableStateOf(false) }

  // 横方向の余白はLazyColumnのcontentPaddingが持つため、ここでは縦方向だけ空ける。
  Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
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
    // 選べる日だけを選択可能にし、初期選択は選べる日のうち直近の日にする。無ければ選択なしで開く。
    val selectableDates = remember(selectableDays) { analysisSelectableDates(selectableDays) }
    val datePickerState = rememberDatePickerState(
      initialSelectedDateMillis = selectableDays.maxOrNull()?.toUtcMillis(),
      selectableDates = selectableDates,
    )
    val pickedDay = datePickerState.selectedDateMillis?.toLocalDateFromUtc()
    // ダイアログを開いている間に自動解析が完了して選択中の日が対象外になることがあるため、
    // 最新の[selectableDays]に含まれるときだけ実行できるようにする(解析済みの日への重複実行を防ぐ)。
    val confirmableDay = pickedDay?.takeIf { it in selectableDays }

    fun close() {
      showDatePicker = false
    }

    DatePickerDialog(
      onDismissRequest = { close() },
      confirmButton = {
        TextButton(
          enabled = confirmableDay != null,
          onClick = {
            if (confirmableDay != null) onAnalyze(confirmableDay)
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

/** 対象日をSnackbar側で持ち直さず、失敗結果に含まれる日をそのまま文言へ入れる。 */
private fun Resources.failureMessage(failed: AnalysisRunResult.Failed): String = when (failed.failure) {
  PeriodAnalysisOutcome.Failure.WEBHOOK_UNAVAILABLE -> getString(R.string.analysis_failure_webhook_unavailable)
  PeriodAnalysisOutcome.Failure.INTEGRATION_UNAVAILABLE ->
    getString(R.string.analysis_failure_integration_unavailable)

  PeriodAnalysisOutcome.Failure.NO_ENTRIES ->
    getString(R.string.analysis_failure_no_entries, failed.day.format(analysisDayFormatter))

  PeriodAnalysisOutcome.Failure.LOCAL_READ -> getString(R.string.analysis_failure_local_read)
  PeriodAnalysisOutcome.Failure.NETWORK -> getString(R.string.analysis_failure_network)
  PeriodAnalysisOutcome.Failure.SERVER_ERROR -> getString(R.string.analysis_failure_server_error)
  PeriodAnalysisOutcome.Failure.TEMPORARILY_UNAVAILABLE ->
    getString(R.string.analysis_failure_temporarily_unavailable)

  PeriodAnalysisOutcome.Failure.INVALID_RESPONSE -> getString(R.string.analysis_failure_invalid_response)
  null -> getString(R.string.analysis_failure_save)
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
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 2.dp),
    )
    Text(
      text = item.body,
      style = HistoryReadingTextStyle,
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
      isRunning = false,
      selectableDays = setOf(LocalDate.of(2026, 8, 23), LocalDate.of(2026, 8, 24)),
      runResults = emptyFlow(),
      onShowMessage = {},
      onAnalyze = {},
    )
  }
}
