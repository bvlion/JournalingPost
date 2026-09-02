package info.bvlion.journalingpost

import info.bvlion.journalingpost.settings.AutoAnalysisSettings
import info.bvlion.journalingpost.settings.AutoAnalysisSettingsRepository
import info.bvlion.journalingpost.settings.AutoAnalysisTargetDay
import java.time.LocalTime
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutoAnalysisSettingsViewModelTest {
  private val testDispatcher = StandardTestDispatcher()
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
  fun `uiStateは読み込み確定前はnull`() = runTest(testDispatcher) {
    val viewModel = AutoAnalysisSettingsViewModel(NeverEmittingRepository(), onSettingsChanged = {})
    collectorScope.launch { viewModel.uiState.collect {} }
    testDispatcher.scheduler.runCurrent()

    assertEquals(null, viewModel.uiState.value)
  }

  @Test
  fun `uiStateは保存済みの設定を反映する`() = runTest(testDispatcher) {
    val repository = FakeRepository(
      AutoAnalysisSettings(true, LocalTime.of(21, 0), AutoAnalysisTargetDay.TODAY),
    )
    val viewModel = createViewModel(repository)
    collectorScope.launch { viewModel.uiState.collect {} }
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(
      AutoAnalysisSettingsUiState(true, LocalTime.of(21, 0), AutoAnalysisTargetDay.TODAY),
      viewModel.uiState.value,
    )
  }

  @Test
  fun `有効化を保存してschedulerへ再予約を依頼する`() = runTest(testDispatcher) {
    val repository = FakeRepository()
    var rescheduleCount = 0
    val viewModel = createViewModel(repository) { rescheduleCount++ }
    collectorScope.launch { viewModel.uiState.collect {} }

    viewModel.setEnabled(true)
    testDispatcher.scheduler.advanceUntilIdle()

    assertTrue(repository.current.enabled)
    assertEquals(1, rescheduleCount)
  }

  @Test
  fun `時刻と対象日の変更を1回の書き込みで保存し再予約を依頼する`() = runTest(testDispatcher) {
    val repository = FakeRepository()
    var rescheduleCount = 0
    val viewModel = createViewModel(repository) { rescheduleCount++ }
    collectorScope.launch { viewModel.uiState.collect {} }

    viewModel.setSchedule(LocalTime.of(22, 15), AutoAnalysisTargetDay.TODAY)
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(LocalTime.of(22, 15), repository.current.timeOfDay)
    assertEquals(AutoAnalysisTargetDay.TODAY, repository.current.targetDay)
    assertEquals(1, repository.saveCount)
    assertEquals(1, rescheduleCount)
  }

  @Test
  fun `同じ値なら保存も再予約もしない`() = runTest(testDispatcher) {
    val repository = FakeRepository()
    var rescheduleCount = 0
    val viewModel = createViewModel(repository) { rescheduleCount++ }
    collectorScope.launch { viewModel.uiState.collect {} }

    viewModel.setEnabled(false)
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(0, repository.saveCount)
    assertEquals(0, rescheduleCount)
  }

  @Test
  fun `保存に失敗したらSaveFailedを通知しschedulerは呼ばない`() = runTest(testDispatcher) {
    val repository = FakeRepository(failOnSave = true)
    var rescheduleCount = 0
    val viewModel = createViewModel(repository) { rescheduleCount++ }
    collectorScope.launch { viewModel.uiState.collect {} }
    val events = mutableListOf<AutoAnalysisSettingsEvent>()
    collectorScope.launch { viewModel.events.collect { events += it } }

    viewModel.setEnabled(true)
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(listOf(AutoAnalysisSettingsEvent.SaveFailed), events)
    assertEquals(0, rescheduleCount)
  }

  private fun createViewModel(
    repository: FakeRepository,
    onSettingsChanged: suspend () -> Unit = {},
  ) = AutoAnalysisSettingsViewModel(repository, onSettingsChanged)

  private class FakeRepository(
    initial: AutoAnalysisSettings = AutoAnalysisSettings.DEFAULT,
    private val failOnSave: Boolean = false,
  ) : AutoAnalysisSettingsRepository {
    private val state = MutableStateFlow(initial)
    var saveCount = 0
      private set

    val current: AutoAnalysisSettings get() = state.value

    override val autoAnalysisSettings: Flow<AutoAnalysisSettings> = state

    override suspend fun setAutoAnalysisSettings(settings: AutoAnalysisSettings) {
      if (failOnSave) throw RuntimeException("save boom")
      saveCount++
      state.value = settings
    }
  }

  private class NeverEmittingRepository : AutoAnalysisSettingsRepository {
    override val autoAnalysisSettings: Flow<AutoAnalysisSettings> = MutableSharedFlow()

    override suspend fun setAutoAnalysisSettings(settings: AutoAnalysisSettings) = Unit
  }
}
