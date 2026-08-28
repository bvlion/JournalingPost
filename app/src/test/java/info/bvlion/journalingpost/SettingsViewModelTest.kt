package info.bvlion.journalingpost

import info.bvlion.journalingpost.settings.RecordMode
import info.bvlion.journalingpost.settings.RecordModeRepository
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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
class SettingsViewModelTest {
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
  fun `setRecordMode成功時はrecordModeが更新されsaveFailedはfalseのまま`() = runTest(testDispatcher) {
    val repository = FakeRecordModeRepository(RecordMode.LOCAL_AND_WEBHOOK)
    val viewModel = SettingsViewModel(repository)
    val collectJob = launchCollection(viewModel)

    viewModel.setRecordMode(RecordMode.LOCAL_ONLY)
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(RecordMode.LOCAL_ONLY, viewModel.recordMode.value)
    assertFalse(viewModel.saveFailed.value)
    collectJob.cancel()
  }

  @Test
  fun `write失敗時は未処理例外にならずsaveFailedがtrueになる`() = runTest(testDispatcher) {
    val repository = FakeRecordModeRepository(RecordMode.LOCAL_AND_WEBHOOK, failNextWrites = 1)
    val viewModel = SettingsViewModel(repository)
    val collectJob = launchCollection(viewModel)

    viewModel.setRecordMode(RecordMode.LOCAL_ONLY)
    testDispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.saveFailed.value)
    collectJob.cancel()
  }

  @Test
  fun `write失敗後はrecordModeが永続化前の有効なモードへ戻る`() = runTest(testDispatcher) {
    val repository = FakeRecordModeRepository(RecordMode.LOCAL_AND_WEBHOOK, failNextWrites = 1)
    val viewModel = SettingsViewModel(repository)
    val collectJob = launchCollection(viewModel)

    viewModel.setRecordMode(RecordMode.LOCAL_ONLY)
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(RecordMode.LOCAL_AND_WEBHOOK, viewModel.recordMode.value)
    collectJob.cancel()
  }

  @Test
  fun `write失敗後に再度成功するとsaveFailedがfalseに戻りrecordModeも更新される`() = runTest(testDispatcher) {
    val repository = FakeRecordModeRepository(RecordMode.LOCAL_AND_WEBHOOK, failNextWrites = 1)
    val viewModel = SettingsViewModel(repository)
    val collectJob = launchCollection(viewModel)
    viewModel.setRecordMode(RecordMode.LOCAL_ONLY)
    testDispatcher.scheduler.advanceUntilIdle()
    assertTrue(viewModel.saveFailed.value)

    viewModel.setRecordMode(RecordMode.LOCAL_ONLY)
    testDispatcher.scheduler.advanceUntilIdle()

    assertFalse(viewModel.saveFailed.value)
    assertEquals(RecordMode.LOCAL_ONLY, viewModel.recordMode.value)
    collectJob.cancel()
  }

  private fun launchCollection(viewModel: SettingsViewModel) =
    CoroutineScope(testDispatcher).launch { viewModel.recordMode.collect {} }

  private class FakeRecordModeRepository(
    initial: RecordMode,
    private var failNextWrites: Int = 0,
  ) : RecordModeRepository {
    private val state = MutableStateFlow(initial)
    override val recordMode: Flow<RecordMode> = state

    override suspend fun setRecordMode(mode: RecordMode) {
      if (failNextWrites > 0) {
        failNextWrites--
        throw IOException("disk error")
      }
      state.value = mode
    }
  }
}
