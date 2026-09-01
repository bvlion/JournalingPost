package info.bvlion.journalingpost.journal.history

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryDateJumpTest {
  private val today = LocalDate.of(2026, 8, 26)

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
  fun `過去日は下限なく選択できる`() {
    assertTrue(isSelectableHistoryDate(today.minusDays(1).toDatePickerMillis(), today))
    assertTrue(isSelectableHistoryDate(LocalDate.of(1970, 1, 1).toDatePickerMillis(), today))
  }

  @Test
  fun `未来日は選択できない`() {
    assertFalse(isSelectableHistoryDate(today.plusDays(1).toDatePickerMillis(), today))
    assertFalse(isSelectableHistoryDate(LocalDate.of(2030, 1, 1).toDatePickerMillis(), today))
  }

  @Test
  fun `年の選択は今年までに制限される`() {
    assertTrue(isSelectableHistoryYear(2025, today))
    assertTrue(isSelectableHistoryYear(2026, today))
    assertFalse(isSelectableHistoryYear(2027, today))
  }
}
