package info.bvlion.journalingpost

import info.bvlion.journalingpost.analysis.AnalysisExecutionRepository
import info.bvlion.journalingpost.analysis.AnalysisExecutionState
import info.bvlion.journalingpost.analysis.AnalysisHistoryUiState
import info.bvlion.journalingpost.analysis.AnalysisResult
import info.bvlion.journalingpost.analysis.AnalysisResultDeleter
import info.bvlion.journalingpost.analysis.AnalysisResultReader
import info.bvlion.journalingpost.time.CurrentDateProvider
import java.io.IOException
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
import kotlinx.coroutines.flow.StateFlow
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

  // Channel由来の削除結果は購読者がいる間だけ流れるため、テスト中はこのscopeで購読し続ける。
  private val collectorScope = CoroutineScope(testDispatcher)

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
  fun `タイムゾーン変更だけで保存済み結果の日付と最新結果の対応も更新される`() = runTest(testDispatcher) {
    val zone = MutableStateFlow<ZoneId>(ZoneOffset.UTC)
    val reader = FakeAnalysisResultReader().apply {
      emit(listOf(result(7, "2026-08-31T02:00:00Z", "結果").copy(periodStart = Instant.parse("2026-08-29T16:00:00Z"))))
    }
    val viewModel = createViewModel(reader = reader, currentZoneId = zone)
    collectorScope.launch { viewModel.uiState.collect {} }
    testDispatcher.scheduler.runCurrent()
    assertEquals(LocalDate.of(2026, 8, 29), (viewModel.uiState.value as AnalysisHistoryUiState.Content).items.single().periodStart.toLocalDate())

    zone.value = ZoneId.of("Asia/Tokyo")
    currentDateProvider.refresh()
    testDispatcher.scheduler.runCurrent()
    val content = viewModel.uiState.value as AnalysisHistoryUiState.Content
    assertEquals(zone.value, content.zoneId)
    assertEquals(7L, content.items.single().id)
    assertEquals(LocalDate.of(2026, 8, 30), content.items.single().periodStart.toLocalDate())
  }

  @Test
  fun `通知だけの購読では閲覧用Repositoryを生成せず一覧を離れると購読を停止する`() = runTest(testDispatcher) {
    var readerCount = 0
    var deleterCount = 0
    var executionCount = 0
    val reader = FakeAnalysisResultReader().apply { emit(emptyList()) }
    val viewModel = AnalysisHistoryViewModel(
      reader = { readerCount++; reader },
      analysisResultDeleter = { deleterCount++; FakeAnalysisResultDeleter() },
      analysisExecutionRepository = { executionCount++; FakeAnalysisExecutionRepository() },
      currentDateProvider = CurrentDateProvider(zoneId = { ZoneOffset.UTC }),
    )
    collectorScope.launch { viewModel.deleteSuccesses.collect {} }
    collectorScope.launch { viewModel.deleteFailures.collect {} }
    testDispatcher.scheduler.runCurrent()
    assertEquals(listOf(0, 0, 0), listOf(readerCount, deleterCount, executionCount))

    val collection = collectorScope.launch { viewModel.uiState.collect {} }
    testDispatcher.scheduler.runCurrent()
    assertEquals(listOf(1, 0, 0), listOf(readerCount, deleterCount, executionCount))
    assertEquals(1, reader.subscriberCount)
    collection.cancel()
    testDispatcher.scheduler.advanceTimeBy(5_001)
    testDispatcher.scheduler.runCurrent()
    assertEquals(0, reader.subscriberCount)
  }

  private lateinit var currentDateProvider: CurrentDateProvider

  private fun createViewModel(
    reader: AnalysisResultReader = FakeAnalysisResultReader(),
    deleter: AnalysisResultDeleter = FakeAnalysisResultDeleter(),
    executionRepository: AnalysisExecutionRepository = FakeAnalysisExecutionRepository(),
    currentZoneId: StateFlow<ZoneId> = MutableStateFlow(ZoneOffset.UTC),
  ): AnalysisHistoryViewModel {
    currentDateProvider = CurrentDateProvider(zoneId = { currentZoneId.value })
    return AnalysisHistoryViewModel({ reader }, { deleter }, { executionRepository }, currentDateProvider)
  }

  private fun result(id: Long, analyzedAt: String, body: String) = AnalysisResult(
    id = id,
    periodStart = Instant.parse("2026-08-01T00:00:00Z"),
    periodEnd = Instant.parse("2026-08-07T00:00:00Z"),
    analyzedAt = Instant.parse(analyzedAt),
    body = body,
  )

  private fun launchCollection(viewModel: AnalysisHistoryViewModel) =
    collectorScope.launch { viewModel.uiState.collect {} }

  /** 初回発行前の状態を再現するため、購読開始時点では値を持たないflowを使う。 */
  private class FakeAnalysisResultReader : AnalysisResultReader {
    private val results = MutableSharedFlow<List<AnalysisResult>>(replay = 1, extraBufferCapacity = 8)

    val subscriberCount: Int get() = results.subscriptionCount.value

    fun emit(results: List<AnalysisResult>) {
      check(this.results.tryEmit(results))
    }

    override fun observeAll(): Flow<List<AnalysisResult>> = results
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

}
