package info.bvlion.journalingpost.journal.history

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import info.bvlion.journalingpost.ui.ScreenTopAppBar
import info.bvlion.journalingpost.ui.theme.JournalingPostTheme
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val historyDateFormatter = DateTimeFormatter.ofPattern("M/d", Locale.JAPAN)
private val historyTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.JAPAN)

@Composable
fun JournalHistoryScreen(
  uiState: JournalHistoryUiState,
  deleteFailed: Boolean,
  onDelete: (Long) -> Unit,
  onBack: () -> Unit,
) {
  // 削除確認中のentryは、回転しても対象を見失わないようidだけを保持する。
  var pendingDeleteId by rememberSaveable { mutableStateOf<Long?>(null) }

  Column(modifier = Modifier.fillMaxSize()) {
    ScreenTopAppBar(title = "履歴", onBack = onBack)

    if (deleteFailed) {
      Text(
        text = "記録を削除できませんでした",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
      )
    }

    when (uiState) {
      JournalHistoryUiState.Loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
      }

      JournalHistoryUiState.Empty -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
          text = "まだ記録がありません",
          style = MaterialTheme.typography.bodyMedium,
        )
      }

      is JournalHistoryUiState.Content -> LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        uiState.groups.forEach { group ->
          item(key = "date-${group.date}") {
            JournalHistoryDateHeader(group.date)
          }
          items(group.items, key = { it.id }) { item ->
            JournalHistoryRow(item = item, onDeleteRequest = { pendingDeleteId = item.id })
          }
        }
      }
    }
  }

  val pendingItem = (uiState as? JournalHistoryUiState.Content)
    ?.groups
    ?.firstNotNullOfOrNull { group -> group.items.firstOrNull { it.id == pendingDeleteId } }
  if (pendingItem != null) {
    JournalHistoryDeleteConfirmDialog(
      item = pendingItem,
      onConfirm = {
        pendingDeleteId = null
        onDelete(pendingItem.id)
      },
      onDismiss = { pendingDeleteId = null },
    )
  }
}

@Composable
private fun JournalHistoryDeleteConfirmDialog(
  item: JournalHistoryItem,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("この記録を削除しますか？") },
    text = {
      Column {
        Text(
          text = buildString {
            append(item.date.format(historyDateFormatter))
            append(" ")
            append(item.time.format(historyTimeFormatter))
            item.moodLabel?.let {
              append(" ")
              append(it)
            }
          },
          style = MaterialTheme.typography.bodyMedium,
        )
        if (item.note != null) {
          Text(
            text = item.note,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
          )
        }
        Text(
          text = "削除すると元に戻せません。",
          style = MaterialTheme.typography.bodySmall,
          modifier = Modifier.padding(top = 8.dp),
        )
      }
    },
    confirmButton = {
      TextButton(onClick = onConfirm) {
        Text(text = "削除", color = MaterialTheme.colorScheme.error)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("キャンセル")
      }
    },
  )
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
private fun JournalHistoryRow(
  item: JournalHistoryItem,
  onDeleteRequest: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
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
    // 一覧に同じ「削除」が並ぶため、読み上げでは対象の時刻まで分かるようにする。
    TextButton(
      onClick = onDeleteRequest,
      modifier = Modifier.semantics {
        contentDescription = "${item.time.format(historyTimeFormatter)}の記録を削除"
      },
    ) {
      Text("削除")
    }
  }
}

@Preview(showBackground = true)
@Composable
fun JournalHistoryScreenPreview() {
  JournalingPostTheme {
    JournalHistoryScreen(
      uiState = JournalHistoryUiState.Content(
        listOf(
          JournalHistoryGroup(
            date = LocalDate.of(2026, 8, 29),
            items = listOf(
              JournalHistoryItem(
                id = 1,
                date = LocalDate.of(2026, 8, 29),
                time = LocalTime.of(9, 30),
                moodEmoji = "🙂",
                moodLabel = "ふつう",
                note = "朝の記録",
              ),
            ),
          ),
        ),
      ),
      deleteFailed = false,
      onDelete = {},
      onBack = {},
    )
  }
}
