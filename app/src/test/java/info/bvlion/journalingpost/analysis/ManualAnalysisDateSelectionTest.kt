package info.bvlion.journalingpost.analysis

import androidx.compose.material3.ExperimentalMaterial3Api
import info.bvlion.journalingpost.settings.AnalysisIntegration
import info.bvlion.journalingpost.time.CalendarDate
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalMaterial3Api::class)
class ManualAnalysisDateSelectionTest {
  private val selectableDays = setOf(LocalDate.of(2025, 12, 31), LocalDate.of(2026, 2, 10))
  private val selectableDates = analysisSelectableDates(selectableDays)

  private fun millisOf(date: LocalDate): Long =
    date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

  @Test
  fun `選べる日は選択できる`() {
    assertTrue(selectableDates.isSelectableDate(millisOf(LocalDate.of(2025, 12, 31))))
    assertTrue(selectableDates.isSelectableDate(millisOf(LocalDate.of(2026, 2, 10))))
  }

  @Test
  fun `選べない日は選択できない`() {
    assertFalse(selectableDates.isSelectableDate(millisOf(LocalDate.of(2026, 1, 15))))
  }

  @Test
  fun `選択できる年は選べる日の年の範囲に収まる`() {
    assertTrue(selectableDates.isSelectableYear(2025))
    assertTrue(selectableDates.isSelectableYear(2026))
    assertFalse(selectableDates.isSelectableYear(2024))
    assertFalse(selectableDates.isSelectableYear(2027))
  }

  @Test
  fun `選べる日が無ければどの日も年も選択できない`() {
    val empty = analysisSelectableDates(emptySet())

    assertFalse(empty.isSelectableDate(millisOf(LocalDate.of(2026, 2, 10))))
    assertFalse(empty.isSelectableYear(2026))
  }

  private val today = LocalDate.of(2026, 2, 12)
  private val recordedDays = setOf(
    LocalDate.of(2026, 2, 5),
    LocalDate.of(2026, 2, 6),
    LocalDate.of(2026, 2, 9),
    LocalDate.of(2026, 2, 10),
    LocalDate.of(2026, 2, 11),
    today,
  )

  @Test
  fun `Custom Webhookは記録のある日をすべて選べる`() {
    assertEquals(
      recordedDays,
      ManualAnalysisEligibility.Ready(
        integration = AnalysisIntegration.CUSTOM_WEBHOOK,
        recordedDays = recordedDays,
        hostedSuccessfulDays = setOf(LocalDate.of(2026, 2, 10)),
        calendarDate = CalendarDate(today, ZoneOffset.UTC),
        runningDay = null,
      ).selectableDays,
    )
  }

  @Test
  fun `Hostedは昨日から6日前までの未解析の記録日だけ選べる`() {
    assertEquals(
      setOf(LocalDate.of(2026, 2, 6), LocalDate.of(2026, 2, 9), LocalDate.of(2026, 2, 11)),
      ManualAnalysisEligibility.Ready(
        integration = AnalysisIntegration.HOSTED,
        recordedDays = recordedDays,
        hostedSuccessfulDays = setOf(LocalDate.of(2026, 2, 10)),
        calendarDate = CalendarDate(today, ZoneOffset.UTC),
        runningDay = null,
      ).selectableDays,
    )
  }

  @Test
  fun `Hostedで前日以前の記録がすべて解析済みなら選べる日は無い`() {
    assertTrue(
      ManualAnalysisEligibility.Ready(
        integration = AnalysisIntegration.HOSTED,
        recordedDays = setOf(LocalDate.of(2026, 2, 11), today),
        hostedSuccessfulDays = setOf(LocalDate.of(2026, 2, 11)),
        calendarDate = CalendarDate(today, ZoneOffset.UTC),
        runningDay = null,
      ).selectableDays.isEmpty(),
    )
  }
}
