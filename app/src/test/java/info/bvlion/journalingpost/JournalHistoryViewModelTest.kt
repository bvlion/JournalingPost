package info.bvlion.journalingpost

import info.bvlion.journalingpost.journal.DeliveryStatus
import info.bvlion.journalingpost.journal.JournalEntry
import info.bvlion.journalingpost.journal.JournalEntryDeleter
import info.bvlion.journalingpost.journal.JournalEntryReader
import info.bvlion.journalingpost.journal.JournalSource
import info.bvlion.journalingpost.journal.history.JournalHistoryUiState
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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
  fun `uiStateはreaderの初回発行前はLoadingで空状態と区別される`() = runTest(testDispatcher) {
    val reader = FakeJournalEntryReader()
    val viewModel = JournalHistoryViewModel(reader, FakeJournalEntryDeleter(), ZoneOffset.UTC)
    val collectJob = launchCollection(viewModel)
    testDispatcher.scheduler.runCurrent()

    assertEquals(JournalHistoryUiState.Loading, viewModel.uiState.value)
    collectJob.cancel()
  }

  @Test
  fun `uiStateは読み込み完了後に0件ならEmptyになる`() = runTest(testDispatcher) {
    val reader = FakeJournalEntryReader()
    val viewModel = JournalHistoryViewModel(reader, FakeJournalEntryDeleter(), ZoneOffset.UTC)
    val collectJob = launchCollection(viewModel)

    reader.emit(emptyList())
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(JournalHistoryUiState.Empty, viewModel.uiState.value)
    collectJob.cancel()
  }

  @Test
  fun `uiStateはreaderの発行後に日付ごとのグループを反映する`() = runTest(testDispatcher) {
    val reader = FakeJournalEntryReader()
    val viewModel = JournalHistoryViewModel(reader, FakeJournalEntryDeleter(), ZoneOffset.UTC)
    val collectJob = launchCollection(viewModel)

    reader.emit(listOf(entry(id = 1, at = "2026-08-26T10:00:00Z", note = "today")))
    testDispatcher.scheduler.advanceUntilIdle()

    val groups = (viewModel.uiState.value as JournalHistoryUiState.Content).groups
    assertEquals(listOf(LocalDate.of(2026, 8, 26)), groups.map { it.date })
    collectJob.cancel()
  }

  @Test
  fun `uiStateは新しい記録が追加されると反応して更新される`() = runTest(testDispatcher) {
    val reader = FakeJournalEntryReader()
    val viewModel = JournalHistoryViewModel(reader, FakeJournalEntryDeleter(), ZoneOffset.UTC)
    val collectJob = launchCollection(viewModel)

    reader.emit(listOf(entry(id = 1, at = "2026-08-26T10:00:00Z", note = "first")))
    testDispatcher.scheduler.advanceUntilIdle()
    assertEquals(1, (viewModel.uiState.value as JournalHistoryUiState.Content).groups.single().items.size)

    reader.emit(
      listOf(
        entry(id = 1, at = "2026-08-26T10:00:00Z", note = "first"),
        entry(id = 2, at = "2026-08-26T12:00:00Z", note = "second"),
      ),
    )
    testDispatcher.scheduler.advanceUntilIdle()

    val items = (viewModel.uiState.value as JournalHistoryUiState.Content).groups.single().items
    assertEquals(listOf("second", "first"), items.map { it.note })
    collectJob.cancel()
  }

  @Test
  fun `deleteEntryは指定したidだけを削除対象としてdeleterへ渡す`() = runTest(testDispatcher) {
    val deleter = FakeJournalEntryDeleter()
    val viewModel = JournalHistoryViewModel(FakeJournalEntryReader(), deleter, ZoneOffset.UTC)

    viewModel.deleteEntry(2)
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(listOf(2L), deleter.deletedIds)
    assertFalse(viewModel.deleteFailed.value)
  }

  @Test
  fun `削除後の一覧はreaderが再発行した内容をそのまま反映する`() = runTest(testDispatcher) {
    val reader = FakeJournalEntryReader()
    val deleter = FakeJournalEntryDeleter()
    val viewModel = JournalHistoryViewModel(reader, deleter, ZoneOffset.UTC)
    val collectJob = launchCollection(viewModel)
    reader.emit(
      listOf(
        entry(id = 1, at = "2026-08-26T10:00:00Z", note = "first"),
        entry(id = 2, at = "2026-08-26T12:00:00Z", note = "second"),
      ),
    )
    testDispatcher.scheduler.advanceUntilIdle()

    viewModel.deleteEntry(2)
    testDispatcher.scheduler.advanceUntilIdle()
    reader.emit(listOf(entry(id = 1, at = "2026-08-26T10:00:00Z", note = "first")))
    testDispatcher.scheduler.advanceUntilIdle()

    val items = (viewModel.uiState.value as JournalHistoryUiState.Content).groups.single().items
    assertEquals(listOf("first"), items.map { it.note })
    collectJob.cancel()
  }

  @Test
  fun `最後の1件を削除してreaderが0件を発行するとEmptyになる`() = runTest(testDispatcher) {
    val reader = FakeJournalEntryReader()
    val viewModel = JournalHistoryViewModel(reader, FakeJournalEntryDeleter(), ZoneOffset.UTC)
    val collectJob = launchCollection(viewModel)
    reader.emit(listOf(entry(id = 1, at = "2026-08-26T10:00:00Z", note = "only")))
    testDispatcher.scheduler.advanceUntilIdle()

    viewModel.deleteEntry(1)
    testDispatcher.scheduler.advanceUntilIdle()
    reader.emit(emptyList())
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(JournalHistoryUiState.Empty, viewModel.uiState.value)
    collectJob.cancel()
  }

  @Test
  fun `削除に失敗すると未処理例外にならずdeleteFailedがtrueになる`() = runTest(testDispatcher) {
    val deleter = FakeJournalEntryDeleter(failNextDeletes = 1)
    val viewModel = JournalHistoryViewModel(FakeJournalEntryReader(), deleter, ZoneOffset.UTC)

    viewModel.deleteEntry(1)
    testDispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.deleteFailed.value)
  }

  @Test
  fun `履歴画面へ入り直すと前回の削除失敗表示は残らない`() = runTest(testDispatcher) {
    val deleter = FakeJournalEntryDeleter(failNextDeletes = 1)
    val viewModel = JournalHistoryViewModel(FakeJournalEntryReader(), deleter, ZoneOffset.UTC)
    viewModel.deleteEntry(1)
    testDispatcher.scheduler.advanceUntilIdle()
    assertTrue(viewModel.deleteFailed.value)

    viewModel.onHistoryOpened()

    assertFalse(viewModel.deleteFailed.value)
  }

  @Test
  fun `削除に失敗した後に成功するとdeleteFailedがfalseへ戻る`() = runTest(testDispatcher) {
    val deleter = FakeJournalEntryDeleter(failNextDeletes = 1)
    val viewModel = JournalHistoryViewModel(FakeJournalEntryReader(), deleter, ZoneOffset.UTC)
    viewModel.deleteEntry(1)
    testDispatcher.scheduler.advanceUntilIdle()
    assertTrue(viewModel.deleteFailed.value)

    viewModel.deleteEntry(1)
    testDispatcher.scheduler.advanceUntilIdle()

    assertFalse(viewModel.deleteFailed.value)
  }

  private fun entry(id: Long, at: String, note: String) = JournalEntry(
    id = id,
    timestamp = Instant.parse(at),
    note = note,
    source = JournalSource.APP,
    deliveryStatus = DeliveryStatus.NOT_REQUIRED,
  )

  private fun launchCollection(viewModel: JournalHistoryViewModel) =
    CoroutineScope(testDispatcher).launch { viewModel.uiState.collect {} }

  /** 初回発行前の状態を再現するため、購読開始時点では値を持たないflowを使う。 */
  private class FakeJournalEntryReader : JournalEntryReader {
    private val entries = MutableSharedFlow<List<JournalEntry>>(replay = 1, extraBufferCapacity = 8)

    fun emit(entries: List<JournalEntry>) {
      check(this.entries.tryEmit(entries))
    }

    override fun observeAll(): Flow<List<JournalEntry>> = entries
  }

  private class FakeJournalEntryDeleter(
    private var failNextDeletes: Int = 0,
  ) : JournalEntryDeleter {
    private val _deletedIds = mutableListOf<Long>()
    val deletedIds: List<Long> get() = _deletedIds

    override suspend fun delete(id: Long) {
      if (failNextDeletes > 0) {
        failNextDeletes--
        throw IOException("db error")
      }
      _deletedIds += id
    }
  }
}
