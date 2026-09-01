package info.bvlion.journalingpost.journal.history

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryDateRangeTest {
  private val today = LocalDate.of(2026, 8, 26)

  @Test
  fun `ページ番号と日付は相互に変換できる`() {
    val date = LocalDate.of(2026, 8, 26)

    assertEquals(date, historyDateOfPage(historyPageOf(date)))
  }

  @Test
  fun `隣り合う日は隣り合うページになる`() {
    val page = historyPageOf(today)

    assertEquals(today.minusDays(1), historyDateOfPage(page - 1))
    assertEquals(today.plusDays(1), historyDateOfPage(page + 1))
  }

  @Test
  fun `最初のページは表示できる最も古い日になる`() {
    assertEquals(HISTORY_EARLIEST_DATE, historyDateOfPage(0))
    assertEquals(0, historyPageOf(HISTORY_EARLIEST_DATE))
  }

  @Test
  fun `最後のページは今日になる`() {
    assertEquals(today, historyDateOfPage(historyPageCount(today) - 1))
  }

  @Test
  fun `端末時刻が表示できる範囲より前でもページ数は1以上になる`() {
    assertTrue(historyPageCount(HISTORY_EARLIEST_DATE.minusYears(1)) >= 1)
  }

  @Test
  fun `範囲内の日はそのまま保たれる`() {
    assertEquals(today, coerceToHistoryRange(today, today))
    assertEquals(LocalDate.of(1950, 6, 1), coerceToHistoryRange(LocalDate.of(1950, 6, 1), today))
  }

  @Test
  fun `未来日は今日までへ丸められる`() {
    assertEquals(today, coerceToHistoryRange(today.plusDays(1), today))
    assertEquals(today, coerceToHistoryRange(LocalDate.of(2030, 1, 1), today))
  }

  @Test
  fun `表示できる範囲より前の日は最も古い日へ丸められる`() {
    assertEquals(HISTORY_EARLIEST_DATE, coerceToHistoryRange(HISTORY_EARLIEST_DATE.minusDays(1), today))
  }

  @Test
  fun `LocalDateとDatePickerのmillisは相互に変換できる`() {
    val date = LocalDate.of(2026, 8, 26)

    assertEquals(date, date.toDatePickerMillis().toDatePickerDate())
  }

  @Test
  fun `今日は選択できる`() {
    assertTrue(isSelectableHistoryDate(today.toDatePickerMillis(), today))
  }

  @Test
  fun `記録の有無によらず過去日は選択できる`() {
    assertTrue(isSelectableHistoryDate(today.minusDays(1).toDatePickerMillis(), today))
    assertTrue(isSelectableHistoryDate(LocalDate.of(1970, 1, 1).toDatePickerMillis(), today))
    assertTrue(isSelectableHistoryDate(HISTORY_EARLIEST_DATE.toDatePickerMillis(), today))
  }

  @Test
  fun `未来日は選択できない`() {
    assertFalse(isSelectableHistoryDate(today.plusDays(1).toDatePickerMillis(), today))
    assertFalse(isSelectableHistoryDate(LocalDate.of(2030, 1, 1).toDatePickerMillis(), today))
  }

  @Test
  fun `スワイプで辿れる範囲より前の日は選択できない`() {
    assertFalse(isSelectableHistoryDate(HISTORY_EARLIEST_DATE.minusDays(1).toDatePickerMillis(), today))
  }

  @Test
  fun `年の選択はスワイプで辿れる範囲に合わせて制限される`() {
    assertTrue(isSelectableHistoryYear(HISTORY_EARLIEST_DATE.year, today))
    assertTrue(isSelectableHistoryYear(2025, today))
    assertTrue(isSelectableHistoryYear(2026, today))
    assertFalse(isSelectableHistoryYear(HISTORY_EARLIEST_DATE.year - 1, today))
    assertFalse(isSelectableHistoryYear(2027, today))
  }
}
