package info.bvlion.journalingpost

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoAnalysisSchedulerTest {
  @Test
  fun `指定時刻が同じ日のまだ先ならその日の時刻まで待つ`() {
    val delay = nextRunDelay(
      from = Instant.parse("2026-09-01T00:00:00Z"),
      zoneId = ZoneOffset.UTC,
      timeOfDay = LocalTime.of(8, 0),
    )

    assertEquals(Duration.ofHours(8), delay)
  }

  @Test
  fun `指定時刻が既に過ぎていれば翌日の時刻まで待つ`() {
    val delay = nextRunDelay(
      from = Instant.parse("2026-09-01T10:00:00Z"),
      zoneId = ZoneOffset.UTC,
      timeOfDay = LocalTime.of(8, 0),
    )

    assertEquals(Duration.ofHours(22), delay)
  }

  @Test
  fun `指定時刻ちょうどのときは翌日にする`() {
    val delay = nextRunDelay(
      from = Instant.parse("2026-09-01T08:00:00Z"),
      zoneId = ZoneOffset.UTC,
      timeOfDay = LocalTime.of(8, 0),
    )

    assertEquals(Duration.ofDays(1), delay)
  }

  @Test
  fun `端末timezoneでの壁時計時刻で待ち時間を計算する`() {
    // 2026-09-01T00:00:00Z は Asia/Tokyo では 09:00。次の 8:00 は翌日で 23 時間後。
    val delay = nextRunDelay(
      from = Instant.parse("2026-09-01T00:00:00Z"),
      zoneId = ZoneId.of("Asia/Tokyo"),
      timeOfDay = LocalTime.of(8, 0),
    )

    assertEquals(Duration.ofHours(23), delay)
  }
}
