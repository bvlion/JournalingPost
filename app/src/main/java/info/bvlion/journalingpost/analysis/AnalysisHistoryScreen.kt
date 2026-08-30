package info.bvlion.journalingpost.analysis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import info.bvlion.journalingpost.ui.ScreenTopAppBar
import info.bvlion.journalingpost.ui.theme.JournalingPostTheme
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// 期間は時間帯まで指定され得るため(#38 / #40)、日付だけでなく時刻も表示する。
private val analysisDateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/M/d HH:mm", Locale.JAPAN)

/**
 * 解析履歴の遷移先。#37で保存したAnalysisResultを新しい順の一覧で表示する。検索・日付
 * ナビゲーション・filter等は持たない最小表示とする。
 */
@Composable
fun AnalysisHistoryScreen(uiState: AnalysisHistoryUiState) {
  Column(modifier = Modifier.fillMaxSize()) {
    ScreenTopAppBar(title = "解析履歴")

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
            periodEnd = LocalDateTime.of(2026, 8, 30, 0, 0),
            analyzedAt = LocalDateTime.of(2026, 8, 30, 7, 0),
            body = "今週は落ち着いた記録が多めでした。",
          ),
        ),
      ),
    )
  }
}
