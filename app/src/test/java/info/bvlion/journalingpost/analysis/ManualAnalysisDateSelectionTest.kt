package info.bvlion.journalingpost.analysis

import androidx.compose.material3.ExperimentalMaterial3Api
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalMaterial3Api::class)
class ManualAnalysisDateSelectionTest {
  private val recordedDays = setOf(LocalDate.of(2025, 12, 31), LocalDate.of(2026, 2, 10))
  private val selectableDates = recordedDaySelectableDates(recordedDays)

  private fun millisOf(date: LocalDate): Long =
    date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

  @Test
  fun `記録のある日は選択できる`() {
    assertTrue(selectableDates.isSelectableDate(millisOf(LocalDate.of(2025, 12, 31))))
    assertTrue(selectableDates.isSelectableDate(millisOf(LocalDate.of(2026, 2, 10))))
  }

  @Test
  fun `記録の無い日は選択できない`() {
    assertFalse(selectableDates.isSelectableDate(millisOf(LocalDate.of(2026, 1, 15))))
  }

  @Test
  fun `選択できる年は記録のある日の年の範囲に収まる`() {
    assertTrue(selectableDates.isSelectableYear(2025))
    assertTrue(selectableDates.isSelectableYear(2026))
    assertFalse(selectableDates.isSelectableYear(2024))
    assertFalse(selectableDates.isSelectableYear(2027))
  }

  @Test
  fun `記録が無ければどの日も年も選択できない`() {
    val empty = recordedDaySelectableDates(emptySet())

    assertFalse(empty.isSelectableDate(millisOf(LocalDate.of(2026, 2, 10))))
    assertFalse(empty.isSelectableYear(2026))
  }
}
