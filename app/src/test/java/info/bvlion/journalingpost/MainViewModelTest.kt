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
  fun `initial state is INIT`() {
    val viewModel = MainViewModel(FakeJournalRecorder { true })

    assertEquals(MainViewModel.UiState.INIT, viewModel.uiState.value)
  }

  @Test
  fun `record sets state to LOADING before the recorder completes`() = runTest(testDispatcher) {
    val viewModel = MainViewModel(FakeJournalRecorder { true })

    viewModel.record("today was good", source = JournalSource.APP)

    assertEquals(MainViewModel.UiState.LOADING, viewModel.uiState.value)
  }

  @Test
  fun `record sets state to SUCCESS when the recorder returns true`() = runTest(testDispatcher) {
    val viewModel = MainViewModel(FakeJournalRecorder { true })

    viewModel.record("today was good", source = JournalSource.APP)
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(MainViewModel.UiState.SUCCESS, viewModel.uiState.value)
  }

  @Test
  fun `record sets state to FAILURE when the recorder returns false`() = runTest(testDispatcher) {
    val viewModel = MainViewModel(FakeJournalRecorder { false })

    viewModel.record("today was good", source = JournalSource.APP)
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(MainViewModel.UiState.FAILURE, viewModel.uiState.value)
  }

  @Test
  fun `record sets state to FAILURE without crashing when the recorder throws`() = runTest(testDispatcher) {
    val viewModel = MainViewModel(FakeJournalRecorder { throw RuntimeException("boom") })

    viewModel.record("today was good", source = JournalSource.APP)
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(MainViewModel.UiState.FAILURE, viewModel.uiState.value)
  }

  @Test
  fun `record does not treat CancellationException as FAILURE`() = runTest(testDispatcher) {
    val viewModel = MainViewModel(FakeJournalRecorder { throw CancellationException("cancelled") })

    viewModel.record("today was good", source = JournalSource.APP)
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(MainViewModel.UiState.LOADING, viewModel.uiState.value)
  }

  @Test
  fun `resetState returns state to INIT`() = runTest(testDispatcher) {
    val viewModel = MainViewModel(FakeJournalRecorder { true })
    viewModel.record("today was good", source = JournalSource.APP)
    testDispatcher.scheduler.advanceUntilIdle()

    viewModel.resetState()

    assertEquals(MainViewModel.UiState.INIT, viewModel.uiState.value)
  }

  @Test
  fun `record passes note, mood and source to the recorder unchanged`() = runTest(testDispatcher) {
    val fakeJournalRecorder = FakeJournalRecorder { true }
    val viewModel = MainViewModel(fakeJournalRecorder)
    assertNull(fakeJournalRecorder.lastNote)
    val mood = MoodSnapshot(id = "HAPPY", emoji = "🙂", label = "嬉しい")

    viewModel.record("today was good", mood = mood, source = JournalSource.WIDGET)
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals("today was good", fakeJournalRecorder.lastNote)
    assertEquals(mood, fakeJournalRecorder.lastMood)
    assertEquals(JournalSource.WIDGET, fakeJournalRecorder.lastSource)
  }

  private class FakeJournalRecorder(
    private val behavior: suspend (String) -> Boolean,
  ) : JournalRecorder {
    var lastNote: String? = null
      private set
    var lastMood: MoodSnapshot? = null
      private set
    var lastSource: JournalSource? = null
      private set

    override suspend fun record(note: String, mood: MoodSnapshot?, source: JournalSource): Boolean {
      lastNote = note
      lastMood = mood
      lastSource = source
      return behavior(note)
    }
  }
}
