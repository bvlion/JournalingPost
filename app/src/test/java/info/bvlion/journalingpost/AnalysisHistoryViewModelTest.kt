package info.bvlion.journalingpost

import info.bvlion.journalingpost.analysis.AnalysisHistoryUiState
import info.bvlion.journalingpost.analysis.AnalysisResult
import info.bvlion.journalingpost.analysis.AnalysisResultReader
import info.bvlion.journalingpost.analysis.AnalysisResultWriter
import info.bvlion.journalingpost.analysis.PeriodAnalysisOutcome
import info.bvlion.journalingpost.analysis.PeriodAnalyzer
import info.bvlion.journalingpost.journal.JournalEntry
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AnalysisHistoryViewModelTest {
  private val testDispatcher = StandardTestDispatcher()
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
  fun `canRunAnalysisはCUSTOM_WEBHOOKのときだけtrueになる`() = runTest(testDispatcher) {
    val integrationRepository = FakeAnalysisIntegrationRepository(AnalysisIntegration.CUSTOM_WEBHOOK)
    val viewModel = createViewModel(integrationRepository = integrationRepository)
    val collectJob = CoroutineScope(testDispatcher).launch { viewModel.canRunAnalysis.collect {} }
    testDispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.canRunAnalysis.value)

    integrationRepository.set(AnalysisIntegration.NONE)
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(false, viewModel.canRunAnalysis.value)
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
  fun `checkCandidateDayは記録がある日をhasEntries=trueで返す`() = runTest(testDispatcher) {
    val reader = FakePeriodJournalEntryReader(listOf(entry("2026-08-30T05:00:00Z")))
    val viewModel = createViewModel(entryReader = reader, currentZoneId = { ZoneOffset.UTC })

    viewModel.checkCandidateDay(LocalDate.of(2026, 8, 30))
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(
      CandidateDayState.Checked(LocalDate.of(2026, 8, 30), hasEntries = true),
      viewModel.candidateDay.value,
    )
    assertEquals(Instant.parse("2026-08-30T00:00:00Z"), reader.lastPeriodStart)
    assertEquals(Instant.parse("2026-08-31T00:00:00Z"), reader.lastPeriodEnd)
  }

  @Test
  fun `checkCandidateDayは記録が無い日をhasEntries=falseで返す`() = runTest(testDispatcher) {
    val viewModel = createViewModel(entryReader = FakePeriodJournalEntryReader(emptyList()))

    viewModel.checkCandidateDay(LocalDate.of(2026, 8, 30))
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(
      CandidateDayState.Checked(LocalDate.of(2026, 8, 30), hasEntries = false),
      viewModel.candidateDay.value,
    )
  }

  @Test
  fun `clearCandidateDayでNoneへ戻る`() = runTest(testDispatcher) {
    val viewModel = createViewModel(entryReader = FakePeriodJournalEntryReader(emptyList()))
    viewModel.checkCandidateDay(LocalDate.of(2026, 8, 30))
    testDispatcher.scheduler.advanceUntilIdle()

    viewModel.clearCandidateDay()

    assertEquals(CandidateDayState.None, viewModel.candidateDay.value)
  }

  @Test
  fun `対象期間にentryが無ければ送信せずNO_ENTRIESのFailedになる`() = runTest(testDispatcher) {
    val analyzer = FakePeriodAnalyzer { success() }
    val viewModel = createViewModel(analyzer = analyzer, entryReader = FakePeriodJournalEntryReader(emptyList()))

    viewModel.analyze(LocalDate.of(2026, 8, 30))
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(0, analyzer.callCount)
    assertEquals(
      AnalysisRunState.Failed(PeriodAnalysisOutcome.Failure.NO_ENTRIES),
      viewModel.analysisRunState.value,
    )
  }

  @Test
  fun `entry取得が失敗するとLOCAL_READのFailedになる`() = runTest(testDispatcher) {
    val analyzer = FakePeriodAnalyzer { success() }
    val viewModel = createViewModel(
      analyzer = analyzer,
      entryReader = PeriodJournalEntryReader { _, _ -> throw RuntimeException("db boom") },
    )

    viewModel.analyze(LocalDate.of(2026, 8, 30))
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(0, analyzer.callCount)
    assertEquals(
      AnalysisRunState.Failed(PeriodAnalysisOutcome.Failure.LOCAL_READ),
      viewModel.analysisRunState.value,
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

    viewModel.analyze(LocalDate.of(2026, 8, 30))
    testDispatcher.scheduler.advanceUntilIdle()

    val saved = writer.saved.single()
    assertEquals(responsePeriodStart, saved.periodStart)
    assertEquals(responsePeriodEnd, saved.periodEnd)
    assertEquals(responseAnalyzedAt, saved.analyzedAt)
    assertEquals("今日は穏やかでした", saved.body)
    assertEquals(AnalysisRunState.Succeeded, viewModel.analysisRunState.value)
  }

  @Test
  fun `analyze失敗時はAnalysisResultを保存せずFailedになる`() = runTest(testDispatcher) {
    val analyzer = FakePeriodAnalyzer { PeriodAnalysisOutcome.Failure.SERVER_ERROR }
    val writer = FakeAnalysisResultWriter()
    val viewModel = createViewModel(analyzer = analyzer, writer = writer)

    viewModel.analyze(LocalDate.of(2026, 8, 30))
    testDispatcher.scheduler.advanceUntilIdle()

    assertTrue(writer.saved.isEmpty())
    assertEquals(
      AnalysisRunState.Failed(PeriodAnalysisOutcome.Failure.SERVER_ERROR),
      viewModel.analysisRunState.value,
    )
  }

  @Test
  fun `AnalysisResult保存に失敗するとfailureなしのFailedになる`() = runTest(testDispatcher) {
    val analyzer = FakePeriodAnalyzer { success() }
    val writer = FakeAnalysisResultWriter(failOnSave = true)
    val viewModel = createViewModel(analyzer = analyzer, writer = writer)

    viewModel.analyze(LocalDate.of(2026, 8, 30))
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(AnalysisRunState.Failed(null), viewModel.analysisRunState.value)
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
  fun `consumeRunResultで実行結果表示がIdleへ戻る`() = runTest(testDispatcher) {
    val analyzer = FakePeriodAnalyzer { PeriodAnalysisOutcome.Failure.NETWORK }
    val viewModel = createViewModel(analyzer = analyzer)
    viewModel.analyze(LocalDate.of(2026, 8, 30))
    testDispatcher.scheduler.advanceUntilIdle()
    assertTrue(viewModel.analysisRunState.value is AnalysisRunState.Failed)

    viewModel.consumeRunResult()

    assertEquals(AnalysisRunState.Idle, viewModel.analysisRunState.value)
  }

  @Test
  fun `失敗結果はconsumeするまで保持される`() = runTest(testDispatcher) {
    // 解析中にタブを離れて戻るケースで、完了した失敗結果を画面へ出す前に消さないことを固定する。
    val analyzer = FakePeriodAnalyzer { PeriodAnalysisOutcome.Failure.NETWORK }
    val viewModel = createViewModel(analyzer = analyzer)
    viewModel.analyze(LocalDate.of(2026, 8, 30))
    testDispatcher.scheduler.advanceUntilIdle()

    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(
      AnalysisRunState.Failed(PeriodAnalysisOutcome.Failure.NETWORK),
      viewModel.analysisRunState.value,
    )
  }

  private fun createViewModel(
    reader: AnalysisResultReader = FakeAnalysisResultReader(),
    integrationRepository: AnalysisIntegrationRepository =
      FakeAnalysisIntegrationRepository(AnalysisIntegration.CUSTOM_WEBHOOK),
    entryReader: PeriodJournalEntryReader = FakePeriodJournalEntryReader(listOf(entry("2026-08-30T05:00:00Z"))),
    analyzer: PeriodAnalyzer = FakePeriodAnalyzer { success() },
    writer: AnalysisResultWriter = FakeAnalysisResultWriter(),
    currentZoneId: () -> ZoneId = { ZoneOffset.UTC },
  ) = AnalysisHistoryViewModel(
    reader = reader,
    analysisIntegrationRepository = integrationRepository,
    periodJournalEntryReader = entryReader,
    periodAnalyzer = analyzer,
    analysisResultWriter = writer,
    currentZoneId = currentZoneId,
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

  /** 初回発行前の状態を再現するため、購読開始時点では値を持たないflowを使う。 */
  private class FakeAnalysisResultReader : AnalysisResultReader {
    private val results = MutableSharedFlow<List<AnalysisResult>>(replay = 1, extraBufferCapacity = 8)

    fun emit(results: List<AnalysisResult>) {
      check(this.results.tryEmit(results))
    }

    override fun observeAll(): Flow<List<AnalysisResult>> = results
  }

  private class FakeAnalysisResultWriter(private val failOnSave: Boolean = false) : AnalysisResultWriter {
    val saved = mutableListOf<AnalysisResult>()

    override suspend fun save(result: AnalysisResult): Long {
      if (failOnSave) throw RuntimeException("db boom")
      saved += result
      return saved.size.toLong()
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
