package info.bvlion.journalingpost.analysis

import info.bvlion.journalingpost.journal.JournalEntry
import info.bvlion.journalingpost.journal.JournalSource
import info.bvlion.journalingpost.journal.PeriodJournalEntryReader
import info.bvlion.journalingpost.settings.AnalysisIntegration
import info.bvlion.journalingpost.settings.AnalysisIntegrationRepository
import info.bvlion.journalingpost.settings.AutoAnalysisSettings
import info.bvlion.journalingpost.settings.AutoAnalysisSettingsRepository
import info.bvlion.journalingpost.settings.AutoAnalysisTargetDay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoAnalyzerTest {
  private val enabledYesterday = AutoAnalysisSettings(
    enabled = true,
    timeOfDay = LocalTime.of(8, 0),
    targetDay = AutoAnalysisTargetDay.YESTERDAY,
  )

  @Test
  fun `無効なら何もしない`() = runTest {
    val analyzer = FakePeriodAnalyzer { success() }
    val outcome = createAnalyzer(
      settings = enabledYesterday.copy(enabled = false),
      analyzer = analyzer,
    ).runOnce()

    assertEquals(AutoAnalysisOutcome.SKIPPED_DISABLED, outcome)
    assertEquals(0, analyzer.callCount)
  }

  @Test
  fun `解析先が使用しないなら送らない`() = runTest {
    val analyzer = FakePeriodAnalyzer { success() }
    val outcome = createAnalyzer(
      integration = AnalysisIntegration.NONE,
      analyzer = analyzer,
    ).runOnce()

    assertEquals(AutoAnalysisOutcome.SKIPPED_NO_INTEGRATION, outcome)
    assertEquals(0, analyzer.callCount)
  }

  @Test
  fun `前日を対象に端末timezoneの半開区間へ変換して解析する`() = runTest {
    val analyzer = FakePeriodAnalyzer { success() }
    val entryReader = FakePeriodJournalEntryReader(listOf(entry("2026-08-30T05:00:00Z")))
    val outcome = createAnalyzer(
      analyzer = analyzer,
      entryReader = entryReader,
      currentDate = { LocalDate.of(2026, 8, 31) },
    ).runOnce()

    assertEquals(AutoAnalysisOutcome.ANALYZED, outcome)
    assertEquals(Instant.parse("2026-08-30T00:00:00Z"), analyzer.lastPeriodStart)
    assertEquals(Instant.parse("2026-08-31T00:00:00Z"), analyzer.lastPeriodEnd)
  }

  @Test
  fun `当日指定なら実行日を対象にする`() = runTest {
    val analyzer = FakePeriodAnalyzer { success() }
    val outcome = createAnalyzer(
      settings = enabledYesterday.copy(targetDay = AutoAnalysisTargetDay.TODAY),
      analyzer = analyzer,
      entryReader = FakePeriodJournalEntryReader(listOf(entry("2026-08-31T05:00:00Z"))),
      currentDate = { LocalDate.of(2026, 8, 31) },
    ).runOnce()

    assertEquals(AutoAnalysisOutcome.ANALYZED, outcome)
    assertEquals(Instant.parse("2026-08-31T00:00:00Z"), analyzer.lastPeriodStart)
    assertEquals(Instant.parse("2026-09-01T00:00:00Z"), analyzer.lastPeriodEnd)
  }

  @Test
  fun `対象日に記録が無ければ送らない`() = runTest {
    val analyzer = FakePeriodAnalyzer { success() }
    val outcome = createAnalyzer(
      analyzer = analyzer,
      entryReader = FakePeriodJournalEntryReader(emptyList()),
    ).runOnce()

    assertEquals(AutoAnalysisOutcome.SKIPPED_NO_ENTRIES, outcome)
    assertEquals(0, analyzer.callCount)
  }

  @Test
  fun `Hostedは対象日が解析済みなら送らない`() = runTest {
    val analyzer = FakePeriodAnalyzer { success() }
    val outcome = createAnalyzer(
      integration = AnalysisIntegration.HOSTED,
      analyzer = analyzer,
      entryReader = FakePeriodJournalEntryReader(listOf(entry("2026-08-30T05:00:00Z"))),
      results = listOf(analysisResult(periodStart = "2026-08-30T00:00:00Z")),
      currentDate = { LocalDate.of(2026, 8, 31) },
    ).runOnce()

    assertEquals(AutoAnalysisOutcome.SKIPPED_ALREADY_ANALYZED, outcome)
    assertEquals(0, analyzer.callCount)
  }

  @Test
  fun `Custom Webhookは対象日が解析済みでも送る`() = runTest {
    val analyzer = FakePeriodAnalyzer { success() }
    val outcome = createAnalyzer(
      integration = AnalysisIntegration.CUSTOM_WEBHOOK,
      analyzer = analyzer,
      entryReader = FakePeriodJournalEntryReader(listOf(entry("2026-08-30T05:00:00Z"))),
      results = listOf(analysisResult(periodStart = "2026-08-30T00:00:00Z")),
      currentDate = { LocalDate.of(2026, 8, 31) },
    ).runOnce()

    assertEquals(AutoAnalysisOutcome.ANALYZED, outcome)
    assertEquals(1, analyzer.callCount)
  }

  @Test
  fun `解析先の失敗は再試行せずFAILEDを返す`() = runTest {
    val outcome = createAnalyzer(
      analyzer = FakePeriodAnalyzer { PeriodAnalysisOutcome.Failure.NETWORK },
    ).runOnce()

    assertEquals(AutoAnalysisOutcome.FAILED, outcome)
  }

  @Test
  fun `記録の読み取り失敗はFAILEDを返す`() = runTest {
    val outcome = createAnalyzer(
      entryReader = { _, _ -> throw RuntimeException("db boom") },
    ).runOnce()

    assertEquals(AutoAnalysisOutcome.FAILED, outcome)
  }

  @Test
  fun `成功時はresponseの値でAnalysisResultを保存する`() = runTest {
    val writer = FakeAnalysisResultWriter()
    val outcome = createAnalyzer(
      analyzer = FakePeriodAnalyzer { success(body = "きのうは穏やかでした") },
      writer = writer,
      entryReader = FakePeriodJournalEntryReader(listOf(entry("2026-08-30T05:00:00Z"))),
      currentDate = { LocalDate.of(2026, 8, 31) },
    ).runOnce()

    assertEquals(AutoAnalysisOutcome.ANALYZED, outcome)
    val saved = writer.saved.single()
    assertEquals("きのうは穏やかでした", saved.body)
    assertEquals(RESPONSE_PERIOD_START, saved.periodStart)
  }

  @Test
  fun `保存成功後にretry stateを持つanalyzerへ端末保存の確定を通知する`() = runTest {
    val analyzer = FakePersistenceAwarePeriodAnalyzer { success() }
    createAnalyzer(
      analyzer = analyzer,
      entryReader = FakePeriodJournalEntryReader(listOf(entry("2026-08-30T05:00:00Z"))),
      currentDate = { LocalDate.of(2026, 8, 31) },
    ).runOnce()

    assertEquals(
      listOf(Instant.parse("2026-08-30T00:00:00Z") to Instant.parse("2026-08-31T00:00:00Z")),
      analyzer.persistedPeriods,
    )
  }

  @Test
  fun `保存に失敗したらFAILEDを返し確定通知もしない`() = runTest {
    val analyzer = FakePersistenceAwarePeriodAnalyzer { success() }
    val outcome = createAnalyzer(
      analyzer = analyzer,
      writer = FakeAnalysisResultWriter(failOnSave = true),
      entryReader = FakePeriodJournalEntryReader(listOf(entry("2026-08-30T05:00:00Z"))),
    ).runOnce()

    assertEquals(AutoAnalysisOutcome.FAILED, outcome)
    assertTrue(analyzer.persistedPeriods.isEmpty())
  }

  private fun createAnalyzer(
    settings: AutoAnalysisSettings = enabledYesterday,
    integration: AnalysisIntegration = AnalysisIntegration.HOSTED,
    analyzer: PeriodAnalyzer = FakePeriodAnalyzer { success() },
    writer: AnalysisResultWriter = FakeAnalysisResultWriter(),
    entryReader: PeriodJournalEntryReader =
      FakePeriodJournalEntryReader(listOf(entry("2026-08-30T05:00:00Z"))),
    results: List<AnalysisResult> = emptyList(),
    currentZoneId: () -> ZoneId = { ZoneOffset.UTC },
    currentDate: () -> LocalDate = { LocalDate.of(2026, 8, 31) },
  ) = AutoAnalyzer(
    autoAnalysisSettingsRepository = FakeAutoAnalysisSettingsRepository(settings),
    analysisIntegrationRepository = FakeAnalysisIntegrationRepository(integration),
    periodJournalEntryReader = entryReader,
    analysisResultReader = AnalysisResultReader { MutableStateFlow(results) },
    periodAnalysisRunner = PeriodAnalysisRunner(analyzer, writer),
    currentZoneId = currentZoneId,
    currentDate = currentDate,
  )

  private fun entry(at: String) = JournalEntry(timestamp = Instant.parse(at), note = "メモ", source = JournalSource.APP)

  private fun analysisResult(periodStart: String) = AnalysisResult(
    periodStart = Instant.parse(periodStart),
    periodEnd = Instant.parse(periodStart).plusSeconds(86_400),
    analyzedAt = Instant.parse(periodStart).plusSeconds(90_000),
    body = "既存の解析結果",
  )

  private fun success(body: String = "結果") = PeriodAnalysisOutcome.Success(
    periodStart = RESPONSE_PERIOD_START,
    periodEnd = Instant.parse("2026-08-31T00:05:00Z"),
    analyzedAt = Instant.parse("2026-08-31T02:00:00Z"),
    body = body,
  )

  private companion object {
    val RESPONSE_PERIOD_START: Instant = Instant.parse("2026-08-30T00:05:00Z")
  }

  private class FakeAutoAnalysisSettingsRepository(settings: AutoAnalysisSettings) : AutoAnalysisSettingsRepository {
    private val state = MutableStateFlow(settings)
    override val autoAnalysisSettings: Flow<AutoAnalysisSettings> = state
    override suspend fun setAutoAnalysisSettings(settings: AutoAnalysisSettings) {
      state.value = settings
    }
  }

  private class FakeAnalysisIntegrationRepository(integration: AnalysisIntegration) : AnalysisIntegrationRepository {
    private val state = MutableStateFlow(integration)
    override val analysisIntegration: Flow<AnalysisIntegration> = state
    override suspend fun setAnalysisIntegration(integration: AnalysisIntegration) {
      state.value = integration
    }
  }

  private class FakePeriodJournalEntryReader(private val entries: List<JournalEntry>) : PeriodJournalEntryReader {
    override suspend fun entriesInPeriod(periodStart: Instant, periodEnd: Instant): List<JournalEntry> = entries
  }

  private class FakeAnalysisResultWriter(private val failOnSave: Boolean = false) : AnalysisResultWriter {
    val saved = mutableListOf<AnalysisResult>()
    override suspend fun save(result: AnalysisResult): Long {
      if (failOnSave) throw RuntimeException("db boom")
      saved += result
      return saved.size.toLong()
    }
  }

  private class FakePeriodAnalyzer(private val outcome: () -> PeriodAnalysisOutcome) : PeriodAnalyzer {
    var callCount = 0
      private set
    var lastPeriodStart: Instant? = null
      private set
    var lastPeriodEnd: Instant? = null
      private set

    override suspend fun analyze(
      periodStart: Instant,
      periodEnd: Instant,
      entries: List<JournalEntry>,
    ): PeriodAnalysisOutcome {
      callCount++
      lastPeriodStart = periodStart
      lastPeriodEnd = periodEnd
      return outcome()
    }
  }

  private class FakePersistenceAwarePeriodAnalyzer(
    private val outcome: () -> PeriodAnalysisOutcome,
  ) : PeriodAnalyzer, AnalysisResultPersistenceListener {
    val persistedPeriods = mutableListOf<Pair<Instant, Instant>>()

    override suspend fun analyze(
      periodStart: Instant,
      periodEnd: Instant,
      entries: List<JournalEntry>,
    ): PeriodAnalysisOutcome = outcome()

    override suspend fun onAnalysisResultPersisted(periodStart: Instant, periodEnd: Instant) {
      persistedPeriods += periodStart to periodEnd
    }
  }
}
