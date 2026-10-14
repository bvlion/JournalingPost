package info.bvlion.journalingpost

import androidx.lifecycle.ViewModelLazy
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import info.bvlion.journalingpost.analysis.AnalysisExecutionRepository
import info.bvlion.journalingpost.analysis.AnalysisExecutionState
import info.bvlion.journalingpost.analysis.AnalysisResult
import info.bvlion.journalingpost.analysis.AnalysisResultPersistenceListener
import info.bvlion.journalingpost.analysis.AnalysisResultReader
import info.bvlion.journalingpost.analysis.AnalysisResultWriter
import info.bvlion.journalingpost.analysis.ManualAnalysisAvailability
import info.bvlion.journalingpost.analysis.ManualAnalysisEligibility
import info.bvlion.journalingpost.analysis.PeriodAnalysisOutcome
import info.bvlion.journalingpost.analysis.PeriodAnalysisRunner
import info.bvlion.journalingpost.analysis.PeriodAnalyzer
import info.bvlion.journalingpost.hosted.HostedCredentialsRepository
import info.bvlion.journalingpost.hosted.sha256Hex
import info.bvlion.journalingpost.journal.JournalEntry
import info.bvlion.journalingpost.journal.JournalEntryDeleter
import info.bvlion.journalingpost.journal.JournalEntryReader
import info.bvlion.journalingpost.journal.JournalSource
import info.bvlion.journalingpost.journal.PeriodJournalEntryReader
import info.bvlion.journalingpost.journal.history.JournalHistoryUiState
import info.bvlion.journalingpost.settings.AnalysisIntegration
import info.bvlion.journalingpost.settings.AnalysisIntegrationRepository
import info.bvlion.journalingpost.time.CalendarDate
import info.bvlion.journalingpost.time.CurrentDateProvider
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ManualAnalysisViewModelTest {
  private val testDispatcher = StandardTestDispatcher()

  private val collectorScope = CoroutineScope(testDispatcher)
  private val responsePeriodStart = Instant.parse("2026-08-30T00:05:00Z")
  private val responsePeriodEnd = Instant.parse("2026-08-31T00:05:00Z")
  private val responseAnalyzedAt = Instant.parse("2026-08-31T02:00:00Z")

  private fun success(
    body: String = "結果",
    integration: AnalysisIntegration = AnalysisIntegration.CUSTOM_WEBHOOK,
  ) = PeriodAnalysisOutcome.Success(
    periodStart = responsePeriodStart,
    periodEnd = responsePeriodEnd,
    analyzedAt = responseAnalyzedAt,
    body = body,
    integration = integration,
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
  fun `解析先に応じて手動解析可否を返す`() = runTest(testDispatcher) {
    val integrationRepository = FakeAnalysisIntegrationRepository(AnalysisIntegration.CUSTOM_WEBHOOK)
    val viewModel = createViewModel(integrationRepository = integrationRepository)
    val collectJob = CoroutineScope(testDispatcher).launch { viewModel.eligibility.collect {} }
    testDispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.ready().hasAvailableIntegration)

    integrationRepository.set(AnalysisIntegration.HOSTED)
    testDispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.ready().hasAvailableIntegration)

    integrationRepository.set(AnalysisIntegration.NONE)
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(false, viewModel.ready().hasAvailableIntegration)
    collectJob.cancel()
  }

  @Test
  fun `selectableDaysはCustom WebhookではJournalEntryのある日を端末timezoneのカレンダー日で返す`() =
    runTest(testDispatcher) {
      val journalEntryReader = FakeJournalEntryReader()
      val viewModel = createViewModel(
        integrationRepository = FakeAnalysisIntegrationRepository(AnalysisIntegration.CUSTOM_WEBHOOK),
        journalEntryReader = journalEntryReader,
        currentZoneId = MutableStateFlow(ZoneId.of("Asia/Tokyo")),
      )
      val collectJob = CoroutineScope(testDispatcher).launch { viewModel.eligibility.collect {} }

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
        viewModel.ready().selectableDays,
      )
      collectJob.cancel()
    }

  @Test
  fun `selectableDaysはJournalEntryが0件なら空になる`() = runTest(testDispatcher) {
    val journalEntryReader = FakeJournalEntryReader()
    val viewModel = createViewModel(journalEntryReader = journalEntryReader)
    val collectJob = CoroutineScope(testDispatcher).launch { viewModel.eligibility.collect {} }

    journalEntryReader.emit(emptyList())
    testDispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.ready().selectableDays.isEmpty())
    collectJob.cancel()
  }

  @Test
  fun `selectableDaysはHostedでは当日と成功済みの日を除いた前日以前の記録日だけになる`() = runTest(testDispatcher) {
    val journalEntryReader = FakeJournalEntryReader()
    val viewModel = createViewModel(
      integrationRepository = FakeAnalysisIntegrationRepository(AnalysisIntegration.HOSTED),
      journalEntryReader = journalEntryReader,
      executionRepository = FakeAnalysisExecutionRepository(
        AnalysisExecutionState(hostedSuccessfulDays = setOf(LocalDate.of(2026, 8, 28))),
      ),
      currentZoneId = MutableStateFlow(ZoneOffset.UTC),
      currentDate = { LocalDate.of(2026, 8, 30) },
    )
    val collectJob = CoroutineScope(testDispatcher).launch { viewModel.eligibility.collect {} }

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
      viewModel.ready().selectableDays,
    )
    collectJob.cancel()
  }

  @Test
  fun `Hosted成功済み日はAnalysisResult削除後もselectableDaysへ戻らない`() = runTest(testDispatcher) {
    val journalEntryReader = FakeJournalEntryReader(listOf(entry("2026-08-29T05:00:00Z")))
    val executionRepository = FakeAnalysisExecutionRepository(
      AnalysisExecutionState(hostedSuccessfulDays = setOf(LocalDate.of(2026, 8, 29))),
    )
    val viewModel = createViewModel(
      integrationRepository = FakeAnalysisIntegrationRepository(AnalysisIntegration.HOSTED),
      journalEntryReader = journalEntryReader,
      executionRepository = executionRepository,
      currentDate = { LocalDate.of(2026, 8, 30) },
    )
    val collectJob = CoroutineScope(testDispatcher).launch { viewModel.eligibility.collect {} }
    testDispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.ready().selectableDays.isEmpty())
    collectJob.cancel()
  }

  @Test
  fun `Custom WebhookはHosted成功済み日でもselectableDaysへ含める`() = runTest(testDispatcher) {
    val journalEntryReader = FakeJournalEntryReader(listOf(entry("2026-08-29T05:00:00Z")))
    val executionRepository = FakeAnalysisExecutionRepository(
      AnalysisExecutionState(hostedSuccessfulDays = setOf(LocalDate.of(2026, 8, 29))),
    )
    val viewModel = createViewModel(
      integrationRepository = FakeAnalysisIntegrationRepository(AnalysisIntegration.CUSTOM_WEBHOOK),
      journalEntryReader = journalEntryReader,
      executionRepository = executionRepository,
      currentDate = { LocalDate.of(2026, 8, 30) },
    )
    val collectJob = CoroutineScope(testDispatcher).launch { viewModel.eligibility.collect {} }
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(setOf(LocalDate.of(2026, 8, 29)), viewModel.ready().selectableDays)
    collectJob.cancel()
  }

  @Test
  fun `履歴の解析可否は記録の初回読み込み前には確定しない`() = runTest(testDispatcher) {
    val entries = MutableSharedFlow<List<JournalEntry>>(replay = 1)
    val viewModel = createViewModel(journalEntryReader = JournalEntryReader { entries })
    collectorScope.launch { viewModel.eligibility.collect {} }
    testDispatcher.scheduler.runCurrent()

    assertEquals(ManualAnalysisEligibility.Checking, viewModel.eligibility.value)
    entries.emit(emptyList())
    testDispatcher.scheduler.runCurrent()
    assertEquals(emptyMap<LocalDate, ManualAnalysisAvailability>(), viewModel.availabilities())
  }

  @Test
  fun `履歴のHosted解析は昨日と6日前を許可し当日と7日前と成功済み日を区別する`() = runTest(testDispatcher) {
    val journalEntryReader = FakeJournalEntryReader(
      listOf(23, 24, 28, 29, 30).map { entry("2026-08-${it}T05:00:00Z") },
    )
    val viewModel = createViewModel(
      journalEntryReader = journalEntryReader,
      integrationRepository = FakeAnalysisIntegrationRepository(AnalysisIntegration.HOSTED),
      executionRepository = FakeAnalysisExecutionRepository(
        AnalysisExecutionState(hostedSuccessfulDays = setOf(LocalDate.of(2026, 8, 28))),
      ),
      currentDate = { LocalDate.of(2026, 8, 30) },
    )
    collectorScope.launch { viewModel.eligibility.collect {} }
    testDispatcher.scheduler.runCurrent()

    val reasons = viewModel.availabilities()
    assertEquals(ManualAnalysisAvailability.OUTSIDE_PERIOD, reasons[LocalDate.of(2026, 8, 23)])
    assertEquals(ManualAnalysisAvailability.AVAILABLE, reasons.getValue(LocalDate.of(2026, 8, 24)))
    assertEquals(ManualAnalysisAvailability.ALREADY_COMPLETED, reasons[LocalDate.of(2026, 8, 28)])
    assertEquals(ManualAnalysisAvailability.AVAILABLE, reasons.getValue(LocalDate.of(2026, 8, 29)))
    assertEquals(ManualAnalysisAvailability.OUTSIDE_PERIOD, reasons[LocalDate.of(2026, 8, 30)])
    assertFalse(LocalDate.of(2026, 8, 27) in reasons)
  }

  @Test
  fun `履歴のCustom Webhook解析はHostedの期間と成功済み制限を受けず解析先なしを区別する`() =
    runTest(testDispatcher) {
      val integrationRepository = FakeAnalysisIntegrationRepository(AnalysisIntegration.CUSTOM_WEBHOOK)
      val viewModel = createViewModel(
        integrationRepository = integrationRepository,
        journalEntryReader = FakeJournalEntryReader(
          listOf(entry("2026-08-30T16:00:00Z"), entry("2026-08-01T16:00:00Z")),
        ),
        executionRepository = FakeAnalysisExecutionRepository(
          AnalysisExecutionState(hostedSuccessfulDays = setOf(LocalDate.of(2026, 8, 31))),
        ),
        currentZoneId = MutableStateFlow(ZoneId.of("Asia/Tokyo")),
        currentDate = { LocalDate.of(2026, 8, 31) },
      )
      collectorScope.launch { viewModel.eligibility.collect {} }
      testDispatcher.scheduler.runCurrent()

      assertEquals(
        mapOf(LocalDate.of(2026, 8, 31) to ManualAnalysisAvailability.AVAILABLE, LocalDate.of(2026, 8, 2) to ManualAnalysisAvailability.AVAILABLE),
        viewModel.availabilities(),
      )
      integrationRepository.set(AnalysisIntegration.NONE)
      testDispatcher.scheduler.runCurrent()
      assertEquals(
        mapOf(
          LocalDate.of(2026, 8, 31) to ManualAnalysisAvailability.INTEGRATION_UNAVAILABLE,
          LocalDate.of(2026, 8, 2) to ManualAnalysisAvailability.INTEGRATION_UNAVAILABLE,
        ),
        viewModel.availabilities(),
      )
    }

  @Test
  fun `履歴は解析中の対象日と別の日を区別し恒久的な制限を優先して完了後に更新する`() = runTest(testDispatcher) {
    val pendingEntries = CompletableDeferred<List<JournalEntry>>()
    val analyzer = FakePeriodAnalyzer { success(integration = AnalysisIntegration.HOSTED) }
    val viewModel = createViewModel(
      integrationRepository = FakeAnalysisIntegrationRepository(AnalysisIntegration.HOSTED),
      journalEntryReader = FakeJournalEntryReader(
        listOf(23, 27, 28, 29, 30).map { entry("2026-08-${it}T05:00:00Z") },
      ),
      entryReader = PeriodJournalEntryReader { _, _ -> pendingEntries.await() },
      analyzer = analyzer,
      executionRepository = FakeAnalysisExecutionRepository(
        AnalysisExecutionState(hostedSuccessfulDays = setOf(LocalDate.of(2026, 8, 28))),
      ),
      currentDate = { LocalDate.of(2026, 8, 30) },
    )
    collectorScope.launch { viewModel.eligibility.collect {} }
    testDispatcher.scheduler.runCurrent()
    viewModel.analyze(LocalDate.of(2026, 8, 29))
    testDispatcher.scheduler.runCurrent()

    val reasons = viewModel.availabilities()
    assertEquals(ManualAnalysisAvailability.RUNNING_DAY, reasons[LocalDate.of(2026, 8, 29)])
    assertEquals(ManualAnalysisAvailability.RUNNING_OTHER_DAY, reasons[LocalDate.of(2026, 8, 27)])
    assertEquals(ManualAnalysisAvailability.ALREADY_COMPLETED, reasons[LocalDate.of(2026, 8, 28)])
    assertEquals(ManualAnalysisAvailability.OUTSIDE_PERIOD, reasons[LocalDate.of(2026, 8, 30)])
    assertEquals(ManualAnalysisAvailability.OUTSIDE_PERIOD, reasons[LocalDate.of(2026, 8, 23)])
    viewModel.analyze(LocalDate.of(2026, 8, 27))
    pendingEntries.complete(listOf(entry("2026-08-29T05:00:00Z")))
    testDispatcher.scheduler.runCurrent()

    assertEquals(1, analyzer.callCount)
    assertFalse(viewModel.state.value.isRunning)
    assertEquals(
      ManualAnalysisAvailability.ALREADY_COMPLETED,
      viewModel.availabilities()[LocalDate.of(2026, 8, 29)],
    )
    assertEquals(ManualAnalysisAvailability.AVAILABLE, viewModel.availabilities().getValue(LocalDate.of(2026, 8, 27)))
  }

  @Test
  fun `analyzeは選択日を端末timezoneの半開区間へ変換してentryを取得しanalyzerへ渡す`() = runTest(testDispatcher) {
    val analyzer = FakePeriodAnalyzer { success() }
    val reader = FakePeriodJournalEntryReader(listOf(entry("2026-08-30T05:00:00Z")))
    val viewModel = createViewModel(analyzer = analyzer, entryReader = reader, currentZoneId = MutableStateFlow(ZoneOffset.UTC))

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
    val zone = MutableStateFlow<ZoneId>(ZoneOffset.UTC)
    val viewModel = createViewModel(analyzer = analyzer, currentZoneId = zone)

    // ViewModel生成後にtimezoneが変わる状況(移動など)を再現する。
    zone.value = ZoneId.of("Asia/Tokyo")
    currentDateProvider.refresh()
    viewModel.analyze(LocalDate.of(2026, 8, 30))
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(Instant.parse("2026-08-29T15:00:00Z"), analyzer.lastPeriodStart)
    assertEquals(Instant.parse("2026-08-30T15:00:00Z"), analyzer.lastPeriodEnd)
  }

  @Test
  fun `通常のタイムゾーン変更と記録追加後も履歴の日付とHosted解析可否と送信記録が一致する`() =
    runTest(testDispatcher) {
      val zone = MutableStateFlow<ZoneId>(ZoneOffset.UTC)
      val now = Instant.parse("2026-08-30T20:00:00Z")
      val entries = MutableStateFlow(
        listOf(
          entry("2026-08-28T16:00:00Z", id = 1),
          entry("2026-08-29T10:00:00Z", id = 2),
          entry("2026-08-29T16:00:00Z", id = 3),
          entry("2026-08-30T10:00:00Z", id = 4),
        ),
      )
      val journalReader = JournalEntryReader { entries }
      val resultReader = FakeAnalysisResultReader().apply { emit(emptyList()) }
      val executionRepository = FakeAnalysisExecutionRepository()
      val analyzer = FakePeriodAnalyzer { success(integration = AnalysisIntegration.HOSTED) }
      val sharedDateProvider = CurrentDateProvider(now = { now }, zoneId = { zone.value })
      val history = JournalHistoryViewModel(
        reader = journalReader,
        analysisResultReader = resultReader,
        analysisExecutionRepository = executionRepository,
        deleter = JournalEntryDeleter {},
        currentDateProvider = sharedDateProvider,
      )
      val analysis = createViewModel(
        journalEntryReader = journalReader,
        integrationRepository = FakeAnalysisIntegrationRepository(AnalysisIntegration.HOSTED),
        entryReader = PeriodJournalEntryReader { start, end ->
          entries.value.filter { it.timestamp >= start && it.timestamp < end }
        },
        analyzer = analyzer,
        executionRepository = executionRepository,
        currentZoneId = zone,
        dateProvider = sharedDateProvider,
      )
      collectorScope.launch { history.uiState.collect {} }
      collectorScope.launch { analysis.eligibility.collect {} }
      testDispatcher.scheduler.runCurrent()
      history.selectDate(LocalDate.of(2026, 8, 29))
      testDispatcher.scheduler.runCurrent()
      val originalContent = history.uiState.value as JournalHistoryUiState.Content
      assertEquals(setOf(2L, 3L), originalContent.itemsOn(originalContent.selectedDate).map { it.id }.toSet())
      assertEquals(
        ManualAnalysisAvailability.OUTSIDE_PERIOD,
        analysis.availabilities()[LocalDate.of(2026, 8, 30)],
      )

      zone.value = ZoneId.of("Asia/Tokyo")
      currentDateProvider.refresh()
      testDispatcher.scheduler.runCurrent()
      val changedContent = history.uiState.value as JournalHistoryUiState.Content
      assertEquals(LocalDate.of(2026, 8, 31), changedContent.today)
      assertEquals(LocalDate.of(2026, 8, 29), changedContent.earliestDate)
      assertEquals(setOf(1L, 2L), changedContent.itemsOn(changedContent.selectedDate).map { it.id }.toSet())
      assertEquals(changedContent.zoneId, analysis.ready().calendarDate.zoneId)
      assertEquals(setOf(LocalDate.of(2026, 8, 29), LocalDate.of(2026, 8, 30)), analysis.ready().selectableDays)

      entries.value += entry("2026-08-30T18:00:00Z", id = 5)
      history.selectDate(LocalDate.of(2026, 8, 30))
      testDispatcher.scheduler.runCurrent()
      val displayedContent = history.uiState.value as JournalHistoryUiState.Content
      val displayedIds = displayedContent.itemsOn(displayedContent.selectedDate).map { it.id }.toSet()
      assertEquals(setOf(3L, 4L), displayedIds)
      assertEquals(ManualAnalysisAvailability.AVAILABLE, analysis.availabilities().getValue(displayedContent.selectedDate))
      analysis.analyze(displayedContent.selectedDate, displayedContent.calendarDate)
      testDispatcher.scheduler.runCurrent()

      assertEquals(displayedIds, requireNotNull(analyzer.lastEntries).map { it.id }.toSet())
      assertEquals(Instant.parse("2026-08-29T15:00:00Z"), analyzer.lastPeriodStart)
      assertEquals(Instant.parse("2026-08-30T15:00:00Z"), analyzer.lastPeriodEnd)
      assertEquals(setOf(displayedContent.selectedDate), executionRepository.state.value.hostedSuccessfulDays)
      assertEquals(
        ManualAnalysisAvailability.ALREADY_COMPLETED,
        analysis.availabilities()[displayedContent.selectedDate],
      )
      assertEquals(displayedContent.selectedDate, (history.uiState.value as JournalHistoryUiState.Content).selectedDate)

      zone.value = ZoneOffset.UTC
      currentDateProvider.refresh()
      testDispatcher.scheduler.runCurrent()
      assertEquals(
        ManualAnalysisAvailability.ALREADY_COMPLETED,
        analysis.availabilities()[displayedContent.selectedDate],
      )
      assertFalse(displayedContent.selectedDate in analysis.ready().selectableDays)
    }

  @Test
  fun `更新前の履歴タイムゾーンでの解析は送信せず開始後のタイムゾーン変更は送信期間を変更しない`() = runTest(testDispatcher) {
    val zone = MutableStateFlow<ZoneId>(ZoneOffset.UTC)
    val pendingEntries = CompletableDeferred<List<JournalEntry>>()
    val entryReader = PeriodJournalEntryReader { _, _ -> pendingEntries.await() }
    val analyzer = FakePeriodAnalyzer { success() }
    val viewModel = createViewModel(currentZoneId = zone, entryReader = entryReader, analyzer = analyzer)
    zone.value = ZoneId.of("Asia/Tokyo")
    currentDateProvider.refresh()

    viewModel.analyze(LocalDate.of(2026, 8, 30), CalendarDate(LocalDate.of(2026, 9, 1), ZoneOffset.UTC))
    testDispatcher.scheduler.runCurrent()
    assertEquals(0, analyzer.callCount)
    assertFalse(viewModel.state.value.isRunning)

    viewModel.analyze(LocalDate.of(2026, 8, 30), currentDateProvider.refresh())
    testDispatcher.scheduler.runCurrent()
    assertTrue(viewModel.state.value.isRunning)
    zone.value = ZoneOffset.UTC
    currentDateProvider.refresh()
    pendingEntries.complete(listOf(entry("2026-08-30T05:00:00Z")))
    testDispatcher.scheduler.runCurrent()
    assertEquals(1, analyzer.callCount)
    assertEquals(Instant.parse("2026-08-29T15:00:00Z"), analyzer.lastPeriodStart)
    assertEquals(Instant.parse("2026-08-30T15:00:00Z"), analyzer.lastPeriodEnd)
    assertFalse(viewModel.state.value.isRunning)
  }

  @Test
  fun `analyze成功時はresponseのperiod・analyzedAt・textでAnalysisResultを保存する`() = runTest(testDispatcher) {
    val analyzer = FakePeriodAnalyzer { success("今日は穏やかでした") }
    val writer = FakeAnalysisResultWriter()
    val viewModel = createViewModel(analyzer = analyzer, writer = writer, currentZoneId = MutableStateFlow(ZoneOffset.UTC))
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
  fun `Custom Webhook成功時はresponse periodに関係なく実際に送った記録idを保存する`() =
    runTest(testDispatcher) {
      val executionRepository = FakeAnalysisExecutionRepository()
      val viewModel = createViewModel(
        entryReader = FakePeriodJournalEntryReader(
          listOf(entry("2026-08-30T00:01:00Z", id = 11), entry("2026-08-30T12:00:00Z", id = 12)),
        ),
        executionRepository = executionRepository,
        currentZoneId = MutableStateFlow(ZoneOffset.UTC),
      )

      viewModel.analyze(LocalDate.of(2026, 8, 30))
      testDispatcher.scheduler.advanceUntilIdle()

      assertEquals(setOf(11L, 12L), executionRepository.state.value.entryIdsByResultId.getValue(1L))
      assertTrue(executionRepository.state.value.hostedSuccessfulDays.isEmpty())
    }

  @Test
  fun `Hosted成功時は対象日を成功済みとして保存する`() = runTest(testDispatcher) {
    val executionRepository = FakeAnalysisExecutionRepository()
    val viewModel = createViewModel(
      analyzer = FakePeriodAnalyzer { success(integration = AnalysisIntegration.HOSTED) },
      executionRepository = executionRepository,
    )

    viewModel.analyze(LocalDate.of(2026, 8, 30))
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(
      setOf(LocalDate.of(2026, 8, 30)),
      executionRepository.state.value.hostedSuccessfulDays,
    )
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
  fun `RATE_LIMITEDでは保存済みAPI keyからSupport IDを生成する`() = runTest(testDispatcher) {
    val analyzer = FakePeriodAnalyzer { PeriodAnalysisOutcome.Failure.RATE_LIMITED }
    val credentialsRepository = object : HostedCredentialsRepository {
      override suspend fun apiKey(): String = "jpk_test"
      override suspend fun store(apiKey: String) = Unit
      override suspend fun clear() = Unit
    }
    val viewModel = createViewModel(
      analyzer = analyzer,
      hostedCredentialsRepository = credentialsRepository,
    )
    val results = collectRunResults(viewModel)

    viewModel.analyze(LocalDate.of(2026, 8, 30))
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(
      listOf(
        AnalysisRunResult.Failed(
          PeriodAnalysisOutcome.Failure.RATE_LIMITED,
          LocalDate.of(2026, 8, 30),
          "jpk_test".sha256Hex(),
        ),
      ),
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
    val viewModel = createViewModel(analyzer = analyzer, currentZoneId = MutableStateFlow(ZoneOffset.UTC))
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
  fun `解析実行の前後で実行状態が切り替わる`() = runTest(testDispatcher) {
    val viewModel = createViewModel()
    assertFalse(viewModel.state.value.isRunning)

    viewModel.analyze(LocalDate.of(2026, 8, 30))
    assertTrue(viewModel.state.value.isRunning)

    testDispatcher.scheduler.advanceUntilIdle()

    assertFalse(viewModel.state.value.isRunning)
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
    val collectJob = collectorScope.launch { viewModel.state.collect { state -> whileStopped += state.notifications.map { it.result } } }
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

  @Test
  fun `日時更新だけでHostedの当日と昨日と6日前と7日前の可否が更新される`() = runTest(testDispatcher) {
    var now = Instant.parse("2026-08-30T23:59:59Z")
    val dates = CurrentDateProvider(now = { now }, zoneId = { ZoneOffset.UTC })
    val viewModel = createViewModel(
      integrationRepository = FakeAnalysisIntegrationRepository(AnalysisIntegration.HOSTED),
      journalEntryReader = FakeJournalEntryReader(listOf(24, 25, 30).map { entry("2026-08-${it}T05:00:00Z") }),
      dateProvider = dates,
    )
    collectorScope.launch { viewModel.eligibility.collect {} }
    testDispatcher.scheduler.runCurrent()
    assertEquals(ManualAnalysisAvailability.OUTSIDE_PERIOD, viewModel.ready().availabilityFor(LocalDate.of(2026, 8, 30)))
    assertEquals(ManualAnalysisAvailability.AVAILABLE, viewModel.ready().availabilityFor(LocalDate.of(2026, 8, 24)))

    now = Instant.parse("2026-08-31T00:00:00Z")
    dates.refresh()
    testDispatcher.scheduler.runCurrent()

    assertEquals(ManualAnalysisAvailability.AVAILABLE, viewModel.ready().availabilityFor(LocalDate.of(2026, 8, 30)))
    assertEquals(ManualAnalysisAvailability.OUTSIDE_PERIOD, viewModel.ready().availabilityFor(LocalDate.of(2026, 8, 24)))
    assertEquals(setOf(LocalDate.of(2026, 8, 25), LocalDate.of(2026, 8, 30)), viewModel.ready().selectableDays)
  }

  @Test
  fun `可否の購読前でも実行開始時にHostedの対象期間と成功済み日を確認し送信しない`() = runTest(testDispatcher) {
    val analyzer = FakePeriodAnalyzer { success() }
    val viewModel = createViewModel(
      integrationRepository = FakeAnalysisIntegrationRepository(AnalysisIntegration.HOSTED),
      analyzer = analyzer,
      executionRepository = FakeAnalysisExecutionRepository(
        AnalysisExecutionState(hostedSuccessfulDays = setOf(LocalDate.of(2026, 8, 30))),
      ),
      currentDate = { LocalDate.of(2026, 8, 31) },
    )
    assertEquals(ManualAnalysisEligibility.Checking, viewModel.eligibility.value)
    viewModel.analyze(LocalDate.of(2026, 8, 30))
    testDispatcher.scheduler.runCurrent()
    assertEquals(AnalysisRunResult.Rejected(ManualAnalysisAvailability.ALREADY_COMPLETED), viewModel.state.value.notifications.single().result)
    viewModel.analyze(LocalDate.of(2026, 8, 31))
    testDispatcher.scheduler.runCurrent()
    assertEquals(AnalysisRunResult.Rejected(ManualAnalysisAvailability.OUTSIDE_PERIOD), viewModel.state.value.notifications.last().result)
    viewModel.analyze(LocalDate.of(2026, 8, 24))
    testDispatcher.scheduler.runCurrent()
    assertEquals(AnalysisRunResult.Rejected(ManualAnalysisAvailability.OUTSIDE_PERIOD), viewModel.state.value.notifications.last().result)
    assertEquals(0, analyzer.callCount)
  }

  @Test
  fun `日付変更前の表示からの要求は送信せず最新の日付でHosted期間を判定する`() = runTest(testDispatcher) {
    var now = Instant.parse("2026-08-30T23:59:59Z")
    val dates = CurrentDateProvider(now = { now }, zoneId = { ZoneOffset.UTC })
    val analyzer = FakePeriodAnalyzer { success() }
    val viewModel = createViewModel(
      integrationRepository = FakeAnalysisIntegrationRepository(AnalysisIntegration.HOSTED),
      analyzer = analyzer,
      dateProvider = dates,
    )
    val displayedDate = dates.state.value
    now = Instant.parse("2026-08-31T00:00:00Z")
    viewModel.analyze(LocalDate.of(2026, 8, 24), displayedDate)
    testDispatcher.scheduler.runCurrent()
    assertFalse(viewModel.state.value.isRunning)
    assertTrue(viewModel.state.value.notifications.isEmpty())
    viewModel.analyze(LocalDate.of(2026, 8, 24), dates.state.value)
    testDispatcher.scheduler.runCurrent()
    assertEquals(AnalysisRunResult.Rejected(ManualAnalysisAvailability.OUTSIDE_PERIOD), viewModel.state.value.notifications.single().result)
    assertEquals(0, analyzer.callCount)
  }

  @Test
  fun `初期状態と閲覧を購読しても解析実行用依存関係は生成されない`() = runTest(testDispatcher) {
    var journalReaderCount = 0
    var executionRepositoryCount = 0
    var periodReaderCount = 0
    var runnerCount = 0
    var credentialsCount = 0
    val integration = FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE)
    val executionRepository = FakeAnalysisExecutionRepository()
    val analyzer = FakePeriodAnalyzer { success() }
    val viewModel = ManualAnalysisViewModel(
      analysisIntegrationRepository = integration,
      journalEntryReader = { journalReaderCount++; FakeJournalEntryReader(listOf(entry("2026-08-30T05:00:00Z"))) },
      periodJournalEntryReader = { periodReaderCount++; FakePeriodJournalEntryReader(listOf(entry("2026-08-30T05:00:00Z"))) },
      periodAnalysisRunner = { runnerCount++; PeriodAnalysisRunner(analyzer, FakeAnalysisResultWriter(), executionRepository) },
      analysisExecutionRepository = { executionRepositoryCount++; executionRepository },
      hostedCredentialsRepository = { credentialsCount++; error("認証情報は不要") },
      currentDateProvider = CurrentDateProvider(zoneId = { ZoneOffset.UTC }),
    )
    collectorScope.launch { viewModel.state.collect {} }
    testDispatcher.scheduler.runCurrent()
    assertEquals(listOf(0, 0, 0, 0, 0), listOf(journalReaderCount, executionRepositoryCount, periodReaderCount, runnerCount, credentialsCount))

    collectorScope.launch { viewModel.eligibility.collect {} }
    testDispatcher.scheduler.runCurrent()
    assertEquals(ManualAnalysisAvailability.INTEGRATION_UNAVAILABLE, viewModel.ready().availabilityFor(LocalDate.of(2026, 8, 30)))
    assertTrue(viewModel.ready().selectableDays.isEmpty())
    assertEquals(listOf(1, 1, 0, 0, 0), listOf(journalReaderCount, executionRepositoryCount, periodReaderCount, runnerCount, credentialsCount))

    integration.set(AnalysisIntegration.CUSTOM_WEBHOOK)
    testDispatcher.scheduler.runCurrent()
    assertTrue(viewModel.ready().availabilityFor(LocalDate.of(2026, 8, 30)).isAvailable)
    assertEquals(0, runnerCount)
    viewModel.analyze(LocalDate.of(2026, 8, 30), viewModel.ready().calendarDate)
    testDispatcher.scheduler.runCurrent()
    assertEquals(1, periodReaderCount)
    assertEquals(1, runnerCount)
    assertEquals(0, credentialsCount)
  }

  @Test
  fun `両入口の共有ViewModelは対象日を共有し画面の購読終了後も成功と失敗を保持する`() = runTest(testDispatcher) {
    for (outcome in listOf(success(), PeriodAnalysisOutcome.Failure.NETWORK)) {
      val pendingEntries = CompletableDeferred<List<JournalEntry>>()
      val store = ViewModelStore()
      val factory = viewModelFactory {
        initializer { createViewModel(entryReader = PeriodJournalEntryReader { _, _ -> pendingEntries.await() }, analyzer = FakePeriodAnalyzer { outcome }) }
      }
      val historyEntry = ViewModelLazy(ManualAnalysisViewModel::class, { store }, { factory })
      val reflectionEntry = ViewModelLazy(ManualAnalysisViewModel::class, { store }, { factory })
      assertSame(historyEntry.value, reflectionEntry.value)
      val calendarDate = currentDateProvider.refresh()
      historyEntry.value.analyze(LocalDate.of(2026, 8, 30), calendarDate)
      reflectionEntry.value.analyze(LocalDate.of(2026, 8, 29), calendarDate)
      testDispatcher.scheduler.runCurrent()
      assertEquals(LocalDate.of(2026, 8, 30), reflectionEntry.value.state.value.runningDay)

      pendingEntries.complete(listOf(entry("2026-08-30T05:00:00Z")))
      testDispatcher.scheduler.runCurrent()
      val recreated = ViewModelLazy(ManualAnalysisViewModel::class, { store }, { factory })
      assertSame(historyEntry.value, recreated.value)
      assertFalse(recreated.value.state.value.isRunning)
      assertEquals(1, recreated.value.state.value.notifications.size)
      val result = recreated.value.state.value.notifications.single().result
      if (outcome is PeriodAnalysisOutcome.Success) {
        assertEquals(AnalysisRunResult.Succeeded(1L), result)
      } else {
        assertEquals(AnalysisRunResult.Failed(PeriodAnalysisOutcome.Failure.NETWORK, LocalDate.of(2026, 8, 30)), result)
      }
      store.clear()
    }
  }

  @Test
  fun `通知を受信して表示前に画面が再生成されても結果を保持し表示完了した通知だけを消す`() = runTest(testDispatcher) {
    val store = ViewModelStore()
    val factory = viewModelFactory { initializer { createViewModel() } }
    val original = ViewModelLazy(ManualAnalysisViewModel::class, { store }, { factory }).value
    original.analyze(LocalDate.of(2026, 8, 30))
    testDispatcher.scheduler.runCurrent()
    val received = original.state.value.notifications.first()
    original.analyze(LocalDate.of(2026, 8, 30))
    testDispatcher.scheduler.runCurrent()
    val recreated = ViewModelLazy(ManualAnalysisViewModel::class, { store }, { factory }).value
    assertEquals(2, recreated.state.value.notifications.size)
    assertEquals(received, recreated.state.value.notifications.first())
    recreated.notificationShown(received.id)
    assertEquals(1, recreated.state.value.notifications.size)
    assertTrue(received.id != recreated.state.value.notifications.single().id)
    recreated.notificationShown(received.id)
    assertEquals(1, recreated.state.value.notifications.size)
    recreated.notificationShown(recreated.state.value.notifications.single().id)
    assertTrue(recreated.state.value.notifications.isEmpty())
    store.clear()
  }

  @Test
  fun `プロセス再生成相当の新しいStoreには実行中と未通知結果を引き継がない`() = runTest(testDispatcher) {
    val factory = viewModelFactory { initializer { createViewModel() } }
    val previousStore = ViewModelStore()
    val previous = ViewModelLazy(ManualAnalysisViewModel::class, { previousStore }, { factory }).value
    previous.analyze(LocalDate.of(2026, 8, 30))
    testDispatcher.scheduler.runCurrent()
    assertEquals(1, previous.state.value.notifications.size)
    previousStore.clear()
    val newStore = ViewModelStore()
    val recreated = ViewModelLazy(ManualAnalysisViewModel::class, { newStore }, { factory }).value
    assertFalse(recreated.state.value.isRunning)
    assertTrue(recreated.state.value.notifications.isEmpty())
    newStore.clear()
  }

  @Test
  fun `6日前の解析中に深夜0時を越えても対象日自身の解析中を判別できる`() = runTest(testDispatcher) {
    var now = Instant.parse("2026-08-30T23:59:59Z")
    val dates = CurrentDateProvider(now = { now }, zoneId = { ZoneOffset.UTC })
    val pendingEntries = CompletableDeferred<List<JournalEntry>>()
    val day = LocalDate.of(2026, 8, 24)
    val viewModel = createViewModel(
      integrationRepository = FakeAnalysisIntegrationRepository(AnalysisIntegration.HOSTED),
      journalEntryReader = FakeJournalEntryReader(listOf(entry("2026-08-24T05:00:00Z"))),
      entryReader = PeriodJournalEntryReader { _, _ -> pendingEntries.await() },
      analyzer = FakePeriodAnalyzer { success(integration = AnalysisIntegration.HOSTED) },
      dateProvider = dates,
    )
    collectorScope.launch { viewModel.eligibility.collect {} }
    testDispatcher.scheduler.runCurrent()
    viewModel.analyze(day, dates.state.value)
    testDispatcher.scheduler.runCurrent()
    now = Instant.parse("2026-08-31T00:00:00Z")
    dates.refresh()
    testDispatcher.scheduler.runCurrent()
    assertEquals(ManualAnalysisAvailability.RUNNING_DAY, viewModel.ready().availabilityFor(day))
    pendingEntries.complete(listOf(entry("2026-08-24T05:00:00Z")))
    testDispatcher.scheduler.runCurrent()
    assertEquals(ManualAnalysisAvailability.ALREADY_COMPLETED, viewModel.ready().availabilityFor(day))
    assertEquals(1, viewModel.state.value.notifications.size)
  }

  private lateinit var currentDateProvider: CurrentDateProvider

  private fun createViewModel(
    integrationRepository: AnalysisIntegrationRepository =
      FakeAnalysisIntegrationRepository(AnalysisIntegration.CUSTOM_WEBHOOK),
    journalEntryReader: JournalEntryReader = FakeJournalEntryReader(),
    entryReader: PeriodJournalEntryReader = FakePeriodJournalEntryReader(listOf(entry("2026-08-30T05:00:00Z"))),
    analyzer: PeriodAnalyzer = FakePeriodAnalyzer { success() },
    writer: AnalysisResultWriter = FakeAnalysisResultWriter(),
    executionRepository: AnalysisExecutionRepository = FakeAnalysisExecutionRepository(),
    hostedCredentialsRepository: HostedCredentialsRepository = object : HostedCredentialsRepository {
      override suspend fun apiKey(): String? = null
      override suspend fun store(apiKey: String) = Unit
      override suspend fun clear() = Unit
    },
    currentZoneId: StateFlow<ZoneId> = MutableStateFlow(ZoneOffset.UTC),
    currentDate: (ZoneId) -> LocalDate = { LocalDate.of(2026, 9, 1) },
    dateProvider: CurrentDateProvider = CurrentDateProvider(
      now = { currentDate(currentZoneId.value).atStartOfDay(currentZoneId.value).toInstant() },
      zoneId = { currentZoneId.value },
    ),
  ): ManualAnalysisViewModel {
    currentDateProvider = dateProvider
    return ManualAnalysisViewModel(
      analysisIntegrationRepository = integrationRepository,
      journalEntryReader = { journalEntryReader },
      periodJournalEntryReader = { entryReader },
      periodAnalysisRunner = { PeriodAnalysisRunner(analyzer, writer, executionRepository) },
      analysisExecutionRepository = { executionRepository },
      hostedCredentialsRepository = { hostedCredentialsRepository },
      currentDateProvider = dateProvider,
    )
  }

  private fun ManualAnalysisViewModel.analyze(day: LocalDate) = analyze(day, currentDateProvider.refresh())
  private fun ManualAnalysisViewModel.ready() = eligibility.value as ManualAnalysisEligibility.Ready
  private fun ManualAnalysisViewModel.availabilities() = ready().recordedDays.associateWith { ready().availabilityFor(it) }

  private fun entry(at: String, id: Long = 0) = JournalEntry(
    id = id,
    timestamp = Instant.parse(at),
    note = "メモ",
    source = JournalSource.APP,
  )

  private fun collectRunResults(viewModel: ManualAnalysisViewModel): List<AnalysisRunResult> {
    val results = mutableListOf<AnalysisRunResult>()
    collectorScope.launch {
      viewModel.state.map { it.notifications.firstOrNull() }.distinctUntilChanged().filterNotNull().collect {
        results += it.result
        viewModel.notificationShown(it.id)
      }
    }
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

  private class FakeAnalysisExecutionRepository(
    initial: AnalysisExecutionState = AnalysisExecutionState(),
  ) : AnalysisExecutionRepository {
    override val state = MutableStateFlow(initial)

    override suspend fun recordSuccess(
      resultId: Long,
      entryIds: Set<Long>,
      hostedSuccessfulDay: LocalDate?,
    ) {
      state.value = state.value.copy(
        entryIdsByResultId = state.value.entryIdsByResultId + (resultId to entryIds),
        hostedSuccessfulDays = state.value.hostedSuccessfulDays + listOfNotNull(hostedSuccessfulDay),
      )
    }

    override suspend fun removeResult(resultId: Long) {
      state.value = state.value.copy(entryIdsByResultId = state.value.entryIdsByResultId - resultId)
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
      analysisDate: LocalDate?,
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
      analysisDate: LocalDate?,
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
