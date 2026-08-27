package info.bvlion.journalingpost

import info.bvlion.journalingpost.journal.DeliveryStatus
import info.bvlion.journalingpost.journal.JournalEntry
import info.bvlion.journalingpost.journal.JournalEntryRepository
import info.bvlion.journalingpost.journal.JournalRecorder
import info.bvlion.journalingpost.journal.JournalSource
import info.bvlion.journalingpost.journal.LocalWebhookJournalRecorder
import info.bvlion.journalingpost.mood.MoodSnapshot
import info.bvlion.journalingpost.poster.JournalPoster
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
    val viewModel = MainViewModel(FakeJournalRecorder())

    assertEquals(MainViewModel.UiState.INIT, viewModel.uiState.value)
  }

  @Test
  fun `record sets state to LOADING before the recorder completes`() = runTest(testDispatcher) {
    val viewModel = MainViewModel(FakeJournalRecorder())

    viewModel.record("today was good", source = JournalSource.APP)

    assertEquals(MainViewModel.UiState.LOADING, viewModel.uiState.value)
  }

  @Test
  fun `record sets state to SUCCESS when the recorder completes normally`() = runTest(testDispatcher) {
    val viewModel = MainViewModel(FakeJournalRecorder())

    viewModel.record("today was good", source = JournalSource.APP)
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(MainViewModel.UiState.SUCCESS, viewModel.uiState.value)
  }

  @Test
  fun `record sets state to FAILURE when the local save fails`() = runTest(testDispatcher) {
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
    val viewModel = MainViewModel(FakeJournalRecorder())
    viewModel.record("today was good", source = JournalSource.APP)
    testDispatcher.scheduler.advanceUntilIdle()

    viewModel.resetState()

    assertEquals(MainViewModel.UiState.INIT, viewModel.uiState.value)
  }

  @Test
  fun `record passes note, mood and source to the recorder unchanged`() = runTest(testDispatcher) {
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
  fun `record reaches SUCCESS end-to-end when local save succeeds but webhook delivery fails`() =
    runTest(testDispatcher) {
      val repository = InMemoryJournalEntryRepository()
      val recorder = LocalWebhookJournalRecorder(repository, JournalPoster { false })
      val viewModel = MainViewModel(recorder)

      viewModel.record("today was good", source = JournalSource.APP)
      testDispatcher.scheduler.advanceUntilIdle()

      // ローカル保存済みのため記録自体は成功扱いとなり、UIは再登録を促すFAILUREにはならない。
      assertEquals(MainViewModel.UiState.SUCCESS, viewModel.uiState.value)
      assertEquals(DeliveryStatus.FAILED, repository.entries.values.single().deliveryStatus)
    }

  private class FakeJournalRecorder(
    private val behavior: suspend (String) -> Unit = {},
  ) : JournalRecorder {
    var lastNote: String? = null
      private set
    var lastMood: MoodSnapshot? = null
      private set
    var lastSource: JournalSource? = null
      private set

    override suspend fun record(note: String, mood: MoodSnapshot?, source: JournalSource) {
      lastNote = note
      lastMood = mood
      lastSource = source
      behavior(note)
    }
  }

  private class InMemoryJournalEntryRepository : JournalEntryRepository {
    val entries = mutableMapOf<Long, JournalEntry>()
    private var nextId = 1L

    override suspend fun insert(entry: JournalEntry): Long {
      val id = nextId++
      entries[id] = entry.copy(id = id)
      return id
    }

    override suspend fun updateDeliveryStatus(id: Long, status: DeliveryStatus) {
      entries[id] = requireNotNull(entries[id]).copy(deliveryStatus = status)
    }
  }
}
