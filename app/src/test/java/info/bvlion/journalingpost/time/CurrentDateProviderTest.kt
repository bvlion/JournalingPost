package info.bvlion.journalingpost.time

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class CurrentDateProviderTest {
  @Test
  fun `深夜0時の更新で同じタイムゾーンの今日が進む`() {
    var now = Instant.parse("2026-08-30T23:59:59Z")
    val provider = CurrentDateProvider(now = { now }, zoneId = { ZoneOffset.UTC })
    assertEquals(CalendarDate(LocalDate.of(2026, 8, 30), ZoneOffset.UTC), provider.state.value)
    now = Instant.parse("2026-08-31T00:00:00Z")
    assertEquals(CalendarDate(LocalDate.of(2026, 8, 31), ZoneOffset.UTC), provider.refresh())
    assertEquals(provider.refresh(), provider.state.value)
  }

  @Test
  fun `タイムゾーン変更で今日とタイムゾーンを一組で更新する`() {
    var zoneId: ZoneId = ZoneOffset.UTC
    val provider = CurrentDateProvider(now = { Instant.parse("2026-08-30T20:00:00Z") }, zoneId = { zoneId })
    zoneId = ZoneId.of("Asia/Tokyo")
    assertEquals(CalendarDate(LocalDate.of(2026, 8, 31), zoneId), provider.refresh())
    zoneId = ZoneOffset.UTC
    assertEquals(CalendarDate(LocalDate.of(2026, 8, 30), zoneId), provider.refresh())
  }

  @Test
  fun `端末時刻を前日に戻すと同じタイムゾーンでも今日を更新する`() {
    var now = Instant.parse("2026-08-31T10:00:00Z")
    val provider = CurrentDateProvider(now = { now }, zoneId = { ZoneOffset.UTC })
    now = Instant.parse("2026-08-30T23:00:00Z")
    provider.refresh()
    assertEquals(LocalDate.of(2026, 8, 30), provider.state.value.today)
  }
}
