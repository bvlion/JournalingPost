package info.bvlion.journalingpost

import info.bvlion.journalingpost.analysis.AnalysisExecutionRepository
import info.bvlion.journalingpost.analysis.AnalysisExecutionState
import info.bvlion.journalingpost.analysis.AnalysisHistoryUiState
import info.bvlion.journalingpost.analysis.AnalysisResult
import info.bvlion.journalingpost.analysis.AnalysisResultDeleter
import info.bvlion.journalingpost.analysis.AnalysisResultPersistenceListener
import info.bvlion.journalingpost.analysis.AnalysisResultReader
import info.bvlion.journalingpost.analysis.AnalysisResultWriter
import info.bvlion.journalingpost.analysis.PeriodAnalysisOutcome
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
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
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

  // Channel由来の実行・削除結果は購読者がいる間だけ流れるため、テスト中はこのscopeで購読し続ける。
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
  fun `deleteResultは指定したidだけを削除し送信記録との対応だけを消す`() = runTest(testDispatcher) {
    val deleter = FakeAnalysisResultDeleter()
    val executionRepository = FakeAnalysisExecutionRepository(
      AnalysisExecutionState(
        entryIdsByResultId = mapOf(2L to setOf(10L)),
        hostedSuccessfulDays = setOf(LocalDate.of(2026, 8, 30)),
      ),
    )
    val viewModel = createViewModel(deleter = deleter, executionRepository = executionRepository)
    val successes = mutableListOf<Unit>()
    val failures = mutableListOf<Unit>()
    collectorScope.launch { viewModel.deleteSuccesses.collect { successes += it } }
    collectorScope.launch { viewModel.deleteFailures.collect { failures += it } }

    viewModel.deleteResult(2)
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(listOf(2L), deleter.deletedIds)
    assertTrue(executionRepository.state.value.entryIdsByResultId.isEmpty())
    assertEquals(setOf(LocalDate.of(2026, 8, 30)), executionRepository.state.value.hostedSuccessfulDays)
    assertEquals(1, successes.size)
    assertTrue(failures.isEmpty())
  }

  @Test
  fun `ふりかえり削除に失敗すると未処理例外にならず削除失敗を1度だけ通知する`() = runTest(testDispatcher) {
    val viewModel = createViewModel(deleter = FakeAnalysisResultDeleter(failNextDeletes = 1))
    val successes = mutableListOf<Unit>()
    val failures = mutableListOf<Unit>()
    collectorScope.launch { viewModel.deleteSuccesses.collect { successes += it } }
    collectorScope.launch { viewModel.deleteFailures.collect { failures += it } }

    viewModel.deleteResult(1)
    testDispatcher.scheduler.advanceUntilIdle()

    assertTrue(successes.isEmpty())
    assertEquals(1, failures.size)
  }

  @Test
  fun `解析先に応じて手動解析可否を返す`() = runTest(testDispatcher) {
    val integrationRepository = FakeAnalysisIntegrationRepository(AnalysisIntegration.CUSTOM_WEBHOOK)
    val viewModel = createViewModel(integrationRepository = integrationRepository)
    val collectJob = CoroutineScope(testDispatcher).launch { viewModel.canRunAnalysis.collect {} }
    testDispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.canRunAnalysis.value)

    integrationRepository.set(AnalysisIntegration.HOSTED)
    testDispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.canRunAnalysis.value)

    integrationRepository.set(AnalysisIntegration.NONE)
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(false, viewModel.canRunAnalysis.value)
    collectJob.cancel()
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
        currentZoneId = MutableStateFlow(ZoneId.of("Asia/Tokyo")),
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
  fun `selectableDaysはHostedでは当日と成功済みの日を除いた前日以前の記録日だけになる`() = runTest(testDispatcher) {
    val journalEntryReader = FakeJournalEntryReader()
    val reader = FakeAnalysisResultReader()
    val viewModel = createViewModel(
      reader = reader,
      integrationRepository = FakeAnalysisIntegrationRepository(AnalysisIntegration.HOSTED),
      journalEntryReader = journalEntryReader,
      executionRepository = FakeAnalysisExecutionRepository(
        AnalysisExecutionState(hostedSuccessfulDays = setOf(LocalDate.of(2026, 8, 28))),
      ),
      currentZoneId = MutableStateFlow(ZoneOffset.UTC),
      currentDate = { LocalDate.of(2026, 8, 30) },
    )
    val collectJob = CoroutineScope(testDispatcher).launch { viewModel.selectableDays.collect {} }

    reader.emit(emptyList())
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
  fun `Hosted成功済み日はAnalysisResult削除後もselectableDaysへ戻らない`() = runTest(testDispatcher) {
    val journalEntryReader = FakeJournalEntryReader(listOf(entry("2026-08-29T05:00:00Z")))
    val reader = FakeAnalysisResultReader().apply { emit(emptyList()) }
    val executionRepository = FakeAnalysisExecutionRepository(
      AnalysisExecutionState(hostedSuccessfulDays = setOf(LocalDate.of(2026, 8, 29))),
    )
    val viewModel = createViewModel(
      reader = reader,
      integrationRepository = FakeAnalysisIntegrationRepository(AnalysisIntegration.HOSTED),
      journalEntryReader = journalEntryReader,
      executionRepository = executionRepository,
      currentDate = { LocalDate.of(2026, 8, 30) },
    )
    val collectJob = CoroutineScope(testDispatcher).launch { viewModel.selectableDays.collect {} }
    testDispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.selectableDays.value.isEmpty())
    collectJob.cancel()
  }

  @Test
  fun `Custom WebhookはHosted成功済み日でもselectableDaysへ含める`() = runTest(testDispatcher) {
    val journalEntryReader = FakeJournalEntryReader(listOf(entry("2026-08-29T05:00:00Z")))
    val reader = FakeAnalysisResultReader().apply { emit(emptyList()) }
    val executionRepository = FakeAnalysisExecutionRepository(
      AnalysisExecutionState(hostedSuccessfulDays = setOf(LocalDate.of(2026, 8, 29))),
    )
    val viewModel = createViewModel(
      reader = reader,
      integrationRepository = FakeAnalysisIntegrationRepository(AnalysisIntegration.CUSTOM_WEBHOOK),
      journalEntryReader = journalEntryReader,
      executionRepository = executionRepository,
      currentDate = { LocalDate.of(2026, 8, 30) },
    )
    val collectJob = CoroutineScope(testDispatcher).launch { viewModel.selectableDays.collect {} }
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(setOf(LocalDate.of(2026, 8, 29)), viewModel.selectableDays.value)
    collectJob.cancel()
  }

  @Test
  fun `履歴の解析可否は記録の初回読み込み前には確定しない`() = runTest(testDispatcher) {
    val entries = MutableSharedFlow<List<JournalEntry>>(replay = 1)
    val viewModel = createViewModel(journalEntryReader = JournalEntryReader { entries })
    collectorScope.launch { viewModel.manualAnalysisUnavailableReasons.collect {} }
    testDispatcher.scheduler.runCurrent()

    assertEquals(null, viewModel.manualAnalysisUnavailableReasons.value?.second)
    entries.emit(emptyList())
    testDispatcher.scheduler.runCurrent()
    assertEquals(emptyMap<LocalDate, Int?>(), viewModel.manualAnalysisUnavailableReasons.value?.second)
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
    collectorScope.launch { viewModel.manualAnalysisUnavailableReasons.collect {} }
    testDispatcher.scheduler.runCurrent()

    val reasons = requireNotNull(viewModel.manualAnalysisUnavailableReasons.value?.second)
    assertEquals(R.string.journal_history_analysis_outside_period, reasons[LocalDate.of(2026, 8, 23)])
    assertEquals(null, reasons.getValue(LocalDate.of(2026, 8, 24)))
    assertEquals(R.string.journal_history_analysis_already_completed, reasons[LocalDate.of(2026, 8, 28)])
    assertEquals(null, reasons.getValue(LocalDate.of(2026, 8, 29)))
    assertEquals(R.string.journal_history_analysis_outside_period, reasons[LocalDate.of(2026, 8, 30)])
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
      collectorScope.launch { viewModel.manualAnalysisUnavailableReasons.collect {} }
      testDispatcher.scheduler.runCurrent()

      assertEquals(
        mapOf(LocalDate.of(2026, 8, 31) to null, LocalDate.of(2026, 8, 2) to null),
        viewModel.manualAnalysisUnavailableReasons.value?.second,
      )
      integrationRepository.set(AnalysisIntegration.NONE)
      testDispatcher.scheduler.runCurrent()
      assertEquals(
        mapOf(
          LocalDate.of(2026, 8, 31) to R.string.analysis_failure_integration_unavailable,
          LocalDate.of(2026, 8, 2) to R.string.analysis_failure_integration_unavailable,
        ),
        viewModel.manualAnalysisUnavailableReasons.value?.second,
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
    collectorScope.launch { viewModel.manualAnalysisUnavailableReasons.collect {} }
    testDispatcher.scheduler.runCurrent()
    viewModel.analyze(LocalDate.of(2026, 8, 29))
    testDispatcher.scheduler.runCurrent()

    val reasons = requireNotNull(viewModel.manualAnalysisUnavailableReasons.value?.second)
    assertEquals(R.string.journal_history_analysis_running_day, reasons[LocalDate.of(2026, 8, 29)])
    assertEquals(R.string.journal_history_analysis_running_other_day, reasons[LocalDate.of(2026, 8, 27)])
    assertEquals(R.string.journal_history_analysis_already_completed, reasons[LocalDate.of(2026, 8, 28)])
    assertEquals(R.string.journal_history_analysis_outside_period, reasons[LocalDate.of(2026, 8, 30)])
    assertEquals(R.string.journal_history_analysis_outside_period, reasons[LocalDate.of(2026, 8, 23)])
    viewModel.analyze(LocalDate.of(2026, 8, 27))
    pendingEntries.complete(listOf(entry("2026-08-29T05:00:00Z")))
    testDispatcher.scheduler.runCurrent()

    assertEquals(1, analyzer.callCount)
    assertFalse(viewModel.isAnalysisRunning.value)
    assertEquals(
      R.string.journal_history_analysis_already_completed,
      requireNotNull(viewModel.manualAnalysisUnavailableReasons.value?.second)[LocalDate.of(2026, 8, 29)],
    )
    assertEquals(null, requireNotNull(viewModel.manualAnalysisUnavailableReasons.value?.second).getValue(LocalDate.of(2026, 8, 27)))
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
      val history = JournalHistoryViewModel(
        reader = journalReader,
        analysisResultReader = resultReader,
        analysisExecutionRepository = executionRepository,
        deleter = JournalEntryDeleter {},
        currentZoneId = zone,
        now = { now },
      )
      val analysis = createViewModel(
        reader = resultReader,
        journalEntryReader = journalReader,
        integrationRepository = FakeAnalysisIntegrationRepository(AnalysisIntegration.HOSTED),
        entryReader = PeriodJournalEntryReader { start, end ->
          entries.value.filter { it.timestamp >= start && it.timestamp < end }
        },
        analyzer = analyzer,
        executionRepository = executionRepository,
        currentZoneId = zone,
        currentDate = { now.atZone(it).toLocalDate() },
      )
      collectorScope.launch { history.uiState.collect {} }
      collectorScope.launch { analysis.manualAnalysisUnavailableReasons.collect {} }
      collectorScope.launch { analysis.selectableDays.collect {} }
      testDispatcher.scheduler.runCurrent()
      history.selectDate(LocalDate.of(2026, 8, 29))
      testDispatcher.scheduler.runCurrent()
      val originalContent = history.uiState.value as JournalHistoryUiState.Content
      assertEquals(setOf(2L, 3L), originalContent.itemsOn(originalContent.selectedDate).map { it.id }.toSet())
      assertEquals(
        R.string.journal_history_analysis_outside_period,
        requireNotNull(analysis.manualAnalysisUnavailableReasons.value).second[LocalDate.of(2026, 8, 30)],
      )

      zone.value = ZoneId.of("Asia/Tokyo")
      testDispatcher.scheduler.runCurrent()
      val changedContent = history.uiState.value as JournalHistoryUiState.Content
      assertEquals(LocalDate.of(2026, 8, 31), changedContent.today)
      assertEquals(LocalDate.of(2026, 8, 29), changedContent.earliestDate)
      assertEquals(setOf(1L, 2L), changedContent.itemsOn(changedContent.selectedDate).map { it.id }.toSet())
      assertEquals(changedContent.zoneId, requireNotNull(analysis.manualAnalysisUnavailableReasons.value).first)
      assertEquals(setOf(LocalDate.of(2026, 8, 29), LocalDate.of(2026, 8, 30)), analysis.selectableDays.value)

      entries.value += entry("2026-08-30T18:00:00Z", id = 5)
      history.selectDate(LocalDate.of(2026, 8, 30))
      testDispatcher.scheduler.runCurrent()
      val displayedContent = history.uiState.value as JournalHistoryUiState.Content
      val displayedIds = displayedContent.itemsOn(displayedContent.selectedDate).map { it.id }.toSet()
      assertEquals(setOf(3L, 4L), displayedIds)
      assertEquals(null, requireNotNull(analysis.manualAnalysisUnavailableReasons.value).second.getValue(displayedContent.selectedDate))
      analysis.analyze(displayedContent.selectedDate, displayedContent.zoneId)
      testDispatcher.scheduler.runCurrent()

      assertEquals(displayedIds, requireNotNull(analyzer.lastEntries).map { it.id }.toSet())
      assertEquals(Instant.parse("2026-08-29T15:00:00Z"), analyzer.lastPeriodStart)
      assertEquals(Instant.parse("2026-08-30T15:00:00Z"), analyzer.lastPeriodEnd)
      assertEquals(setOf(displayedContent.selectedDate), executionRepository.state.value.hostedSuccessfulDays)
      assertEquals(
        R.string.journal_history_analysis_already_completed,
        requireNotNull(analysis.manualAnalysisUnavailableReasons.value).second[displayedContent.selectedDate],
      )
      assertEquals(displayedContent.selectedDate, (history.uiState.value as JournalHistoryUiState.Content).selectedDate)

      zone.value = ZoneOffset.UTC
      testDispatcher.scheduler.runCurrent()
      assertEquals(
        R.string.journal_history_analysis_already_completed,
        requireNotNull(analysis.manualAnalysisUnavailableReasons.value).second[displayedContent.selectedDate],
      )
      assertFalse(displayedContent.selectedDate in analysis.selectableDays.value)
    }

  @Test
  fun `タイムゾーン変更だけで保存済み結果の日付と最新結果の対応も更新される`() = runTest(testDispatcher) {
    val zone = MutableStateFlow<ZoneId>(ZoneOffset.UTC)
    val reader = FakeAnalysisResultReader().apply {
      emit(listOf(result(7, "2026-08-31T02:00:00Z", "結果").copy(periodStart = Instant.parse("2026-08-29T16:00:00Z"))))
    }
    val viewModel = createViewModel(reader = reader, currentZoneId = zone)
    collectorScope.launch { viewModel.uiState.collect {} }
    testDispatcher.scheduler.runCurrent()
    assertEquals(7L, (viewModel.uiState.value as AnalysisHistoryUiState.Content).latestResultsByDay[LocalDate.of(2026, 8, 29)]?.id)

    zone.value = ZoneId.of("Asia/Tokyo")
    testDispatcher.scheduler.runCurrent()
    val content = viewModel.uiState.value as AnalysisHistoryUiState.Content
    assertEquals(zone.value, content.zoneId)
    assertEquals(null, content.latestResultsByDay[LocalDate.of(2026, 8, 29)])
    assertEquals(7L, content.latestResultsByDay[LocalDate.of(2026, 8, 30)]?.id)
  }

  @Test
  fun `更新前の履歴タイムゾーンでの解析は送信せず開始後のタイムゾーン変更は送信期間を変更しない`() = runTest(testDispatcher) {
    val zone = MutableStateFlow<ZoneId>(ZoneOffset.UTC)
    val pendingEntries = CompletableDeferred<List<JournalEntry>>()
    val entryReader = PeriodJournalEntryReader { _, _ -> pendingEntries.await() }
    val analyzer = FakePeriodAnalyzer { success() }
    val viewModel = createViewModel(currentZoneId = zone, entryReader = entryReader, analyzer = analyzer)
    zone.value = ZoneId.of("Asia/Tokyo")

    viewModel.analyze(LocalDate.of(2026, 8, 30), ZoneOffset.UTC)
    testDispatcher.scheduler.runCurrent()
    assertEquals(0, analyzer.callCount)
    assertFalse(viewModel.isAnalysisRunning.value)

    viewModel.analyze(LocalDate.of(2026, 8, 30), zone.value)
    testDispatcher.scheduler.runCurrent()
    assertTrue(viewModel.isAnalysisRunning.value)
    zone.value = ZoneOffset.UTC
    pendingEntries.complete(listOf(entry("2026-08-30T05:00:00Z")))
    testDispatcher.scheduler.runCurrent()
    assertEquals(1, analyzer.callCount)
    assertEquals(Instant.parse("2026-08-29T15:00:00Z"), analyzer.lastPeriodStart)
    assertEquals(Instant.parse("2026-08-30T15:00:00Z"), analyzer.lastPeriodEnd)
    assertFalse(viewModel.isAnalysisRunning.value)
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
    deleter: AnalysisResultDeleter = FakeAnalysisResultDeleter(),
    executionRepository: AnalysisExecutionRepository = FakeAnalysisExecutionRepository(),
    hostedCredentialsRepository: HostedCredentialsRepository = object : HostedCredentialsRepository {
      override suspend fun apiKey(): String? = null
      override suspend fun store(apiKey: String) = Unit
      override suspend fun clear() = Unit
    },
    currentZoneId: StateFlow<ZoneId> = MutableStateFlow(ZoneOffset.UTC),
    currentDate: (ZoneId) -> LocalDate = { LocalDate.of(2026, 9, 1) },
  ) = AnalysisHistoryViewModel(
    reader = reader,
    analysisIntegrationRepository = integrationRepository,
    journalEntryReader = journalEntryReader,
    periodJournalEntryReader = entryReader,
    periodAnalyzer = analyzer,
    analysisResultWriter = writer,
    analysisResultDeleter = deleter,
    analysisExecutionRepository = executionRepository,
    hostedCredentialsRepository = hostedCredentialsRepository,
    currentZoneId = currentZoneId,
    currentDate = currentDate,
  )

  private fun entry(at: String, id: Long = 0) = JournalEntry(
    id = id,
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

  private class FakeAnalysisResultDeleter(
    private var failNextDeletes: Int = 0,
  ) : AnalysisResultDeleter {
    private val _deletedIds = mutableListOf<Long>()
    val deletedIds: List<Long> get() = _deletedIds

    override suspend fun delete(id: Long) {
      if (failNextDeletes > 0) {
        failNextDeletes--
        throw IOException("db error")
      }
      _deletedIds += id
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
