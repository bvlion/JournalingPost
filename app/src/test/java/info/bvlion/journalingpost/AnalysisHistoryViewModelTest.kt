package info.bvlion.journalingpost

import info.bvlion.journalingpost.analysis.AnalysisHistoryUiState
import info.bvlion.journalingpost.analysis.AnalysisResult
import info.bvlion.journalingpost.analysis.AnalysisResultReader
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AnalysisHistoryViewModelTest {
  private val testDispatcher = StandardTestDispatcher()

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
    val viewModel = AnalysisHistoryViewModel(reader, ZoneOffset.UTC)
    val collectJob = launchCollection(viewModel)
    testDispatcher.scheduler.runCurrent()

    assertEquals(AnalysisHistoryUiState.Loading, viewModel.uiState.value)
    collectJob.cancel()
  }

  @Test
  fun `uiStateは読み込み完了後に0件ならEmptyになる`() = runTest(testDispatcher) {
    val reader = FakeAnalysisResultReader()
    val viewModel = AnalysisHistoryViewModel(reader, ZoneOffset.UTC)
    val collectJob = launchCollection(viewModel)

    reader.emit(emptyList())
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(AnalysisHistoryUiState.Empty, viewModel.uiState.value)
    collectJob.cancel()
  }

  @Test
  fun `uiStateはreaderの発行後に解析日時の新しい順で結果を反映する`() = runTest(testDispatcher) {
    val reader = FakeAnalysisResultReader()
    val viewModel = AnalysisHistoryViewModel(reader, ZoneOffset.UTC)
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
  fun `uiStateは新しい解析結果が追加されると反応して更新される`() = runTest(testDispatcher) {
    val reader = FakeAnalysisResultReader()
    val viewModel = AnalysisHistoryViewModel(reader, ZoneOffset.UTC)
    val collectJob = launchCollection(viewModel)

    reader.emit(listOf(result(id = 1, analyzedAt = "2026-08-07T07:00:00Z", body = "first")))
    testDispatcher.scheduler.advanceUntilIdle()
    assertEquals(1, (viewModel.uiState.value as AnalysisHistoryUiState.Content).items.size)

    reader.emit(
      listOf(
        result(id = 1, analyzedAt = "2026-08-07T07:00:00Z", body = "first"),
        result(id = 2, analyzedAt = "2026-08-08T07:00:00Z", body = "second"),
      ),
    )
    testDispatcher.scheduler.advanceUntilIdle()

    val items = (viewModel.uiState.value as AnalysisHistoryUiState.Content).items
    assertEquals(listOf("second", "first"), items.map { it.body })
    collectJob.cancel()
  }

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
}
