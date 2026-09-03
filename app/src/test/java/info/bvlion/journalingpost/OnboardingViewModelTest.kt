package info.bvlion.journalingpost

import info.bvlion.journalingpost.onboarding.AnalysisIntroductionRepository
import info.bvlion.journalingpost.onboarding.FirstRecordRepository
import info.bvlion.journalingpost.onboarding.WelcomeRepository
import info.bvlion.journalingpost.settings.AnalysisIntegration
import info.bvlion.journalingpost.settings.AnalysisIntegrationRepository
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
class OnboardingViewModelTest {
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
  fun `ウェルカム未読ならウェルカムダイアログのみ出す`() = runTest(dispatcher) {
    val viewModel = createViewModel(welcomeSeen = false, firstRecordCompleted = false, introductionSeen = false)
    collectUiState(viewModel)
    advanceUntilIdle()

    assertTrue(viewModel.uiState.value.showWelcomeDialog)
    assertEquals(false, viewModel.uiState.value.highlightMoodSelection)
    assertEquals(false, viewModel.uiState.value.showAnalysisIntroduction)
  }

  @Test
  fun `ウェルカム既読で記録未完了なら気分選択の視覚誘導のみ出す`() = runTest(dispatcher) {
    val viewModel = createViewModel(welcomeSeen = true, firstRecordCompleted = false, introductionSeen = false)
    collectUiState(viewModel)
    advanceUntilIdle()

    assertEquals(false, viewModel.uiState.value.showWelcomeDialog)
    assertTrue(viewModel.uiState.value.highlightMoodSelection)
    assertEquals(false, viewModel.uiState.value.showAnalysisIntroduction)
  }

  @Test
  fun `最初の記録完了後で案内未読ならAI振り返り案内のみ出す`() = runTest(dispatcher) {
    val viewModel = createViewModel(welcomeSeen = true, firstRecordCompleted = true, introductionSeen = false)
    collectUiState(viewModel)
    advanceUntilIdle()

    assertEquals(false, viewModel.uiState.value.showWelcomeDialog)
    assertEquals(false, viewModel.uiState.value.highlightMoodSelection)
    assertTrue(viewModel.uiState.value.showAnalysisIntroduction)
  }

  @Test
  fun `AI振り返り案内が既読なら記録完了後も出さない`() = runTest(dispatcher) {
    val viewModel = createViewModel(welcomeSeen = true, firstRecordCompleted = true, introductionSeen = true)
    collectUiState(viewModel)
    advanceUntilIdle()

    assertEquals(false, viewModel.uiState.value.showAnalysisIntroduction)
  }

  @Test
  fun `最初の記録より前に解析先を選んでいれば記録完了後もAI振り返り案内を出さない`() = runTest(dispatcher) {
    val viewModel = createViewModel(
      welcomeSeen = true,
      firstRecordCompleted = true,
      introductionSeen = false,
      analysisIntegrationRepository = FakeAnalysisIntegrationRepository(AnalysisIntegration.HOSTED),
    )
    collectUiState(viewModel)
    advanceUntilIdle()

    assertEquals(false, viewModel.uiState.value.showAnalysisIntroduction)
  }

  @Test
  fun `ウェルカムダイアログを閉じると既読にする`() = runTest(dispatcher) {
    val welcomeRepository = FakeWelcomeRepository(initial = false)
    val viewModel = createViewModel(welcomeRepository = welcomeRepository)

    viewModel.onWelcomeDialogDismissed()
    advanceUntilIdle()

    assertTrue(welcomeRepository.current)
  }

  @Test
  fun `記録成功で最初の記録を完了として記録する`() = runTest(dispatcher) {
    val firstRecordRepository = FakeFirstRecordRepository(initial = false)
    val viewModel = createViewModel(firstRecordRepository = firstRecordRepository)

    viewModel.onRecordSucceeded()
    advanceUntilIdle()

    assertTrue(firstRecordRepository.current)
  }

  @Test
  fun `設定するを選ぶと既読にして設定画面への遷移イベントを送る`() = runTest(dispatcher) {
    val analysisIntroductionRepository = FakeAnalysisIntroductionRepository(initial = false)
    val viewModel = createViewModel(
      welcomeSeen = true,
      firstRecordCompleted = true,
      analysisIntroductionRepository = analysisIntroductionRepository,
    )
    val events = collectEvents(viewModel)

    viewModel.onAnalysisIntroductionSetupSelected()
    advanceUntilIdle()

    assertTrue(analysisIntroductionRepository.current)
    assertEquals(listOf(OnboardingEvent.NavigateToAnalysisSettings), events)
  }

