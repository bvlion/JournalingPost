package info.bvlion.journalingpost

import info.bvlion.journalingpost.analysis.AnalysisHistoryUiState
import info.bvlion.journalingpost.analysis.AnalysisResult
import info.bvlion.journalingpost.analysis.AnalysisResultPersistenceListener
import info.bvlion.journalingpost.analysis.AnalysisResultReader
import info.bvlion.journalingpost.analysis.AnalysisResultWriter
import info.bvlion.journalingpost.analysis.PeriodAnalysisOutcome
import info.bvlion.journalingpost.analysis.PeriodAnalyzer
import info.bvlion.journalingpost.journal.JournalEntry
import info.bvlion.journalingpost.journal.JournalEntryReader
import info.bvlion.journalingpost.journal.JournalSource
import info.bvlion.journalingpost.journal.PeriodJournalEntryReader
import info.bvlion.journalingpost.settings.AnalysisIntegration
import info.bvlion.journalingpost.settings.AnalysisIntegrationRepository
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AnalysisHistoryViewModelTest {
  private val testDispatcher = StandardTestDispatcher()

  // Channel由来のrunResultsは購読者がいる間だけ流れるため、テスト中はこのscopeで購読し続ける。
  private val collectorScope = CoroutineScope(testDispatcher)
  private val responsePeriodStart = Instant.parse("2026-08-30T00:05:00Z")
  private val responsePeriodEnd = Instant.parse("2026-08-31T00:05:00Z")
  private val responseAnalyzedAt = Instant.parse("2026-08-31T02:00:00Z")

  private fun success(body: String = "結果") = PeriodAnalysisOutcome.Success(
    periodStart = responsePeriodStart,
    periodEnd = responsePeriodEnd,
    analyzedAt = responseAnalyzedAt,
    body = body,
  )

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
  }

  @After
  fun tearDown() {
    collectorScope.cancel()
    Dispatchers.resetMain()
  }

  @Test
  fun `uiStateはreaderの初回発行前はLoadingで空状態と区別される`() = runTest(testDispatcher) {
    val reader = FakeAnalysisResultReader()
    val viewModel = createViewModel(reader = reader)
    val collectJob = launchCollection(viewModel)
    testDispatcher.scheduler.runCurrent()

    assertEquals(AnalysisHistoryUiState.Loading, viewModel.uiState.value)
    collectJob.cancel()
  }

  @Test
  fun `uiStateは読み込み完了後に0件ならEmptyになる`() = runTest(testDispatcher) {
    val reader = FakeAnalysisResultReader()
    val viewModel = createViewModel(reader = reader)
    val collectJob = launchCollection(viewModel)

    reader.emit(emptyList())
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(AnalysisHistoryUiState.Empty, viewModel.uiState.value)
    collectJob.cancel()
  }

  @Test
  fun `uiStateはreaderの発行後に解析日時の新しい順で結果を反映する`() = runTest(testDispatcher) {
    val reader = FakeAnalysisResultReader()
    val viewModel = createViewModel(reader = reader)
    val collectJob = launchCollection(viewModel)

    reader.emit(
      listOf(
        result(id = 1, analyzedAt = "2026-08-07T07:00:00Z", body = "old"),
        result(id = 2, analyzedAt = "2026-08-08T07:00:00Z", body = "new"),
      ),
    )
    testDispatcher.scheduler.advanceUntilIdle()

    val items = (viewModel.uiState.value as AnalysisHistoryUiState.Content).items
    assertEquals(listOf("new", "old"), items.map { it.body })
    assertEquals(LocalDateTime.of(2026, 8, 8, 7, 0), items.first().analyzedAt)
    collectJob.cancel()
  }

  @Test
  fun `解析先に応じて手動解析可否とCustom Webhook判定を返す`() = runTest(testDispatcher) {
    val integrationRepository = FakeAnalysisIntegrationRepository(AnalysisIntegration.CUSTOM_WEBHOOK)
    val viewModel = createViewModel(integrationRepository = integrationRepository)
    val collectJob = CoroutineScope(testDispatcher).launch { viewModel.canRunAnalysis.collect {} }
    val customWebhookCollectJob = CoroutineScope(testDispatcher).launch { viewModel.isCustomWebhook.collect {} }
    testDispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.canRunAnalysis.value)
    assertTrue(viewModel.isCustomWebhook.value)

    integrationRepository.set(AnalysisIntegration.HOSTED)
    testDispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.canRunAnalysis.value)
    assertFalse(viewModel.isCustomWebhook.value)

    integrationRepository.set(AnalysisIntegration.NONE)
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(false, viewModel.canRunAnalysis.value)
    assertFalse(viewModel.isCustomWebhook.value)
    collectJob.cancel()
    customWebhookCollectJob.cancel()
  }

  @Test
  fun `selectableDaysはCustom WebhookではJournalEntryのある日を端末timezoneのカレンダー日で返す`() =
    runTest(testDispatcher) {
      val journalEntryReader = FakeJournalEntryReader()
      val reader = FakeAnalysisResultReader()
      val viewModel = createViewModel(
        reader = reader,
        integrationRepository = FakeAnalysisIntegrationRepository(AnalysisIntegration.CUSTOM_WEBHOOK),
        journalEntryReader = journalEntryReader,
        currentZoneId = { ZoneId.of("Asia/Tokyo") },
      )
      val collectJob = CoroutineScope(testDispatcher).launch { viewModel.selectableDays.collect {} }

      reader.emit(emptyList())
      journalEntryReader.emit(
        listOf(
          entry("2026-08-29T20:00:00Z"),
          entry("2026-08-30T02:00:00Z"),
          entry("2026-08-24T18:00:00Z"),
        ),
      )
      testDispatcher.scheduler.advanceUntilIdle()

      assertEquals(
        setOf(LocalDate.of(2026, 8, 30), LocalDate.of(2026, 8, 25)),
        viewModel.selectableDays.value,
      )
      collectJob.cancel()
    }

  @Test
  fun `selectableDaysはJournalEntryが0件なら空になる`() = runTest(testDispatcher) {
    val journalEntryReader = FakeJournalEntryReader()
    val reader = FakeAnalysisResultReader()
    val viewModel = createViewModel(reader = reader, journalEntryReader = journalEntryReader)
    val collectJob = CoroutineScope(testDispatcher).launch { viewModel.selectableDays.collect {} }

    reader.emit(emptyList())
    journalEntryReader.emit(emptyList())
    testDispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.selectableDays.value.isEmpty())
    collectJob.cancel()
  }

  @Test
  fun `selectableDaysはHostedでは当日と解析済みの日を除いた前日以前の記録日だけになる`() = runTest(testDispatcher) {
    val journalEntryReader = FakeJournalEntryReader()
    val reader = FakeAnalysisResultReader()
    val viewModel = createViewModel(
      reader = reader,
      integrationRepository = FakeAnalysisIntegrationRepository(AnalysisIntegration.HOSTED),
      journalEntryReader = journalEntryReader,
      currentZoneId = { ZoneOffset.UTC },
      currentDate = { LocalDate.of(2026, 8, 30) },
    )
    val collectJob = CoroutineScope(testDispatcher).launch { viewModel.selectableDays.collect {} }

    reader.emit(
      listOf(
        result(id = 1, analyzedAt = "2026-08-29T07:00:00Z", body = "既に解析済み")
          .copy(periodStart = Instant.parse("2026-08-28T00:00:00Z")),
      ),
    )
    journalEntryReader.emit(
      listOf(
        entry("2026-08-27T05:00:00Z"),
        entry("2026-08-28T05:00:00Z"),
        entry("2026-08-29T05:00:00Z"),
        entry("2026-08-30T05:00:00Z"),
      ),
    )
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(
      setOf(LocalDate.of(2026, 8, 27), LocalDate.of(2026, 8, 29)),
      viewModel.selectableDays.value,
    )
    collectJob.cancel()
  }

  @Test
  fun `analyzeは選択日を端末timezoneの半開区間へ変換してentryを取得しanalyzerへ渡す`() = runTest(testDispatcher) {
    val analyzer = FakePeriodAnalyzer { success() }
    val reader = FakePeriodJournalEntryReader(listOf(entry("2026-08-30T05:00:00Z")))
    val viewModel = createViewModel(analyzer = analyzer, entryReader = reader, currentZoneId = { ZoneOffset.UTC })

    viewModel.analyze(LocalDate.of(2026, 8, 30))
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(Instant.parse("2026-08-30T00:00:00Z"), reader.lastPeriodStart)
    assertEquals(Instant.parse("2026-08-31T00:00:00Z"), reader.lastPeriodEnd)
    assertEquals(Instant.parse("2026-08-30T00:00:00Z"), analyzer.lastPeriodStart)
    assertEquals(Instant.parse("2026-08-31T00:00:00Z"), analyzer.lastPeriodEnd)
    assertEquals(1, analyzer.lastEntries?.size)
  }

  @Test
  fun `対象期間にentryが無ければ送信せずNO_ENTRIESのFailedになる`() = runTest(testDispatcher) {
    val analyzer = FakePeriodAnalyzer { success() }
    val viewModel = createViewModel(analyzer = analyzer, entryReader = FakePeriodJournalEntryReader(emptyList()))
    val results = collectRunResults(viewModel)

    viewModel.analyze(LocalDate.of(2026, 8, 30))
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(0, analyzer.callCount)
    assertEquals(
      listOf(AnalysisRunResult.Failed(PeriodAnalysisOutcome.Failure.NO_ENTRIES, LocalDate.of(2026, 8, 30))),
      results,
    )
  }

  @Test
  fun `NO_ENTRIESのFailedは解析対象に選んだ日を保持する`() = runTest(testDispatcher) {
    val viewModel = createViewModel(entryReader = FakePeriodJournalEntryReader(emptyList()))
    val results = collectRunResults(viewModel)

    viewModel.analyze(LocalDate.of(2026, 8, 30))
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(LocalDate.of(2026, 8, 30), (results.single() as AnalysisRunResult.Failed).day)
  }

  @Test
  fun `entry取得が失敗するとLOCAL_READのFailedになる`() = runTest(testDispatcher) {
    val analyzer = FakePeriodAnalyzer { success() }
    val viewModel = createViewModel(
      analyzer = analyzer,
      entryReader = PeriodJournalEntryReader { _, _ -> throw RuntimeException("db boom") },
    )
    val results = collectRunResults(viewModel)

    viewModel.analyze(LocalDate.of(2026, 8, 30))
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(0, analyzer.callCount)
    assertEquals(
      listOf(AnalysisRunResult.Failed(PeriodAnalysisOutcome.Failure.LOCAL_READ, LocalDate.of(2026, 8, 30))),
      results,
    )
  }

  @Test
  fun `analyzeは解析開始時点の現在timezoneで期間境界を計算する`() = runTest(testDispatcher) {
    val analyzer = FakePeriodAnalyzer { success() }
    var zone: ZoneId = ZoneOffset.UTC
    val viewModel = createViewModel(analyzer = analyzer, currentZoneId = { zone })

    // ViewModel生成後にtimezoneが変わる状況(移動など)を再現する。
    zone = ZoneId.of("Asia/Tokyo")
    viewModel.analyze(LocalDate.of(2026, 8, 30))
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(Instant.parse("2026-08-29T15:00:00Z"), analyzer.lastPeriodStart)
    assertEquals(Instant.parse("2026-08-30T15:00:00Z"), analyzer.lastPeriodEnd)
  }

  @Test
  fun `analyze成功時はresponseのperiod・analyzedAt・textでAnalysisResultを保存する`() = runTest(testDispatcher) {
    val analyzer = FakePeriodAnalyzer { success("今日は穏やかでした") }
    val writer = FakeAnalysisResultWriter()
    val viewModel = createViewModel(analyzer = analyzer, writer = writer, currentZoneId = { ZoneOffset.UTC })
    val results = collectRunResults(viewModel)

    viewModel.analyze(LocalDate.of(2026, 8, 30))
    testDispatcher.scheduler.advanceUntilIdle()

    val saved = writer.saved.single()
    assertEquals(responsePeriodStart, saved.periodStart)
    assertEquals(responsePeriodEnd, saved.periodEnd)
    assertEquals(responseAnalyzedAt, saved.analyzedAt)
    assertEquals("今日は穏やかでした", saved.body)
    assertEquals(listOf(AnalysisRunResult.Succeeded(1L)), results)
  }

  @Test
  fun `Succeededは保存したAnalysisResultの行idを持つ`() = runTest(testDispatcher) {
    val viewModel = createViewModel(writer = FakeAnalysisResultWriter(savedId = 7L))
    val results = collectRunResults(viewModel)

    viewModel.analyze(LocalDate.of(2026, 8, 30))
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(listOf(AnalysisRunResult.Succeeded(7L)), results)
  }

  @Test
  fun `analyze失敗時はAnalysisResultを保存せずFailedになる`() = runTest(testDispatcher) {
    val analyzer = FakePeriodAnalyzer { PeriodAnalysisOutcome.Failure.SERVER_ERROR }
    val writer = FakeAnalysisResultWriter()
    val viewModel = createViewModel(analyzer = analyzer, writer = writer)
    val results = collectRunResults(viewModel)

    viewModel.analyze(LocalDate.of(2026, 8, 30))
    testDispatcher.scheduler.advanceUntilIdle()

    assertTrue(writer.saved.isEmpty())
    assertEquals(
      listOf(AnalysisRunResult.Failed(PeriodAnalysisOutcome.Failure.SERVER_ERROR, LocalDate.of(2026, 8, 30))),
      results,
    )
  }

  @Test
  fun `AnalysisResult保存に失敗するとfailureなしのFailedになる`() = runTest(testDispatcher) {
    val analyzer = FakePeriodAnalyzer { success() }
    val writer = FakeAnalysisResultWriter(failOnSave = true)
    val viewModel = createViewModel(analyzer = analyzer, writer = writer)
    val results = collectRunResults(viewModel)

    viewModel.analyze(LocalDate.of(2026, 8, 30))
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(listOf(AnalysisRunResult.Failed(null, LocalDate.of(2026, 8, 30))), results)
  }

  @Test
  fun `保存成功後にanalyzerへ端末保存の確定を対象期間つきで通知する`() = runTest(testDispatcher) {
    val analyzer = FakePersistenceAwarePeriodAnalyzer { success() }
    val viewModel = createViewModel(analyzer = analyzer, currentZoneId = { ZoneOffset.UTC })
    val results = collectRunResults(viewModel)

    viewModel.analyze(LocalDate.of(2026, 8, 30))
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(listOf(AnalysisRunResult.Succeeded(1L)), results)
    assertEquals(
      listOf(Instant.parse("2026-08-30T00:00:00Z") to Instant.parse("2026-08-31T00:00:00Z")),
      analyzer.persistedPeriods,
    )
  }

  @Test
  fun `保存に失敗した場合はanalyzerへ端末保存の確定を通知しない`() = runTest(testDispatcher) {
    val analyzer = FakePersistenceAwarePeriodAnalyzer { success() }
    val viewModel = createViewModel(
      analyzer = analyzer,
      writer = FakeAnalysisResultWriter(failOnSave = true),
    )
    val results = collectRunResults(viewModel)

    viewModel.analyze(LocalDate.of(2026, 8, 30))
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(listOf(AnalysisRunResult.Failed(null, LocalDate.of(2026, 8, 30))), results)
    assertTrue(analyzer.persistedPeriods.isEmpty())
  }

  @Test
  fun `解析実行中の再呼び出しは無視される`() = runTest(testDispatcher) {
    val analyzer = FakePeriodAnalyzer { success() }
    val viewModel = createViewModel(analyzer = analyzer)

    viewModel.analyze(LocalDate.of(2026, 8, 30))
    viewModel.analyze(LocalDate.of(2026, 8, 29))
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(1, analyzer.callCount)
  }

  @Test
  fun `解析実行の前後でisAnalysisRunningが切り替わる`() = runTest(testDispatcher) {
    val viewModel = createViewModel()
    assertFalse(viewModel.isAnalysisRunning.value)

    viewModel.analyze(LocalDate.of(2026, 8, 30))
    assertTrue(viewModel.isAnalysisRunning.value)

    testDispatcher.scheduler.advanceUntilIdle()

    assertFalse(viewModel.isAnalysisRunning.value)
  }

  @Test
  fun `実行結果は画面が受け取るまで保持される`() = runTest(testDispatcher) {
    // 解析中にタブを離れて戻るケースで、完了した結果を画面へ出す前に落とさないことを固定する。
    val analyzer = FakePeriodAnalyzer { PeriodAnalysisOutcome.Failure.NETWORK }
    val viewModel = createViewModel(analyzer = analyzer)
    viewModel.analyze(LocalDate.of(2026, 8, 30))
    testDispatcher.scheduler.advanceUntilIdle()

    val results = collectRunResults(viewModel)
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(
      listOf(AnalysisRunResult.Failed(PeriodAnalysisOutcome.Failure.NETWORK, LocalDate.of(2026, 8, 30))),
      results,
    )
  }

  @Test
  fun `購読が途切れている間に完了した結果は再購読時に受け取れる`() = runTest(testDispatcher) {
    // 画面がSTOPPEDの間はeventを購読しないため、その間に完了した結果を再開時に受け取れることを固定する。
    val analyzer = FakePeriodAnalyzer { PeriodAnalysisOutcome.Failure.NETWORK }
    val viewModel = createViewModel(analyzer = analyzer)
    val whileStopped = mutableListOf<AnalysisRunResult>()
    val collectJob = collectorScope.launch { viewModel.runResults.collect { whileStopped += it } }
    testDispatcher.scheduler.advanceUntilIdle()

    collectJob.cancel()
    testDispatcher.scheduler.advanceUntilIdle()
    viewModel.analyze(LocalDate.of(2026, 8, 30))
    testDispatcher.scheduler.advanceUntilIdle()
    assertTrue(whileStopped.isEmpty())

    val afterRestart = collectRunResults(viewModel)
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(
      listOf(AnalysisRunResult.Failed(PeriodAnalysisOutcome.Failure.NETWORK, LocalDate.of(2026, 8, 30))),
      afterRestart,
    )
  }

  private fun createViewModel(
    reader: AnalysisResultReader = FakeAnalysisResultReader(),
    integrationRepository: AnalysisIntegrationRepository =
      FakeAnalysisIntegrationRepository(AnalysisIntegration.CUSTOM_WEBHOOK),
    journalEntryReader: JournalEntryReader = FakeJournalEntryReader(),
    entryReader: PeriodJournalEntryReader = FakePeriodJournalEntryReader(listOf(entry("2026-08-30T05:00:00Z"))),
    analyzer: PeriodAnalyzer = FakePeriodAnalyzer { success() },
    writer: AnalysisResultWriter = FakeAnalysisResultWriter(),
    currentZoneId: () -> ZoneId = { ZoneOffset.UTC },
    currentDate: () -> LocalDate = { LocalDate.of(2026, 9, 1) },
  ) = AnalysisHistoryViewModel(
    reader = reader,
    analysisIntegrationRepository = integrationRepository,
    journalEntryReader = journalEntryReader,
    periodJournalEntryReader = entryReader,
    periodAnalyzer = analyzer,
    analysisResultWriter = writer,
    currentZoneId = currentZoneId,
    currentDate = currentDate,
  )

  private fun entry(at: String) = JournalEntry(
    timestamp = Instant.parse(at),
    note = "メモ",
    source = JournalSource.APP,
  )

  private fun result(id: Long, analyzedAt: String, body: String) = AnalysisResult(
    id = id,
    periodStart = Instant.parse("2026-08-01T00:00:00Z"),
    periodEnd = Instant.parse("2026-08-07T00:00:00Z"),
    analyzedAt = Instant.parse(analyzedAt),
    body = body,
  )

  private fun launchCollection(viewModel: AnalysisHistoryViewModel) =
    CoroutineScope(testDispatcher).launch { viewModel.uiState.collect {} }

  private fun collectRunResults(viewModel: AnalysisHistoryViewModel): List<AnalysisRunResult> {
    val results = mutableListOf<AnalysisRunResult>()
    collectorScope.launch { viewModel.runResults.collect { results += it } }
    return results
  }

  /** 初回発行前の状態を再現するため、購読開始時点では値を持たないflowを使う。 */
  private class FakeAnalysisResultReader : AnalysisResultReader {
    private val results = MutableSharedFlow<List<AnalysisResult>>(replay = 1, extraBufferCapacity = 8)

    fun emit(results: List<AnalysisResult>) {
      check(this.results.tryEmit(results))
    }

    override fun observeAll(): Flow<List<AnalysisResult>> = results
  }

  private class FakeJournalEntryReader(entries: List<JournalEntry> = emptyList()) : JournalEntryReader {
    private val entries = MutableStateFlow(entries)

    fun emit(entries: List<JournalEntry>) {
      this.entries.value = entries
    }

    override fun observeAll(): Flow<List<JournalEntry>> = entries
  }

  private class FakeAnalysisResultWriter(
    private val failOnSave: Boolean = false,
    private val savedId: Long = 1L,
  ) : AnalysisResultWriter {
    val saved = mutableListOf<AnalysisResult>()

    override suspend fun save(result: AnalysisResult): Long {
      if (failOnSave) throw RuntimeException("db boom")
      saved += result
      return savedId
    }
  }

  private class FakeAnalysisIntegrationRepository(initial: AnalysisIntegration) : AnalysisIntegrationRepository {
    private val state = MutableStateFlow(initial)
    override val analysisIntegration: Flow<AnalysisIntegration> = state

    fun set(integration: AnalysisIntegration) {
      state.value = integration
    }

    override suspend fun setAnalysisIntegration(integration: AnalysisIntegration) {
      state.value = integration
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

  private class FakePeriodAnalyzer(
    private val outcome: () -> PeriodAnalysisOutcome,
  ) : PeriodAnalyzer {
    var callCount = 0
      private set
    var lastPeriodStart: Instant? = null
      private set
    var lastPeriodEnd: Instant? = null
      private set
    var lastEntries: List<JournalEntry>? = null
      private set

    override suspend fun analyze(
      periodStart: Instant,
      periodEnd: Instant,
      entries: List<JournalEntry>,
    ): PeriodAnalysisOutcome {
      callCount++
      lastPeriodStart = periodStart
      lastPeriodEnd = periodEnd
      lastEntries = entries
      return outcome()
    }
  }

  private class FakePeriodJournalEntryReader(private val entries: List<JournalEntry>) : PeriodJournalEntryReader {
    var lastPeriodStart: Instant? = null
      private set
    var lastPeriodEnd: Instant? = null
      private set

    override suspend fun entriesInPeriod(periodStart: Instant, periodEnd: Instant): List<JournalEntry> {
      lastPeriodStart = periodStart
      lastPeriodEnd = periodEnd
      return entries
    }
  }
}
