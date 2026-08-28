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
import org.junit.Assert.assertFalse
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
  fun `ローカル保存成功後にWebhook送信が失敗するとSUCCESS_DELIVERY_FAILEDになる`() =
    runTest(testDispatcher) {
      val repository = InMemoryJournalEntryRepository()
      val recorder = LocalWebhookJournalRecorder(repository, JournalPoster { false }, isWebhookConfigured = { true })
      val viewModel = MainViewModel(recorder)

      viewModel.record("today was good", source = JournalSource.APP)
      testDispatcher.scheduler.advanceUntilIdle()

      assertEquals(MainViewModel.UiState.SUCCESS_DELIVERY_FAILED, viewModel.uiState.value)
      assertEquals(DeliveryStatus.FAILED, repository.entries.values.single().deliveryStatus)
    }

  @Test
  fun `Webhook設定不足時はローカル記録が残りSUCCESS_DELIVERY_FAILEDになる`() =
    runTest(testDispatcher) {
      val repository = InMemoryJournalEntryRepository()
      var postCalled = false
      val recorder = LocalWebhookJournalRecorder(
        repository,
        JournalPoster { postCalled = true; true },
        isWebhookConfigured = { false },
      )
      val viewModel = MainViewModel(recorder)

      viewModel.record("today was good", source = JournalSource.APP)
      testDispatcher.scheduler.advanceUntilIdle()

      assertEquals(MainViewModel.UiState.SUCCESS_DELIVERY_FAILED, viewModel.uiState.value)
      assertFalse(postCalled)
      assertEquals("today was good", repository.entries.values.single().note)
    }

  @Test
  fun `Webhook配送がFAILEDの場合はSUCCESS_DELIVERY_FAILEDへ遷移する`() = runTest(testDispatcher) {
    val viewModel = MainViewModel(FakeJournalRecorder { DeliveryStatus.FAILED })

    viewModel.record("today was good", source = JournalSource.APP)
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(MainViewModel.UiState.SUCCESS_DELIVERY_FAILED, viewModel.uiState.value)
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
    private val behavior: suspend (String) -> DeliveryStatus = { DeliveryStatus.SENT },
  ) : JournalRecorder {
    var callCount = 0
      private set
    var lastNote: String? = null
      private set
    var lastMood: MoodSnapshot? = null
      private set
    var lastSource: JournalSource? = null
      private set

    override suspend fun record(note: String, mood: MoodSnapshot?, source: JournalSource): DeliveryStatus {
      callCount++
      lastNote = note
      lastMood = mood
      lastSource = source
      return behavior(note)
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
