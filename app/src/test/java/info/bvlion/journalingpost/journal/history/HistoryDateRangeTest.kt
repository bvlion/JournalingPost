package info.bvlion.journalingpost.journal.history

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryDateRangeTest {
  private val today = LocalDate.of(2026, 8, 26)
  private val earliestDate = LocalDate.of(2026, 8, 20)

  @Test
  fun `ページ番号と日付は相互に変換できる`() {
    val date = LocalDate.of(2026, 8, 23)

    assertEquals(date, historyDateOfPage(historyPageOf(date, earliestDate), earliestDate))
  }

  @Test
  fun `最古の記録日がページ番号0になる`() {
    assertEquals(0, historyPageOf(earliestDate, earliestDate))
    assertEquals(earliestDate, historyDateOfPage(0, earliestDate))
  }

  @Test
  fun `隣り合う日は隣り合うページになる`() {
    val page = historyPageOf(today, earliestDate)

    assertEquals(today.minusDays(1), historyDateOfPage(page - 1, earliestDate))
    assertEquals(today.plusDays(1), historyDateOfPage(page + 1, earliestDate))
  }

  @Test
  fun `最後のページは今日になる`() {
    assertEquals(today, historyDateOfPage(historyPageCount(earliestDate, today) - 1, earliestDate))
  }

  @Test
  fun `記録が無く下限が今日と同じ場合はページ数が1になる`() {
    assertEquals(1, historyPageCount(today, today))
  }

  @Test
  fun `範囲内の日はそのまま保たれる`() {
    assertEquals(today, coerceToHistoryRange(today, earliestDate, today))
    assertEquals(earliestDate, coerceToHistoryRange(earliestDate, earliestDate, today))
    val middle = LocalDate.of(2026, 8, 23)
    assertEquals(middle, coerceToHistoryRange(middle, earliestDate, today))
  }

  @Test
  fun `未来日は今日までへ丸められる`() {
    assertEquals(today, coerceToHistoryRange(today.plusDays(1), earliestDate, today))
    assertEquals(today, coerceToHistoryRange(LocalDate.of(2030, 1, 1), earliestDate, today))
  }

  @Test
  fun `最古の記録日より前の日は最古の記録日へ丸められる`() {
    assertEquals(earliestDate, coerceToHistoryRange(earliestDate.minusDays(1), earliestDate, today))
    assertEquals(earliestDate, coerceToHistoryRange(LocalDate.of(2000, 1, 1), earliestDate, today))
  }

  @Test
  fun `LocalDateとDatePickerのmillisは相互に変換できる`() {
    val date = LocalDate.of(2026, 8, 26)

    assertEquals(date, date.toDatePickerMillis().toDatePickerDate())
  }

  @Test
  fun `今日と最古の記録日はどちらも選択できる`() {
    assertTrue(isSelectableHistoryDate(today.toDatePickerMillis(), earliestDate, today))
    assertTrue(isSelectableHistoryDate(earliestDate.toDatePickerMillis(), earliestDate, today))
  }

  @Test
  fun `最古の記録日と今日の間の日は選択できる`() {
    val middle = LocalDate.of(2026, 8, 23)
    assertTrue(isSelectableHistoryDate(middle.toDatePickerMillis(), earliestDate, today))
  }

  @Test
  fun `未来日は選択できない`() {
    assertFalse(isSelectableHistoryDate(today.plusDays(1).toDatePickerMillis(), earliestDate, today))
    assertFalse(isSelectableHistoryDate(LocalDate.of(2030, 1, 1).toDatePickerMillis(), earliestDate, today))
  }

  @Test
  fun `最古の記録日より前の日は選択できない`() {
    assertFalse(isSelectableHistoryDate(earliestDate.minusDays(1).toDatePickerMillis(), earliestDate, today))
  }

  @Test
  fun `記録が1件も無い場合は今日だけが選択できる`() {
    assertTrue(isSelectableHistoryDate(today.toDatePickerMillis(), today, today))
    assertFalse(isSelectableHistoryDate(today.minusDays(1).toDatePickerMillis(), today, today))
  }

  @Test
  fun `年の選択は最古の記録日から今日までに制限される`() {
    assertTrue(isSelectableHistoryYear(earliestDate.year, earliestDate, today))
    assertTrue(isSelectableHistoryYear(today.year, earliestDate, today))
    assertFalse(isSelectableHistoryYear(today.year + 1, earliestDate, today))
  }

  @Test
  fun `最古の記録日と今日が別年をまたぐ場合その間の年も選択できる`() {
    val earliestLastYear = LocalDate.of(2025, 12, 1)

    assertTrue(isSelectableHistoryYear(2025, earliestLastYear, today))
    assertTrue(isSelectableHistoryYear(2026, earliestLastYear, today))
    assertFalse(isSelectableHistoryYear(2024, earliestLastYear, today))
  }
}
