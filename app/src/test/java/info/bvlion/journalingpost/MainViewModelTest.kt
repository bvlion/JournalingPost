package info.bvlion.journalingpost

import info.bvlion.journalingpost.journal.JournalRecorder
import info.bvlion.journalingpost.journal.JournalSource
import info.bvlion.journalingpost.mood.MoodSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
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
  fun `初期状態はINITになる`() {
    val viewModel = MainViewModel(FakeJournalRecorder())

    assertEquals(MainViewModel.UiState.INIT, viewModel.uiState.value)
  }

  @Test
  fun `recordはrecorder完了前にLOADINGへ遷移する`() = runTest(testDispatcher) {
    val viewModel = MainViewModel(FakeJournalRecorder())

    viewModel.record("today was good", source = JournalSource.APP)

    assertEquals(MainViewModel.UiState.LOADING, viewModel.uiState.value)
  }

  @Test
  fun `recorderが正常終了するとSUCCESSへ遷移する`() = runTest(testDispatcher) {
    val viewModel = MainViewModel(FakeJournalRecorder())

    viewModel.record("today was good", source = JournalSource.APP)
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(MainViewModel.UiState.SUCCESS, viewModel.uiState.value)
  }

  @Test
  fun `ローカル保存が失敗するとFAILUREへ遷移する`() = runTest(testDispatcher) {
    val viewModel = MainViewModel(FakeJournalRecorder { throw RuntimeException("boom") })

    viewModel.record("today was good", source = JournalSource.APP)
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(MainViewModel.UiState.FAILURE, viewModel.uiState.value)
  }

  @Test
  fun `CancellationExceptionはFAILURE扱いにしない`() = runTest(testDispatcher) {
    val viewModel = MainViewModel(FakeJournalRecorder { throw CancellationException("cancelled") })

    viewModel.record("today was good", source = JournalSource.APP)
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(MainViewModel.UiState.LOADING, viewModel.uiState.value)
  }

  @Test
  fun `resetStateで状態がINITに戻る`() = runTest(testDispatcher) {
    val viewModel = MainViewModel(FakeJournalRecorder())
    viewModel.record("today was good", source = JournalSource.APP)
    testDispatcher.scheduler.advanceUntilIdle()

    viewModel.resetState()

    assertEquals(MainViewModel.UiState.INIT, viewModel.uiState.value)
  }

  @Test
  fun `recordはnote・mood・sourceをそのままrecorderへ渡す`() = runTest(testDispatcher) {
    val fakeJournalRecorder = FakeJournalRecorder()
    val viewModel = MainViewModel(fakeJournalRecorder)
    assertNull(fakeJournalRecorder.lastNote)
    val mood = MoodSnapshot(id = "HAPPY", emoji = "🙂", label = "嬉しい")

    viewModel.record("today was good", mood = mood, source = JournalSource.WIDGET)
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals("today was good", fakeJournalRecorder.lastNote)
    assertEquals(mood, fakeJournalRecorder.lastMood)
    assertEquals(JournalSource.WIDGET, fakeJournalRecorder.lastSource)
  }

  @Test
  fun `直前のrecordが処理中の呼び出しは無視される`() = runTest(testDispatcher) {
    val fakeJournalRecorder = FakeJournalRecorder()
    val viewModel = MainViewModel(fakeJournalRecorder)

    viewModel.record("first", source = JournalSource.APP)
    viewModel.record("second", source = JournalSource.APP)
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(1, fakeJournalRecorder.callCount)
    assertEquals("first", fakeJournalRecorder.lastNote)
  }

  @Test
  fun `直前のrecordが完了していれば新しい呼び出しを受け付ける`() = runTest(testDispatcher) {
    val fakeJournalRecorder = FakeJournalRecorder()
    val viewModel = MainViewModel(fakeJournalRecorder)

    viewModel.record("first", source = JournalSource.APP)
    testDispatcher.scheduler.advanceUntilIdle()
    viewModel.record("second", source = JournalSource.APP)
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(2, fakeJournalRecorder.callCount)
    assertEquals("second", fakeJournalRecorder.lastNote)
  }

  private class FakeJournalRecorder(
    private val behavior: suspend (String) -> Unit = {},
  ) : JournalRecorder {
    var callCount = 0
      private set
    var lastNote: String? = null
      private set
    var lastMood: MoodSnapshot? = null
      private set
    var lastSource: JournalSource? = null
      private set

    override suspend fun record(note: String, mood: MoodSnapshot?, source: JournalSource) {
      callCount++
      lastNote = note
      lastMood = mood
      lastSource = source
      behavior(note)
    }
  }
}
