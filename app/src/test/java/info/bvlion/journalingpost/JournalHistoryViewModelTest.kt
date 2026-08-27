package info.bvlion.journalingpost

import info.bvlion.journalingpost.journal.DeliveryStatus
import info.bvlion.journalingpost.journal.JournalEntry
import info.bvlion.journalingpost.journal.JournalEntryReader
import info.bvlion.journalingpost.journal.JournalSource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class JournalHistoryViewModelTest {
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
  fun `historyGroups starts empty before the reader emits`() = runTest(testDispatcher) {
    val reader = FakeJournalEntryReader()

    val viewModel = JournalHistoryViewModel(reader, ZoneOffset.UTC)

    assertTrue(viewModel.historyGroups.value.isEmpty())
  }

  @Test
  fun `historyGroups reflects entries grouped by date once the reader emits`() = runTest(testDispatcher) {
    val reader = FakeJournalEntryReader()
    val viewModel = JournalHistoryViewModel(reader, ZoneOffset.UTC)
    val collectJob = launchCollection(viewModel)

    reader.entries.value = listOf(
      JournalEntry(
        id = 1,
        timestamp = Instant.parse("2026-08-26T10:00:00Z"),
        note = "today",
        source = JournalSource.APP,
        deliveryStatus = DeliveryStatus.NOT_REQUIRED,
      ),
    )
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(listOf(LocalDate.of(2026, 8, 26)), viewModel.historyGroups.value.map { it.date })
    collectJob.cancel()
  }

  @Test
  fun `historyGroups updates reactively when a new entry is recorded`() = runTest(testDispatcher) {
    val reader = FakeJournalEntryReader()
    val viewModel = JournalHistoryViewModel(reader, ZoneOffset.UTC)
    val collectJob = launchCollection(viewModel)

    reader.entries.value = listOf(
      JournalEntry(
        id = 1,
        timestamp = Instant.parse("2026-08-26T10:00:00Z"),
        note = "first",
        source = JournalSource.APP,
        deliveryStatus = DeliveryStatus.NOT_REQUIRED,
      ),
    )
    testDispatcher.scheduler.advanceUntilIdle()
    assertEquals(1, viewModel.historyGroups.value.single().items.size)

    reader.entries.value = reader.entries.value + JournalEntry(
      id = 2,
      timestamp = Instant.parse("2026-08-26T12:00:00Z"),
      note = "second",
      source = JournalSource.APP,
      deliveryStatus = DeliveryStatus.NOT_REQUIRED,
    )
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(listOf("second", "first"), viewModel.historyGroups.value.single().items.map { it.note })
    collectJob.cancel()
  }

  private fun launchCollection(viewModel: JournalHistoryViewModel) =
    CoroutineScope(testDispatcher).launch { viewModel.historyGroups.collect {} }

  private class FakeJournalEntryReader : JournalEntryReader {
    val entries = MutableStateFlow<List<JournalEntry>>(emptyList())

    override fun observeAll() = entries
  }
}
