package info.bvlion.journalingpost

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
    val viewModel = MainViewModel(FakeJournalPoster { true })

    assertEquals(MainViewModel.UiState.INIT, viewModel.uiState.value)
  }

  @Test
  fun `postMessage sets state to LOADING before the poster completes`() = runTest(testDispatcher) {
    val viewModel = MainViewModel(FakeJournalPoster { true })

    viewModel.postMessage("today was good")

    assertEquals(MainViewModel.UiState.LOADING, viewModel.uiState.value)
  }

  @Test
  fun `postMessage sets state to SUCCESS when the poster returns true`() = runTest(testDispatcher) {
    val viewModel = MainViewModel(FakeJournalPoster { true })

    viewModel.postMessage("today was good")
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(MainViewModel.UiState.SUCCESS, viewModel.uiState.value)
  }

  @Test
  fun `postMessage sets state to FAILURE when the poster returns false`() = runTest(testDispatcher) {
    val viewModel = MainViewModel(FakeJournalPoster { false })

    viewModel.postMessage("today was good")
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(MainViewModel.UiState.FAILURE, viewModel.uiState.value)
  }

  @Test
  fun `postMessage sets state to FAILURE without crashing when the poster throws`() = runTest(testDispatcher) {
    val viewModel = MainViewModel(FakeJournalPoster { throw RuntimeException("boom") })

    viewModel.postMessage("today was good")
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(MainViewModel.UiState.FAILURE, viewModel.uiState.value)
  }

  @Test
  fun `postMessage does not treat CancellationException as FAILURE`() = runTest(testDispatcher) {
    val viewModel = MainViewModel(FakeJournalPoster { throw CancellationException("cancelled") })

    viewModel.postMessage("today was good")
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(MainViewModel.UiState.LOADING, viewModel.uiState.value)
  }

  @Test
  fun `resetState returns state to INIT`() = runTest(testDispatcher) {
    val viewModel = MainViewModel(FakeJournalPoster { true })
    viewModel.postMessage("today was good")
    testDispatcher.scheduler.advanceUntilIdle()

    viewModel.resetState()

    assertEquals(MainViewModel.UiState.INIT, viewModel.uiState.value)
  }

  @Test
  fun `postMessage passes the message to the poster unchanged`() = runTest(testDispatcher) {
    val fakeJournalPoster = FakeJournalPoster { true }
    val viewModel = MainViewModel(fakeJournalPoster)
    assertNull(fakeJournalPoster.lastMessage)

    viewModel.postMessage("today was good")
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals("today was good", fakeJournalPoster.lastMessage)
  }

  private class FakeJournalPoster(
    private val behavior: suspend (String) -> Boolean,
  ) : JournalPoster {
    var lastMessage: String? = null
      private set

    override suspend fun post(message: String): Boolean {
      lastMessage = message
      return behavior(message)
    }
  }
}
