package info.bvlion.journalingpost

import info.bvlion.journalingpost.journal.JournalEntry
import info.bvlion.journalingpost.journal.JournalEntryDeleter
import info.bvlion.journalingpost.journal.JournalEntryReader
import info.bvlion.journalingpost.journal.JournalSource
import info.bvlion.journalingpost.journal.history.JournalHistoryUiState
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
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

  // Channel由来のdeleteFailuresは購読者がいる間だけ流れるため、テスト中はこのscopeで購読し続ける。
  private val collectorScope = CoroutineScope(testDispatcher)

  // 「今日」に依存する挙動を検証するため、テストからは固定値で与えて必要な場合だけ進める。
  private var now = Instant.parse("2026-08-26T10:00:00Z")

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
  fun `uiStateはreaderの初回発行前はLoadingで空の一覧と区別される`() = runTest(testDispatcher) {
    val reader = FakeJournalEntryReader()
    val viewModel = createViewModel(reader)
    val collectJob = launchCollection(viewModel)
    testDispatcher.scheduler.runCurrent()

    assertEquals(JournalHistoryUiState.Loading, viewModel.uiState.value)
    collectJob.cancel()
  }

  @Test
  fun `初期表示は今日で今日の記録だけを持つ`() = runTest(testDispatcher) {
    val reader = FakeJournalEntryReader()
    val viewModel = createViewModel(reader)
    val collectJob = launchCollection(viewModel)

    reader.emit(
      listOf(
        entry(id = 1, at = "2026-08-25T10:00:00Z", note = "yesterday"),
        entry(id = 2, at = "2026-08-26T09:00:00Z", note = "today morning"),
        entry(id = 3, at = "2026-08-26T21:00:00Z", note = "today night"),
      ),
    )
    testDispatcher.scheduler.advanceUntilIdle()

    val content = viewModel.content()
    assertEquals(LocalDate.of(2026, 8, 26), content.selectedDate)
    assertEquals(LocalDate.of(2026, 8, 26), content.today)
    assertEquals(LocalDate.of(2026, 8, 25), content.earliestDate)
    assertEquals(listOf("today night", "today morning"), content.selectedItems.map { it.note })
    assertTrue(content.isToday)
    collectJob.cancel()
  }

  @Test
  fun `記録が1件も無い場合は今日だけが表示できる範囲になる`() = runTest(testDispatcher) {
    val reader = FakeJournalEntryReader()
    val viewModel = createViewModel(reader)
    val collectJob = launchCollection(viewModel)

    reader.emit(emptyList())
    testDispatcher.scheduler.advanceUntilIdle()

    val content = viewModel.content()
    assertTrue(content.selectedItems.isEmpty())
    assertFalse(content.hasAnyEntry)
    assertEquals(LocalDate.of(2026, 8, 26), content.earliestDate)
    assertTrue(content.isToday)
    assertTrue(content.isEarliestDate)
    collectJob.cancel()
  }

  @Test
  fun `記録はあるが選択日に無い場合は一覧が空でhasAnyEntryはtrueになる`() = runTest(testDispatcher) {
    val reader = FakeJournalEntryReader()
    val viewModel = createViewModel(reader)
    val collectJob = launchCollection(viewModel)
    reader.emit(listOf(entry(id = 1, at = "2026-08-24T10:00:00Z", note = "old")))
    testDispatcher.scheduler.advanceUntilIdle()

    val content = viewModel.content()
    assertTrue(content.selectedItems.isEmpty())
    assertTrue(content.hasAnyEntry)
    assertEquals(LocalDate.of(2026, 8, 24), content.earliestDate)
    collectJob.cancel()
  }

  @Test
  fun `showPreviousDayは記録の無い日も飛ばさず1日ずつ戻る`() = runTest(testDispatcher) {
    val reader = FakeJournalEntryReader()
    val viewModel = createViewModel(reader)
    val collectJob = launchCollection(viewModel)
    reader.emit(listOf(entry(id = 1, at = "2026-08-24T10:00:00Z", note = "old")))
    testDispatcher.scheduler.advanceUntilIdle()

    viewModel.showPreviousDay()
    testDispatcher.scheduler.advanceUntilIdle()
    assertEquals(LocalDate.of(2026, 8, 25), viewModel.content().selectedDate)
    assertTrue(viewModel.content().selectedItems.isEmpty())

    viewModel.showPreviousDay()
    testDispatcher.scheduler.advanceUntilIdle()
    assertEquals(LocalDate.of(2026, 8, 24), viewModel.content().selectedDate)
    assertEquals(listOf("old"), viewModel.content().selectedItems.map { it.note })
    collectJob.cancel()
  }

  @Test
  fun `selectDateへ最古の記録日より前を渡すと最古の記録日へ丸められる`() = runTest(testDispatcher) {
    val reader = FakeJournalEntryReader()
    val viewModel = createViewModel(reader)
    val collectJob = launchCollection(viewModel)
    reader.emit(listOf(entry(id = 1, at = "2026-08-24T10:00:00Z", note = "oldest")))
    testDispatcher.scheduler.advanceUntilIdle()

    viewModel.selectDate(LocalDate.of(2020, 1, 1))
    testDispatcher.scheduler.advanceUntilIdle()

    val content = viewModel.content()
    assertEquals(LocalDate.of(2026, 8, 24), content.selectedDate)
    assertEquals(listOf("oldest"), content.selectedItems.map { it.note })
    assertTrue(content.isEarliestDate)
    assertFalse(content.isToday)
    collectJob.cancel()
  }

  @Test
  fun `showPreviousDayは最古の記録日で止まる`() = runTest(testDispatcher) {
    val reader = FakeJournalEntryReader()
    val viewModel = createViewModel(reader)
    val collectJob = launchCollection(viewModel)
    reader.emit(listOf(entry(id = 1, at = "2026-08-20T10:00:00Z", note = "oldest")))
    testDispatcher.scheduler.advanceUntilIdle()
    viewModel.selectDate(LocalDate.of(2026, 8, 20))
    testDispatcher.scheduler.advanceUntilIdle()

    viewModel.showPreviousDay()
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(LocalDate.of(2026, 8, 20), viewModel.content().selectedDate)
    collectJob.cancel()
  }

  @Test
  fun `最古の記録日の最後の記録が削除されると下限が新しい最古日へ動く`() = runTest(testDispatcher) {
    val reader = FakeJournalEntryReader()
    val viewModel = createViewModel(reader)
    val collectJob = launchCollection(viewModel)
    reader.emit(
      listOf(
        entry(id = 1, at = "2026-08-20T10:00:00Z", note = "oldest"),
        entry(id = 2, at = "2026-08-24T10:00:00Z", note = "second oldest"),
      ),
    )
    testDispatcher.scheduler.advanceUntilIdle()
    viewModel.selectDate(LocalDate.of(2026, 8, 20))
    testDispatcher.scheduler.advanceUntilIdle()
    assertEquals(LocalDate.of(2026, 8, 20), viewModel.content().earliestDate)

    // 最古の記録(id=1)を削除する。表示していた日(id=1の日)が下限より前になるため、
    // 新しい下限である残り最古の日(id=2の日)まで表示日が動く。
    viewModel.deleteEntry(1)
    testDispatcher.scheduler.advanceUntilIdle()
    reader.emit(listOf(entry(id = 2, at = "2026-08-24T10:00:00Z", note = "second oldest")))
    testDispatcher.scheduler.advanceUntilIdle()

    val content = viewModel.content()
    assertEquals(LocalDate.of(2026, 8, 24), content.earliestDate)
    assertEquals(LocalDate.of(2026, 8, 24), content.selectedDate)
    assertEquals(listOf("second oldest"), content.selectedItems.map { it.note })
    collectJob.cancel()
  }

  @Test
  fun `全ての記録が削除されると今日だけを表示する`() = runTest(testDispatcher) {
    val reader = FakeJournalEntryReader()
    val viewModel = createViewModel(reader)
    val collectJob = launchCollection(viewModel)
    reader.emit(listOf(entry(id = 1, at = "2026-08-20T10:00:00Z", note = "only")))
    testDispatcher.scheduler.advanceUntilIdle()
    viewModel.selectDate(LocalDate.of(2026, 8, 20))
    testDispatcher.scheduler.advanceUntilIdle()

    viewModel.deleteEntry(1)
    testDispatcher.scheduler.advanceUntilIdle()
    reader.emit(emptyList())
    testDispatcher.scheduler.advanceUntilIdle()

    val content = viewModel.content()
    assertEquals(LocalDate.of(2026, 8, 26), content.selectedDate)
    assertEquals(LocalDate.of(2026, 8, 26), content.earliestDate)
    assertFalse(content.hasAnyEntry)
    assertTrue(content.isToday)
    collectJob.cancel()
  }

  @Test
  fun `showNextDayは今日より先へ進まない`() = runTest(testDispatcher) {
    val reader = FakeJournalEntryReader()
    val viewModel = createViewModel(reader)
    val collectJob = launchCollection(viewModel)
    reader.emit(emptyList())
    testDispatcher.scheduler.advanceUntilIdle()

    viewModel.showNextDay()
    viewModel.showNextDay()
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(LocalDate.of(2026, 8, 26), viewModel.content().selectedDate)
    assertTrue(viewModel.content().isToday)
    collectJob.cancel()
  }

  @Test
  fun `showNextDayは過去を表示中なら1日進む`() = runTest(testDispatcher) {
    val reader = FakeJournalEntryReader()
    val viewModel = createViewModel(reader)
    val collectJob = launchCollection(viewModel)
    reader.emit(listOf(entry(id = 1, at = "2026-08-15T10:00:00Z", note = "oldest")))
    testDispatcher.scheduler.advanceUntilIdle()
    viewModel.selectDate(LocalDate.of(2026, 8, 20))
    testDispatcher.scheduler.advanceUntilIdle()

    viewModel.showNextDay()
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(LocalDate.of(2026, 8, 21), viewModel.content().selectedDate)
    collectJob.cancel()
  }

  @Test
  fun `selectDateへ未来日を渡しても今日までへ丸められる`() = runTest(testDispatcher) {
    val reader = FakeJournalEntryReader()
    val viewModel = createViewModel(reader)
    val collectJob = launchCollection(viewModel)
    reader.emit(emptyList())
    testDispatcher.scheduler.advanceUntilIdle()

    viewModel.selectDate(LocalDate.of(2026, 9, 30))
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(LocalDate.of(2026, 8, 26), viewModel.content().selectedDate)
    collectJob.cancel()
  }

  @Test
  fun `showTodayは過去を表示中でも今日へ戻る`() = runTest(testDispatcher) {
    val reader = FakeJournalEntryReader()
    val viewModel = createViewModel(reader)
    val collectJob = launchCollection(viewModel)
    reader.emit(listOf(entry(id = 1, at = "2025-01-01T10:00:00Z", note = "oldest")))
    testDispatcher.scheduler.advanceUntilIdle()
    viewModel.selectDate(LocalDate.of(2026, 1, 5))
    testDispatcher.scheduler.advanceUntilIdle()

    viewModel.showToday()
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(LocalDate.of(2026, 8, 26), viewModel.content().selectedDate)
    assertTrue(viewModel.content().isToday)
    collectJob.cancel()
  }

  @Test
  fun `日付が変わった後のshowTodayは新しい今日を表示する`() = runTest(testDispatcher) {
    val reader = FakeJournalEntryReader()
    val viewModel = createViewModel(reader)
    val collectJob = launchCollection(viewModel)
    reader.emit(emptyList())
    testDispatcher.scheduler.advanceUntilIdle()

    now = Instant.parse("2026-08-27T01:00:00Z")
    viewModel.showToday()
    testDispatcher.scheduler.advanceUntilIdle()

    val content = viewModel.content()
    assertEquals(LocalDate.of(2026, 8, 27), content.selectedDate)
    assertEquals(LocalDate.of(2026, 8, 27), content.today)
    assertTrue(content.isToday)
    collectJob.cancel()
  }

  @Test
  fun `選択日の判定はUTCではなく指定したタイムゾーンを使う`() = runTest(testDispatcher) {
    val reader = FakeJournalEntryReader()
    // UTCでは8/26 23:30だが、+09:00では8/27 08:30になり、日付境界を跨ぐ。
    now = Instant.parse("2026-08-26T23:30:00Z")
    val viewModel = createViewModel(reader, zoneId = ZoneOffset.ofHours(9))
    val collectJob = launchCollection(viewModel)

    reader.emit(listOf(entry(id = 1, at = "2026-08-26T23:00:00Z", note = "late")))
    testDispatcher.scheduler.advanceUntilIdle()

    val content = viewModel.content()
    assertEquals(LocalDate.of(2026, 8, 27), content.selectedDate)
    assertEquals(listOf("late"), content.selectedItems.map { it.note })
    collectJob.cancel()
  }

  @Test
  fun `uiStateは選択日の記録が追加されると反応して更新される`() = runTest(testDispatcher) {
    val reader = FakeJournalEntryReader()
    val viewModel = createViewModel(reader)
    val collectJob = launchCollection(viewModel)

    reader.emit(listOf(entry(id = 1, at = "2026-08-26T10:00:00Z", note = "first")))
    testDispatcher.scheduler.advanceUntilIdle()
    assertEquals(1, viewModel.content().selectedItems.size)

    reader.emit(
      listOf(
        entry(id = 1, at = "2026-08-26T10:00:00Z", note = "first"),
        entry(id = 2, at = "2026-08-26T12:00:00Z", note = "second"),
      ),
    )
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(listOf("second", "first"), viewModel.content().selectedItems.map { it.note })
    collectJob.cancel()
  }

  @Test
  fun `deleteEntryは指定したidだけを削除対象としてdeleterへ渡す`() = runTest(testDispatcher) {
    val deleter = FakeJournalEntryDeleter()
    val viewModel = createViewModel(FakeJournalEntryReader(), deleter = deleter)
    val failures = collectDeleteFailures(viewModel)

    viewModel.deleteEntry(2)
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(listOf(2L), deleter.deletedIds)
    assertEquals(0, failures.size)
  }

  @Test
  fun `削除後の一覧はreaderが再発行した内容をそのまま反映する`() = runTest(testDispatcher) {
    val reader = FakeJournalEntryReader()
    val viewModel = createViewModel(reader)
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

    assertEquals(listOf("first"), viewModel.content().selectedItems.map { it.note })
    collectJob.cancel()
  }

  @Test
  fun `最後の1件を削除してreaderが0件を発行するとhasAnyEntryがfalseになる`() = runTest(testDispatcher) {
    val reader = FakeJournalEntryReader()
    val viewModel = createViewModel(reader)
    val collectJob = launchCollection(viewModel)
    reader.emit(listOf(entry(id = 1, at = "2026-08-26T10:00:00Z", note = "only")))
    testDispatcher.scheduler.advanceUntilIdle()

    viewModel.deleteEntry(1)
    testDispatcher.scheduler.advanceUntilIdle()
    reader.emit(emptyList())
    testDispatcher.scheduler.advanceUntilIdle()

    val content = viewModel.content()
    assertTrue(content.selectedItems.isEmpty())
    assertFalse(content.hasAnyEntry)
    collectJob.cancel()
  }

  @Test
  fun `削除に失敗すると未処理例外にならず削除失敗を1度だけ通知する`() = runTest(testDispatcher) {
    val deleter = FakeJournalEntryDeleter(failNextDeletes = 1)
    val viewModel = createViewModel(FakeJournalEntryReader(), deleter = deleter)
    val failures = collectDeleteFailures(viewModel)

    viewModel.deleteEntry(1)
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(1, failures.size)
  }

  @Test
  fun `削除に失敗した後に成功しても再度は通知しない`() = runTest(testDispatcher) {
    val deleter = FakeJournalEntryDeleter(failNextDeletes = 1)
    val viewModel = createViewModel(FakeJournalEntryReader(), deleter = deleter)
    val failures = collectDeleteFailures(viewModel)
    viewModel.deleteEntry(1)
    testDispatcher.scheduler.advanceUntilIdle()
    assertEquals(1, failures.size)

    viewModel.deleteEntry(1)
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(1, failures.size)
  }

  private fun createViewModel(
    reader: JournalEntryReader,
    deleter: JournalEntryDeleter = FakeJournalEntryDeleter(),
    zoneId: ZoneId = ZoneOffset.UTC,
  ) = JournalHistoryViewModel(reader, deleter, zoneId) { now }

  private fun JournalHistoryViewModel.content() = uiState.value as JournalHistoryUiState.Content

  private val JournalHistoryUiState.Content.selectedItems get() = itemsOn(selectedDate)

  private fun entry(id: Long, at: String, note: String) = JournalEntry(
    id = id,
    timestamp = Instant.parse(at),
    note = note,
    source = JournalSource.APP,
  )

  private fun launchCollection(viewModel: JournalHistoryViewModel) =
    CoroutineScope(testDispatcher).launch { viewModel.uiState.collect {} }

  private fun collectDeleteFailures(viewModel: JournalHistoryViewModel): List<Unit> {
    val failures = mutableListOf<Unit>()
    collectorScope.launch { viewModel.deleteFailures.collect { failures += it } }
    return failures
  }

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
