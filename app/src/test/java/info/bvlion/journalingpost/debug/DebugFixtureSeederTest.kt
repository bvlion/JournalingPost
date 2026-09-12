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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugFixtureSeederTest {
  private val tokyo = ZoneId.of("Asia/Tokyo")
  private val moods = listOf(
    Mood(id = "excited", emoji = "🤩", label = "ワクワク"),
    Mood(id = "happy", emoji = "😄", label = "嬉しい"),
    Mood(id = "calm", emoji = "😌", label = "穏やか"),
    Mood(id = "neutral", emoji = "😐", label = "普通"),
    Mood(id = "tired", emoji = "😮‍💨", label = "疲れた"),
    Mood(id = "uncertain", emoji = "😕", label = "もやもや"),
    Mood(id = "anxious", emoji = "😰", label = "不安"),
    Mood(id = "irritated", emoji = "😡", label = "イライラ"),
  )

  @Test
  fun `初回投入で今日を含む過去14日分のJournalEntryを作る`() = runTest {
    val entries = FakeJournalEntryRepository()
    val seeder = seeder(entries = entries, now = Instant.parse("2026-09-01T04:00:00Z"))

    val result = seeder.seed() as DebugFixtureSeedResult.Seeded

    assertEquals(49, result.entryCount)
    val dates = entries.inserted.map { it.timestamp.atZone(tokyo).toLocalDate() }.toSortedSet()
    assertEquals(14, dates.size)
    assertEquals("2026-09-01", dates.last().toString())
    assertEquals("2026-08-19", dates.first().toString())
  }

  @Test
  fun `各日のJournalEntry件数は解析入力と一致する`() = runTest {
    val entries = FakeJournalEntryRepository()
    val seeder = seeder(entries = entries, now = Instant.parse("2026-09-01T04:00:00Z"))

    seeder.seed()

    val today = Instant.parse("2026-09-01T04:00:00Z").atZone(tokyo).toLocalDate()
    val counts = (0..13).map { daysAgo ->
      val day = today.minusDays(daysAgo.toLong())
      entries.inserted.count { it.timestamp.atZone(tokyo).toLocalDate() == day }
    }
    assertEquals(listOf(4, 4, 4, 4, 3, 3, 4, 4, 3, 4, 3, 3, 3, 3), counts)
  }

  @Test
  fun `JournalEntryは解析入力のMoodとnoteを保持する`() = runTest {
    val entries = FakeJournalEntryRepository()
    seeder(entries = entries).seed()

    assertTrue(entries.inserted.all { it.moodId != null })
    assertTrue(entries.inserted.any { it.note == null })
    assertTrue(entries.inserted.any { it.note != null })
    assertTrue(entries.inserted.none { it.note?.contains("週") == true })
    assertEquals(
      JournalEntry(
        id = 1,
        timestamp = Instant.parse("2026-08-31T23:00:00Z"),
        moodId = "calm",
        moodEmoji = "😌",
        moodLabel = "穏やか",
        note = "朝から落ち着いている。前よりペースをつかめている気がする。",
        source = JournalSource.WIDGET,
      ),
      entries.inserted.first(),
    )
  }

  @Test
  fun `JournalEntryはAPPとWIDGETのsourceを混在させる`() = runTest {
    val entries = FakeJournalEntryRepository()
    seeder(entries = entries).seed()

    assertTrue(entries.inserted.any { it.source == JournalSource.APP })
    assertTrue(entries.inserted.any { it.source == JournalSource.WIDGET })
  }

  @Test
  fun `AnalysisResultは今日を含む過去14日に1件ずつ作る`() = runTest {
    val results = FakeAnalysisResultWriter()
    val seeder = seeder(results = results, now = Instant.parse("2026-09-01T04:00:00Z"))

    seeder.seed()

    assertEquals(14, results.saved.size)
    val startDates = results.saved.map { it.periodStart.atZone(tokyo).toLocalDate() }.toSortedSet()
    assertEquals(14, startDates.size)
    assertEquals("2026-09-01", startDates.last().toString())
    assertEquals("2026-08-19", startDates.first().toString())
    assertTrue(results.saved.first().body.startsWith("【要約】\n朝は落ち着いており"))
    assertTrue(results.saved.last().body.startsWith("【要約】\n朝から穏やかな気分で始まり"))
    assertTrue(results.saved.none { it.body.contains("週") })
    assertTrue(results.saved.none { it.body.contains("😊") })
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
  fun `seedを並行呼び出ししてもJournalEntryとAnalysisResultは1セットしか投入しない`() = runTest {
    val entries = FakeJournalEntryRepository()
    val results = FakeAnalysisResultWriter()
    val seeder = seeder(entries = entries, results = results)

    val outcomes = listOf(
      async { seeder.seed() },
      async { seeder.seed() },
      async { seeder.seed() },
    ).awaitAll()

    val seededOutcome = outcomes.filterIsInstance<DebugFixtureSeedResult.Seeded>().single()
    assertEquals(2, outcomes.count { it == DebugFixtureSeedResult.AlreadySeeded })
    assertEquals(seededOutcome.entryCount, entries.inserted.size)
    assertEquals(seededOutcome.analysisResultCount, results.saved.size)
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
      // 並行seed()テストで、書き込みの途中に別の呼び出しへ制御が渡る余地を作る。
      yield()
      val id = nextId++
      inserted += entry.copy(id = id)
      return id
    }
  }

  private class FakeAnalysisResultWriter : AnalysisResultWriter {
    val saved = mutableListOf<AnalysisResult>()
    private var nextId = 1L

    override suspend fun save(result: AnalysisResult): Long {
      yield()
      val id = nextId++
      saved += result.copy(id = id)
      return id
    }
  }
}