  @Test
  fun `今はしないを選ぶと既読にするだけで遷移イベントは送らない`() = runTest(dispatcher) {
    val analysisIntroductionRepository = FakeAnalysisIntroductionRepository(initial = false)
    val viewModel = createViewModel(
      welcomeSeen = true,
      firstRecordCompleted = true,
      analysisIntroductionRepository = analysisIntroductionRepository,
    )
    val events = collectEvents(viewModel)

    viewModel.onAnalysisIntroductionDismissed()
    advanceUntilIdle()

    assertTrue(analysisIntroductionRepository.current)
    assertTrue(events.isEmpty())
  }

  @Test
  fun `既読の保存に失敗しても遷移イベントは送る`() = runTest(dispatcher) {
    val analysisIntroductionRepository = FakeAnalysisIntroductionRepository(initial = false, failNextMarks = 1)
    val viewModel = createViewModel(
      welcomeSeen = true,
      firstRecordCompleted = true,
      analysisIntroductionRepository = analysisIntroductionRepository,
    )
    val events = collectEvents(viewModel)

    viewModel.onAnalysisIntroductionSetupSelected()
    advanceUntilIdle()

    assertEquals(false, analysisIntroductionRepository.current)
    assertEquals(listOf(OnboardingEvent.NavigateToAnalysisSettings), events)
  }

  private fun createViewModel(
    welcomeSeen: Boolean = true,
    firstRecordCompleted: Boolean = false,
    introductionSeen: Boolean = false,
    welcomeRepository: WelcomeRepository = FakeWelcomeRepository(initial = welcomeSeen),
    firstRecordRepository: FirstRecordRepository = FakeFirstRecordRepository(initial = firstRecordCompleted),
    analysisIntroductionRepository: AnalysisIntroductionRepository =
      FakeAnalysisIntroductionRepository(initial = introductionSeen),
    analysisIntegrationRepository: AnalysisIntegrationRepository =
      FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE),
  ) = OnboardingViewModel(
    welcomeRepository = welcomeRepository,
    firstRecordRepository = firstRecordRepository,
    analysisIntroductionRepository = analysisIntroductionRepository,
    analysisIntegrationRepository = analysisIntegrationRepository,
  )

  private fun collectUiState(viewModel: OnboardingViewModel) {
    collectorScope.launch { viewModel.uiState.collect {} }
  }

  private fun collectEvents(viewModel: OnboardingViewModel): List<OnboardingEvent> {
    val events = mutableListOf<OnboardingEvent>()
    collectorScope.launch { viewModel.events.collect { events += it } }
    return events
  }

  private class FakeWelcomeRepository(
    initial: Boolean,
    private var failNextMarks: Int = 0,
  ) : WelcomeRepository {
    private val state = MutableStateFlow(initial)
    override val isWelcomeDialogSeen: Flow<Boolean> = state
    val current: Boolean get() = state.value

    override suspend fun markWelcomeDialogSeen() {
      if (failNextMarks > 0) {
        failNextMarks--
        throw IOException("welcome dialog write failed")
      }
      state.value = true
    }
  }

  private class FakeFirstRecordRepository(
    initial: Boolean,
    private var failNextMarks: Int = 0,
  ) : FirstRecordRepository {
    private val state = MutableStateFlow(initial)
    override val isFirstRecordCompleted: Flow<Boolean> = state
    val current: Boolean get() = state.value

    override suspend fun markFirstRecordCompleted() {
      if (failNextMarks > 0) {
        failNextMarks--
        throw IOException("first record write failed")
      }
      state.value = true
    }
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

  private class FakeAnalysisIntegrationRepository(
    initial: AnalysisIntegration,
  ) : AnalysisIntegrationRepository {
    private val state = MutableStateFlow(initial)
    override val analysisIntegration: Flow<AnalysisIntegration> = state

    override suspend fun setAnalysisIntegration(integration: AnalysisIntegration) = error("not used in this test")
  }
}
