package info.bvlion.journalingpost.journal.history

import info.bvlion.journalingpost.journal.JournalEntry
import info.bvlion.journalingpost.journal.JournalSource
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalHistoryMapperTest {
  private val utc = ZoneOffset.UTC

  private fun entry(
    id: Long,
    timestamp: Instant,
    moodEmoji: String? = null,
    moodLabel: String? = null,
    note: String? = null,
  ) = JournalEntry(
    id = id,
    timestamp = timestamp,
    moodId = moodEmoji?.let { "MOOD" },
    moodEmoji = moodEmoji,
    moodLabel = moodLabel,
    note = note,
    source = JournalSource.APP,
  )

  @Test
  fun `空リストの場合は空のグループになる`() {
    assertTrue(emptyList<JournalEntry>().toHistoryGroups(utc).isEmpty())
  }

  @Test
  fun `日付ごとにグループ化され新しい日付が先頭になる`() {
    val entries = listOf(
      entry(id = 1, timestamp = Instant.parse("2026-08-25T10:00:00Z"), note = "old day"),
      entry(id = 2, timestamp = Instant.parse("2026-08-26T10:00:00Z"), note = "new day"),
    )

    val groups = entries.toHistoryGroups(utc)

    assertEquals(listOf(LocalDate.of(2026, 8, 26), LocalDate.of(2026, 8, 25)), groups.map { it.date })
  }

  @Test
  fun `同じ日付内では新しい時刻順に並ぶ`() {
    val entries = listOf(
      entry(id = 1, timestamp = Instant.parse("2026-08-26T09:00:00Z"), note = "morning"),
      entry(id = 2, timestamp = Instant.parse("2026-08-26T21:00:00Z"), note = "night"),
      entry(id = 3, timestamp = Instant.parse("2026-08-26T13:00:00Z"), note = "noon"),
    )

    val groups = entries.toHistoryGroups(utc)

    assertEquals(1, groups.size)
    assertEquals(listOf("night", "noon", "morning"), groups.single().items.map { it.note })
  }

  @Test
  fun `timestampが同一の場合は入力順によらずid降順で並ぶ`() {
    val tied = Instant.parse("2026-08-26T10:00:00Z")
    val entries = listOf(
      entry(id = 5, timestamp = tied, note = "fifth"),
      entry(id = 7, timestamp = tied, note = "seventh"),
      entry(id = 6, timestamp = tied, note = "sixth"),
    )

    val groups = entries.toHistoryGroups(utc)

    assertEquals(listOf("seventh", "sixth", "fifth"), groups.single().items.map { it.note })
  }

  @Test
  fun `グループ化はUTCではなく指定したタイムゾーンを使う`() {
    // UTCでは8/26 23:30だが、+09:00では8/27 08:30になり、日付境界を跨ぐ。
    val instant = Instant.parse("2026-08-26T23:30:00Z")
    val entries = listOf(entry(id = 1, timestamp = instant, note = "late"))

    val utcGroups = entries.toHistoryGroups(ZoneOffset.UTC)
    val jstGroups = entries.toHistoryGroups(ZoneOffset.ofHours(9))

    assertEquals(LocalDate.of(2026, 8, 26), utcGroups.single().date)
    assertEquals(LocalDate.of(2026, 8, 27), jstGroups.single().date)
    assertEquals(LocalTime.of(8, 30), jstGroups.single().items.single().time)
  }

  @Test
  fun `Moodのみの記録はmoodスナップショットを保持しnoteはnullになる`() {
    val entries = listOf(
      entry(id = 1, timestamp = Instant.parse("2026-08-26T10:00:00Z"), moodEmoji = "🙂", moodLabel = "嬉しい"),
    )

    val item = entries.toHistoryGroups(utc).single().items.single()

    assertEquals("🙂", item.moodEmoji)
    assertEquals("嬉しい", item.moodLabel)
    assertEquals(null, item.note)
  }

  @Test
  fun `手入力のみの記録はnoteを保持しmoodは全てnullになる`() {
    val entries = listOf(
      entry(id = 1, timestamp = Instant.parse("2026-08-26T10:00:00Z"), note = "手入力メモ"),
    )

    val item = entries.toHistoryGroups(utc).single().items.single()

    assertEquals("手入力メモ", item.note)
    assertEquals(null, item.moodEmoji)
    assertEquals(null, item.moodLabel)
  }

  @Test
  fun `moodとnoteは保存済みのスナップショットのまま保持され再計算されない`() {
    val entries = listOf(
      entry(
        id = 1,
        timestamp = Instant.parse("2026-08-26T10:00:00Z"),
        moodEmoji = "🥲",
        moodLabel = "廃止済みMoodの表示名",
        note = "本文",
      ),
    )

    val item = entries.toHistoryGroups(utc).single().items.single()

    assertEquals("🥲", item.moodEmoji)
    assertEquals("廃止済みMoodの表示名", item.moodLabel)
    assertEquals("本文", item.note)
  }
}
