package info.bvlion.journalingpost

import info.bvlion.journalingpost.onboarding.AnalysisIntroductionRepository
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AnalysisIntroductionViewModelTest {
  private val dispatcher = StandardTestDispatcher()

  // WhileSubscribedなStateFlowとChannel由来のeventは購読者がいる間だけ流れるため、テスト中は
  // このscopeで購読し続ける。
  private val collectorScope = CoroutineScope(dispatcher)

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
  }

  @After
  fun tearDown() {
    collectorScope.cancel()
    Dispatchers.resetMain()
  }

  @Test
  fun `未案内なら案内を表示する`() = runTest(dispatcher) {
    val viewModel = AnalysisIntroductionViewModel(FakeAnalysisIntroductionRepository(initial = false))
    collectUiState(viewModel)
    advanceUntilIdle()

    assertTrue(viewModel.uiState.value.shouldShow)
  }

  @Test
  fun `既読なら案内を表示しない`() = runTest(dispatcher) {
    val viewModel = AnalysisIntroductionViewModel(FakeAnalysisIntroductionRepository(initial = true))
    collectUiState(viewModel)
    advanceUntilIdle()

    assertEquals(false, viewModel.uiState.value.shouldShow)
  }

  @Test
  fun `設定するを選ぶと既読にして設定画面への遷移イベントを送る`() = runTest(dispatcher) {
    val repository = FakeAnalysisIntroductionRepository(initial = false)
    val viewModel = AnalysisIntroductionViewModel(repository)
    val events = collectEvents(viewModel)

    viewModel.onSetupSelected()
    advanceUntilIdle()

    assertEquals(true, repository.current)
    assertEquals(listOf(AnalysisIntroductionEvent.NavigateToAnalysisSettings), events)
  }

  @Test
  fun `今はしないを選ぶと既読にするだけで遷移イベントは送らない`() = runTest(dispatcher) {
    val repository = FakeAnalysisIntroductionRepository(initial = false)
    val viewModel = AnalysisIntroductionViewModel(repository)
    val events = collectEvents(viewModel)

    viewModel.onDismissed()
    advanceUntilIdle()

    assertEquals(true, repository.current)
    assertTrue(events.isEmpty())
  }

  @Test
  fun `既読の保存に失敗しても遷移イベントは送る`() = runTest(dispatcher) {
    val repository = FakeAnalysisIntroductionRepository(initial = false, failNextMarks = 1)
    val viewModel = AnalysisIntroductionViewModel(repository)
    val events = collectEvents(viewModel)

    viewModel.onSetupSelected()
    advanceUntilIdle()

    assertEquals(false, repository.current)
    assertEquals(listOf(AnalysisIntroductionEvent.NavigateToAnalysisSettings), events)
  }

  private fun collectUiState(viewModel: AnalysisIntroductionViewModel) {
    collectorScope.launch { viewModel.uiState.collect {} }
  }

  private fun collectEvents(viewModel: AnalysisIntroductionViewModel): List<AnalysisIntroductionEvent> {
    val events = mutableListOf<AnalysisIntroductionEvent>()
    collectorScope.launch { viewModel.events.collect { events += it } }
    return events
  }

  private class FakeAnalysisIntroductionRepository(
    initial: Boolean,
    private var failNextMarks: Int = 0,
  ) : AnalysisIntroductionRepository {
    private val state = MutableStateFlow(initial)
    override val isIntroductionSeen: Flow<Boolean> = state
    val current: Boolean get() = state.value

    override suspend fun markIntroductionSeen() {
      if (failNextMarks > 0) {
        failNextMarks--
        throw IOException("analysis introduction write failed")
      }
      state.value = true
    }
  }
}
