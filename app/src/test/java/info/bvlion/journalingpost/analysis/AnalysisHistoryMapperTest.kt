package info.bvlion.journalingpost.analysis

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisHistoryMapperTest {
  private val utc = ZoneOffset.UTC

  private fun result(
    id: Long,
    analyzedAt: Instant,
    periodStart: Instant = Instant.parse("2026-08-01T00:00:00Z"),
    periodEnd: Instant = Instant.parse("2026-08-07T00:00:00Z"),
    body: String = "本文",
  ) = AnalysisResult(
    id = id,
    periodStart = periodStart,
    periodEnd = periodEnd,
    analyzedAt = analyzedAt,
    body = body,
  )

  @Test
  fun `空リストの場合は空になる`() {
    assertTrue(emptyList<AnalysisResult>().toAnalysisHistoryItems(utc).isEmpty())
  }

  @Test
  fun `解析日時の新しい順に並ぶ`() {
    val items = listOf(
      result(id = 1, analyzedAt = Instant.parse("2026-08-07T07:00:00Z"), body = "old"),
      result(id = 2, analyzedAt = Instant.parse("2026-08-08T07:00:00Z"), body = "new"),
    ).toAnalysisHistoryItems(utc)

    assertEquals(listOf("new", "old"), items.map { it.body })
  }

  @Test
  fun `解析日時が同一の場合は入力順によらずid降順で並ぶ`() {
    val tied = Instant.parse("2026-08-08T07:00:00Z")
    val items = listOf(
      result(id = 5, analyzedAt = tied, body = "fifth"),
      result(id = 7, analyzedAt = tied, body = "seventh"),
      result(id = 6, analyzedAt = tied, body = "sixth"),
    ).toAnalysisHistoryItems(utc)

    assertEquals(listOf("seventh", "sixth", "fifth"), items.map { it.body })
  }

  @Test
  fun `対象期間と解析日時は指定したタイムゾーンのローカル日時へ変換される`() {
    val items = listOf(
      result(
        id = 1,
        periodStart = Instant.parse("2026-08-25T15:00:00Z"),
        periodEnd = Instant.parse("2026-08-26T15:00:00Z"),
        analyzedAt = Instant.parse("2026-08-26T22:30:00Z"),
      ),
    ).toAnalysisHistoryItems(ZoneOffset.ofHours(9))

    val item = items.single()
    assertEquals(LocalDateTime.of(2026, 8, 26, 0, 0), item.periodStart)
    assertEquals(LocalDateTime.of(2026, 8, 27, 0, 0), item.periodEnd)
    assertEquals(LocalDateTime.of(2026, 8, 27, 7, 30), item.analyzedAt)
  }

  @Test
  fun `結果本文はそのまま保持される`() {
    val items = listOf(
      result(id = 1, analyzedAt = Instant.parse("2026-08-08T07:00:00Z"), body = "落ち着いた一週間でした"),
    ).toAnalysisHistoryItems(utc)

    assertEquals("落ち着いた一週間でした", items.single().body)
  }
}
