package info.bvlion.journalingpost.debug

import info.bvlion.journalingpost.analysis.AnalysisResult
import info.bvlion.journalingpost.analysis.AnalysisResultWriter
import info.bvlion.journalingpost.journal.JournalEntry
import info.bvlion.journalingpost.journal.JournalEntryRepository
import info.bvlion.journalingpost.journal.JournalSource
import info.bvlion.journalingpost.mood.Mood
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugFixtureSeederTest {
  private val tokyo = ZoneId.of("Asia/Tokyo")
  private val moods = listOf(
    Mood(id = "mood-a", emoji = "😀", label = "嬉しい"),
    Mood(id = "mood-b", emoji = "😌", label = "穏やか"),
    Mood(id = "mood-c", emoji = "😮‍💨", label = "疲れた"),
  )

  @Test
  fun `初回投入で今日を含む過去7日分のJournalEntryを作る`() = runTest {
    val entries = FakeJournalEntryRepository()
    val seeder = seeder(entries = entries, now = Instant.parse("2026-09-01T04:00:00Z"))

    seeder.seed()

    val dates = entries.inserted.map { it.timestamp.atZone(tokyo).toLocalDate() }.toSortedSet()
    assertEquals(7, dates.size)
    assertEquals("2026-09-01", dates.last().toString())
    assertEquals("2026-08-26", dates.first().toString())
  }

  @Test
  fun `今日のJournalEntryは縦スクロール確認に十分な件数がある`() = runTest {
    val entries = FakeJournalEntryRepository()
    val seeder = seeder(entries = entries, now = Instant.parse("2026-09-01T04:00:00Z"))

    seeder.seed()

    val today = Instant.parse("2026-09-01T04:00:00Z").atZone(tokyo).toLocalDate()
    val todayCount = entries.inserted.count { it.timestamp.atZone(tokyo).toLocalDate() == today }
    assertTrue("今日の件数=$todayCount", todayCount >= 12)
  }

  @Test
  fun `過去6日はそれぞれ複数件のJournalEntryがある`() = runTest {
    val entries = FakeJournalEntryRepository()
    val seeder = seeder(entries = entries, now = Instant.parse("2026-09-01T04:00:00Z"))

    seeder.seed()

    val today = Instant.parse("2026-09-01T04:00:00Z").atZone(tokyo).toLocalDate()
    for (daysAgo in 1..6) {
      val day = today.minusDays(daysAgo.toLong())
      val count = entries.inserted.count { it.timestamp.atZone(tokyo).toLocalDate() == day }
      assertTrue("$day の件数=$count", count >= 2)
    }
  }

  @Test
  fun `JournalEntryはMoodのみとMoodとnoteとnoteのみを混在させる`() = runTest {
    val entries = FakeJournalEntryRepository()
    seeder(entries = entries).seed()

    assertTrue(entries.inserted.any { it.moodId != null && it.note == null })
    assertTrue(entries.inserted.any { it.moodId != null && it.note != null })
    assertTrue(entries.inserted.any { it.moodId == null && it.note != null })
  }

  @Test
  fun `JournalEntryはAPPとWIDGETのsourceを混在させる`() = runTest {
    val entries = FakeJournalEntryRepository()
    seeder(entries = entries).seed()

    assertTrue(entries.inserted.any { it.source == JournalSource.APP })
    assertTrue(entries.inserted.any { it.source == JournalSource.WIDGET })
  }

  @Test
  fun `AnalysisResultは今日を含む過去7日に1件ずつ作る`() = runTest {
    val results = FakeAnalysisResultWriter()
    val seeder = seeder(results = results, now = Instant.parse("2026-09-01T04:00:00Z"))

    seeder.seed()

    assertEquals(7, results.saved.size)
    val startDates = results.saved.map { it.periodStart.atZone(tokyo).toLocalDate() }.toSortedSet()
    assertEquals(7, startDates.size)
    assertEquals("2026-09-01", startDates.last().toString())
    assertEquals("2026-08-26", startDates.first().toString())
    results.saved.forEach {
      assertEquals(Duration.ofDays(1), Duration.between(it.periodStart, it.periodEnd))
      assertTrue("analyzedAtが未来", !it.analyzedAt.isAfter(Instant.parse("2026-09-01T04:00:00Z")))
    }
  }

  @Test
  fun `2回目の投入では何も追加せずAlreadySeededを返す`() = runTest {
    val entries = FakeJournalEntryRepository()
    val results = FakeAnalysisResultWriter()
    val seeded = booleanArrayOf(false)
    val seeder = seeder(entries = entries, results = results, seededFlag = seeded)

    val first = seeder.seed()
    val entryCountAfterFirst = entries.inserted.size
    val resultCountAfterFirst = results.saved.size
    val second = seeder.seed()

    assertTrue(first is DebugFixtureSeedResult.Seeded)
    assertEquals(DebugFixtureSeedResult.AlreadySeeded, second)
    assertEquals(entryCountAfterFirst, entries.inserted.size)
    assertEquals(resultCountAfterFirst, results.saved.size)
  }

  @Test
  fun `timestampは端末timezoneの今日を基準に生成する`() = runTest {
    // UTCでは9/1、Asia/Tokyoでは9/2に日付が変わる時刻。
    val now = Instant.parse("2026-09-01T16:00:00Z")
    val entriesTokyo = FakeJournalEntryRepository()
    seeder(entries = entriesTokyo, now = now, zone = tokyo).seed()
    val entriesUtc = FakeJournalEntryRepository()
    seeder(entries = entriesUtc, now = now, zone = ZoneId.of("UTC")).seed()

    val latestTokyo = entriesTokyo.inserted.maxOf { it.timestamp.atZone(tokyo).toLocalDate() }
    val latestUtc = entriesUtc.inserted.maxOf { it.timestamp.atZone(ZoneId.of("UTC")).toLocalDate() }
    assertEquals("2026-09-02", latestTokyo.toString())
    assertEquals("2026-09-01", latestUtc.toString())
  }

  private fun seeder(
    entries: FakeJournalEntryRepository = FakeJournalEntryRepository(),
    results: FakeAnalysisResultWriter = FakeAnalysisResultWriter(),
    seededFlag: BooleanArray = booleanArrayOf(false),
    now: Instant = Instant.parse("2026-09-01T04:00:00Z"),
    zone: ZoneId = tokyo,
  ) = DebugFixtureSeeder(
    journalEntryRepository = entries,
    analysisResultWriter = results,
    isAlreadySeeded = { seededFlag[0] },
    markSeeded = { seededFlag[0] = true },
    moods = { moods },
    zoneId = { zone },
    now = { now },
  )

  private class FakeJournalEntryRepository : JournalEntryRepository {
    val inserted = mutableListOf<JournalEntry>()
    private var nextId = 1L

    override suspend fun insert(entry: JournalEntry): Long {
      val id = nextId++
      inserted += entry.copy(id = id)
      return id
    }
  }

  private class FakeAnalysisResultWriter : AnalysisResultWriter {
    val saved = mutableListOf<AnalysisResult>()
    private var nextId = 1L

    override suspend fun save(result: AnalysisResult): Long {
      val id = nextId++
      saved += result.copy(id = id)
      return id
    }
  }
}
