package info.bvlion.journalingpost.journal.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val historyDateFormatter = DateTimeFormatter.ofPattern("M/d", Locale.JAPAN)
private val historyTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.JAPAN)

/** 保存済みJournalEntryを日付単位でグループ化して表示する履歴画面。 */
@Composable
fun JournalHistoryScreen(
  groups: List<JournalHistoryGroup>,
  onBack: () -> Unit,
) {
  Column(modifier = Modifier.fillMaxSize()) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      TextButton(onClick = onBack) {
        Text("戻る")
      }
      Text(
        text = "履歴",
        style = MaterialTheme.typography.titleMedium,
      )
    }

    if (groups.isEmpty()) {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
          text = "まだ記録がありません",
          style = MaterialTheme.typography.bodyMedium,
        )
      }
    } else {
      LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        groups.forEach { group ->
          item(key = "date-${group.date}") {
            JournalHistoryDateHeader(group.date)
          }
          items(group.items, key = { it.id }) { item ->
            JournalHistoryRow(item)
          }
        }
      }
    }
  }
}

@Composable
private fun JournalHistoryDateHeader(date: LocalDate) {
  Text(
    text = date.format(historyDateFormatter),
    style = MaterialTheme.typography.titleSmall,
    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
  )
}

@Composable
private fun JournalHistoryRow(item: JournalHistoryItem) {
  Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = item.time.format(historyTimeFormatter),
        style = MaterialTheme.typography.bodyMedium,
      )
      if (item.moodEmoji != null) {
        Text(
          text = item.moodEmoji,
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.padding(start = 8.dp)
            .semantics { item.moodLabel?.let { contentDescription = it } },
        )
      }
    }
    if (item.note != null) {
      Text(
        text = item.note,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 2.dp),
      )
    }
  }
}
