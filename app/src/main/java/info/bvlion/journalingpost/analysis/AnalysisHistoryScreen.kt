package info.bvlion.journalingpost.analysis

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import info.bvlion.journalingpost.R
import info.bvlion.journalingpost.ui.HistoryEmptyMessage
import info.bvlion.journalingpost.ui.ScreenTopAppBar
import info.bvlion.journalingpost.ui.TopLevelScreen
import info.bvlion.journalingpost.ui.theme.HistoryReadingTextStyle
import info.bvlion.journalingpost.ui.theme.JournalingPostTheme
import info.bvlion.journalingpost.ui.topLevelListContentPadding
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// 期間は時間帯まで指定され得るため(#40)、日付だけでなく時刻も表示する。
private val analysisDateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/M/d HH:mm", Locale.JAPAN)

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
 */
@Composable
fun AnalysisHistoryScreen(
  uiState: AnalysisHistoryUiState,
  selectedItem: AnalysisHistoryItem?,
  listState: LazyListState = rememberLazyListState(),
  canRunAnalysis: Boolean,
  isRunning: Boolean,
  selectableDays: Set<LocalDate>,
  onAnalyze: (LocalDate) -> Unit,
  onDelete: (Long) -> Unit,
  onResultClick: (AnalysisHistoryItem) -> Unit,
  onBack: () -> Unit,
) {
  var pendingDeleteId by rememberSaveable { mutableStateOf<Long?>(null) }

  // 選べる日が無いときは「解析する」を出さない。Hostedでは当日と解析済みの日を除くと対象が
  // 無くなることがある(その場合は自動解析か翌日以降に委ねる)。
  val showTrigger = (canRunAnalysis && selectableDays.isNotEmpty()) || isRunning

  if (selectedItem != null) {
    Column(modifier = Modifier.fillMaxSize()) {
      ScreenTopAppBar(title = stringResource(R.string.tab_analysis_history), onBack = onBack)
      Column(
        modifier = Modifier
          .weight(1f)
          .verticalScroll(rememberScrollState())
          .padding(16.dp),
      ) {
        Text(
          text = stringResource(
            R.string.analysis_card_period,
            selectedItem.periodStart.format(analysisDateTimeFormatter),
            selectedItem.periodEnd.format(analysisDateTimeFormatter),
          ),
          style = MaterialTheme.typography.titleSmall,
        )
        Text(
          text = stringResource(
            R.string.analysis_card_analyzed_at,
            selectedItem.analyzedAt.format(analysisDateTimeFormatter),
          ),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 2.dp),
        )
        Text(
          text = selectedItem.displayBody,
          style = HistoryReadingTextStyle,
          modifier = Modifier.padding(top = 16.dp),
        )
        TextButton(
          onClick = { pendingDeleteId = selectedItem.id },
          modifier = Modifier.align(Alignment.End).padding(top = 16.dp),
        ) {
          Text(text = stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
        }
      }
    }
    if (pendingDeleteId == selectedItem.id) {
      AlertDialog(
        onDismissRequest = { pendingDeleteId = null },
        title = { Text(stringResource(R.string.analysis_delete_confirm_title)) },
        text = { Text(stringResource(R.string.analysis_delete_confirm_body)) },
        confirmButton = {
          TextButton(
            onClick = {
              pendingDeleteId = null
              onDelete(selectedItem.id)
              onBack()
            },
          ) {
            Text(text = stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
          }
        },
        dismissButton = {
          TextButton(onClick = { pendingDeleteId = null }) {
            Text(stringResource(R.string.action_cancel))
          }
        },
      )
    }
    return
  }

  TopLevelScreen {
    when (uiState) {
      AnalysisHistoryUiState.Loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
      }

      AnalysisHistoryUiState.Empty -> {
        // 案内は記録履歴の全体空状態と縦位置を揃えるため、画面領域全体の中央へ置く。
        HistoryEmptyMessage(stringResource(R.string.analysis_history_empty))
        // 解析先が有効なら結果が無くても「解析する」導線は残す。案内の縦位置が導線の有無で
        // 変わらないよう、導線は中央の案内とは重ねて上端へ置く。
        if (showTrigger) {
          Box(
            modifier = Modifier
              .align(Alignment.TopStart)
              .padding(topLevelListContentPadding()),
          ) {
            AnalysisTrigger(
              canRunAnalysis = canRunAnalysis,
              isRunning = isRunning,
              selectableDays = selectableDays,
              onAnalyze = onAnalyze,
            )
          }
        }
      }

      is AnalysisHistoryUiState.Content -> LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = topLevelListContentPadding(),
      ) {
        if (showTrigger) {
          item(key = ANALYSIS_TRIGGER_ITEM_KEY) {
            AnalysisTrigger(
              canRunAnalysis = canRunAnalysis,
              isRunning = isRunning,
              selectableDays = selectableDays,
              onAnalyze = onAnalyze,
              modifier = Modifier.padding(bottom = 16.dp),
            )
          }
        }

        itemsIndexed(uiState.items, key = { _, item -> item.id }) { index, item ->
          Column {
            AnalysisHistoryCard(item = item, onClick = { onResultClick(item) })
            if (index < uiState.items.lastIndex) {
              HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            }
          }
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
  modifier: Modifier = Modifier,
) {
  var showDatePicker by remember { mutableStateOf(false) }

  // 横方向の余白はLazyColumnのcontentPaddingが持つため、ここでは縦方向だけ空ける。
  Column(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
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

@Composable
private fun AnalysisHistoryCard(
  item: AnalysisHistoryItem,
  onClick: () -> Unit,
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick, role = Role.Button),
  ) {
    Text(
      text = stringResource(
        R.string.analysis_card_period,
        item.periodStart.format(analysisDateTimeFormatter),
        item.periodEnd.format(analysisDateTimeFormatter),
      ),
      style = MaterialTheme.typography.titleSmall,
    )
    Text(
      text = item.displayBody,
      style = HistoryReadingTextStyle,
      modifier = Modifier.padding(top = 8.dp),
      maxLines = 3,
      overflow = TextOverflow.Ellipsis,
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
      selectedItem = null,
      canRunAnalysis = true,
      isRunning = false,
      selectableDays = setOf(LocalDate.of(2026, 8, 23), LocalDate.of(2026, 8, 24)),
      onAnalyze = {},
      onDelete = {},
      onResultClick = {},
      onBack = {},
    )
  }
}
