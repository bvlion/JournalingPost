package info.bvlion.journalingpost.journal.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import info.bvlion.journalingpost.R
import info.bvlion.journalingpost.ui.EventEffect
import info.bvlion.journalingpost.ui.fixedTopRegionBackgroundColor
import info.bvlion.journalingpost.ui.theme.HistoryReadingTextStyle
import info.bvlion.journalingpost.ui.theme.JournalingPostTheme
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow

// 履歴が蓄積すると何年の記録か分からなくなるため、日付表示には年も含める。
private val historyDateFormatter = DateTimeFormatter.ofPattern("yyyy/M/d", Locale.JAPAN)
private val historyTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.JAPAN)

/**
 * 記録履歴の画面。1日分だけを表示し、日付ナビゲーション行・日付指定・左右スワイプで表示日を切り替える。
 * どの日へ移動できるかの判断はViewModelが持ち、この画面は操作を伝えるだけにする。
 *
 * 下部NavigationBarが現在地(記録履歴)を示すため画面名の固定タイトルは持たない。ただし日付
 * ナビゲーションは画面タイトルではなく操作UIなので、status bar直下へ固定して常に見えるようにし、
 * 履歴コンテンツはその背後を通過してスクロールする。
 */
@Composable
fun JournalHistoryScreen(
  uiState: JournalHistoryUiState,
  deleteFailures: Flow<Unit>,
  onShowMessage: (String) -> Unit,
  onDelete: (Long) -> Unit,
  onPreviousDay: () -> Unit,
  onNextDay: () -> Unit,
  onToday: () -> Unit,
  onSelectDate: (LocalDate) -> Unit,
) {
  // 削除確認中のentryは、回転しても対象を見失わないようidだけを保持する。
  var pendingDeleteId by rememberSaveable { mutableStateOf<Long?>(null) }
  var showDateJump by rememberSaveable { mutableStateOf(false) }

  val deleteFailedMessage = stringResource(R.string.journal_history_delete_failed)
  EventEffect(deleteFailures) { onShowMessage(deleteFailedMessage) }

  Box(modifier = Modifier.fillMaxSize()) {
    when (uiState) {
      JournalHistoryUiState.Loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
      }

      is JournalHistoryUiState.Content -> {
        // 日付ナビゲーションはstatus bar直下へ固定し、履歴コンテンツはその背後を通過させる。
        // 初期表示でコンテンツ先頭がナビの裏へ隠れないよう、実測したナビ高さぶんだけ上を空ける。
        var dateNavigationHeight by remember { mutableStateOf(0.dp) }
        val density = LocalDensity.current

        JournalHistoryDayPager(
          uiState = uiState,
          topContentPadding = dateNavigationHeight,
          onSelectDate = onSelectDate,
          onDeleteRequest = { pendingDeleteId = it.id },
        )
        // 記録が1件も無い全体空状態では移動できる日が今日だけで操作対象も無いため、日付ナビは出さず
        // 中央の案内だけにする。表示中の日だけ記録が無い場合(他の日に記録あり)はナビを残す。
        if (uiState.hasAnyEntry) {
          JournalHistoryDateNavigation(
            uiState = uiState,
            onPreviousDay = onPreviousDay,
            onNextDay = onNextDay,
            onToday = onToday,
            onDateJumpRequest = { showDateJump = true },
            modifier = Modifier
              .align(Alignment.TopCenter)
              .onGloballyPositioned { dateNavigationHeight = with(density) { it.size.height.toDp() } },
          )
        }
      }
    }
  }

  val content = uiState as? JournalHistoryUiState.Content
  if (content != null && showDateJump) {
    JournalHistoryDateJumpDialog(
      selectedDate = content.selectedDate,
      earliestDate = content.earliestDate,
      today = content.today,
      onSelect = {
        showDateJump = false
        onSelectDate(it)
      },
      onDismiss = { showDateJump = false },
    )
  }

  val pendingItem = content?.itemsByDate?.values
    ?.firstNotNullOfOrNull { items -> items.firstOrNull { it.id == pendingDeleteId } }
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
private fun JournalHistoryDateNavigation(
  uiState: JournalHistoryUiState.Content,
  onPreviousDay: () -> Unit,
  onNextDay: () -> Unit,
  onToday: () -> Unit,
  onDateJumpRequest: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val selectedDateText = uiState.selectedDate.format(historyDateFormatter)
  val dateJumpDescription = stringResource(R.string.journal_history_date_jump_description, selectedDateText)

  // 背景はstatus bar領域まで広げ、上端保護と地続きに見せる。中身だけをstatus barの下へ寄せる。
  Row(
    modifier = modifier
      .fillMaxWidth()
      .background(fixedTopRegionBackgroundColor())
      .statusBarsPadding()
      .padding(horizontal = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    IconButton(onClick = onPreviousDay, enabled = !uiState.isEarliestDate) {
      Icon(
        painter = painterResource(R.drawable.ic_chevron_left),
        contentDescription = stringResource(R.string.journal_history_previous_day),
      )
    }
    // 表示中の日付そのものを日付指定の入口にして、専用のボタンを増やさない。
    TextButton(
      onClick = onDateJumpRequest,
      modifier = Modifier.weight(1f).semantics { contentDescription = dateJumpDescription },
    ) {
      Text(text = selectedDateText, style = MaterialTheme.typography.titleMedium)
    }
    IconButton(onClick = onNextDay, enabled = !uiState.isToday) {
      Icon(
        painter = painterResource(R.drawable.ic_chevron_right),
        contentDescription = stringResource(R.string.journal_history_next_day),
      )
    }
    TextButton(onClick = onToday, enabled = !uiState.isToday) {
      Text(stringResource(R.string.journal_history_today))
    }
  }
}

/**
 * 1ページ=1日のHorizontalPagerで日付を送る。ドラッグ追従・fling・スナップ・縦スクロールとの調停は
 * Pagerの標準挙動へ任せ、この画面では独自のスワイプ判定を持たない。
 *
 * ページ番号は[JournalHistoryUiState.Content.earliestDate]を0とした相対番号で、最後のページが今日。
 * Pagerの両端がそのまま移動できる範囲の制約になる。[earliestDate]は削除で動くことがあるため、
 * ページ番号の意味が変わった場合はLaunchedEffect(selectedPage)がPagerの位置を追従させる。
 */
@Composable
private fun JournalHistoryDayPager(
  uiState: JournalHistoryUiState.Content,
  topContentPadding: Dp,
  onSelectDate: (LocalDate) -> Unit,
  onDeleteRequest: (JournalHistoryItem) -> Unit,
) {
  val pagerState = rememberPagerState(
    initialPage = historyPageOf(uiState.selectedDate, uiState.earliestDate),
    pageCount = { historyPageCount(uiState.earliestDate, uiState.today) },
  )
  val selectedPage = historyPageOf(uiState.selectedDate, uiState.earliestDate)

  // 日付ナビゲーション行や日付指定で表示日が変わったとき、または下限が動いてページ番号の意味が
  // 変わったときに、Pagerを同じ日へ寄せる。
  LaunchedEffect(selectedPage) {
    if (pagerState.currentPage != selectedPage) pagerState.animateScrollToPage(selectedPage)
  }
  // LaunchedEffect(pagerState)はpagerStateが同一インスタンスである限り再起動されないため、
  // ブロック内で参照するuiState由来の値はrememberUpdatedStateで最新化しておく。
  val currentEarliestDate by rememberUpdatedState(uiState.earliestDate)
  val currentOnSelectDate by rememberUpdatedState(onSelectDate)
  // スワイプで落ち着いたページを表示日として確定する。購読直後の1件はPagerの現在値が流れてくるだけで
  // スワイプ結果ではない。これを表示日として扱うと、process再生成でPagerの位置だけが復元されたときに
  // 「初期表示は今日」がPager側の値で上書きされる。
  LaunchedEffect(pagerState) {
    snapshotFlow { pagerState.settledPage }
      .drop(1)
      .collect { currentOnSelectDate(historyDateOfPage(it, currentEarliestDate)) }
  }

  HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
    val items = uiState.itemsOn(historyDateOfPage(page, uiState.earliestDate))
    if (items.isEmpty()) {
      Box(
        modifier = Modifier.fillMaxSize().padding(top = topContentPadding),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = stringResource(
            if (uiState.hasAnyEntry) R.string.journal_history_day_empty else R.string.journal_history_empty,
          ),
          style = HistoryReadingTextStyle,
        )
      }
    } else {
      // 履歴の先頭は固定日付ナビの下から始め、スクロールでその背後を通過させる。
      LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = topContentPadding),
      ) {
        items(items, key = { it.id }) { item ->
          JournalHistoryRow(item = item, onDeleteRequest = { onDeleteRequest(item) })
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JournalHistoryDateJumpDialog(
  selectedDate: LocalDate,
  earliestDate: LocalDate,
  today: LocalDate,
  onSelect: (LocalDate) -> Unit,
  onDismiss: () -> Unit,
) {
  // 日付指定でも前日/翌日ボタン・スワイプと同じ範囲だけを選べるようにする。
  val selectableDates = remember(earliestDate, today) {
    object : SelectableDates {
      override fun isSelectableDate(utcTimeMillis: Long): Boolean =
        isSelectableHistoryDate(utcTimeMillis, earliestDate, today)

      override fun isSelectableYear(year: Int): Boolean = isSelectableHistoryYear(year, earliestDate, today)
    }
  }
  val datePickerState = rememberDatePickerState(
    initialSelectedDateMillis = selectedDate.toDatePickerMillis(),
    selectableDates = selectableDates,
  )
  val pickedDate = datePickerState.selectedDateMillis?.toDatePickerDate()

  DatePickerDialog(
    onDismissRequest = onDismiss,
    confirmButton = {
      TextButton(
        enabled = pickedDate != null,
        onClick = { if (pickedDate != null) onSelect(pickedDate) },
      ) {
        Text(stringResource(R.string.journal_history_date_jump_confirm))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.action_cancel))
      }
    },
  ) {
    DatePicker(state = datePickerState)
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
    title = { Text(stringResource(R.string.journal_history_delete_confirm_title)) },
    text = {
      Column {
        Text(
          text = buildString {
            append(item.date.format(historyDateFormatter))
            append(" ")
            append(item.time.format(historyTimeFormatter))
            listOfNotNull(item.moodEmoji, item.moodLabel)
              .filter { it.isNotBlank() }
              .joinToString(" ")
              .takeIf { it.isNotEmpty() }
              ?.let {
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
          text = stringResource(R.string.journal_history_delete_confirm_body),
          style = MaterialTheme.typography.bodySmall,
          modifier = Modifier.padding(top = 8.dp),
        )
      }
    },
    confirmButton = {
      TextButton(onClick = onConfirm) {
        Text(text = stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.action_cancel))
      }
    },
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
          style = HistoryReadingTextStyle,
        )
        val moodText = listOfNotNull(item.moodEmoji, item.moodLabel)
          .filter { it.isNotBlank() }
          .joinToString(" ")
        if (moodText.isNotEmpty()) {
          Text(
            text = moodText,
            style = HistoryReadingTextStyle,
            modifier = Modifier.padding(start = 8.dp),
          )
        }
      }
      if (item.note != null) {
        Text(
          text = item.note,
          style = HistoryReadingTextStyle,
          modifier = Modifier.padding(top = 2.dp),
        )
      }
    }
    // 一覧に同じ「削除」が並ぶため、読み上げでは対象の時刻まで分かるようにする。
    val rowDescription = stringResource(
      R.string.journal_history_delete_row_description,
      item.time.format(historyTimeFormatter),
    )
    TextButton(
      onClick = onDeleteRequest,
      modifier = Modifier.semantics { contentDescription = rowDescription },
    ) {
      Text(stringResource(R.string.action_delete))
    }
  }
}

@Preview(showBackground = true)
@Composable
fun JournalHistoryScreenPreview() {
  JournalingPostTheme {
    JournalHistoryScreen(
      uiState = JournalHistoryUiState.Content(
        selectedDate = LocalDate.of(2026, 8, 29),
        today = LocalDate.of(2026, 8, 30),
        earliestDate = LocalDate.of(2026, 8, 20),
        itemsByDate = mapOf(
          LocalDate.of(2026, 8, 29) to listOf(
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
      deleteFailures = emptyFlow(),
      onShowMessage = {},
      onDelete = {},
      onPreviousDay = {},
      onNextDay = {},
      onToday = {},
      onSelectDate = {},
    )
  }
}
