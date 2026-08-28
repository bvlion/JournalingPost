package info.bvlion.journalingpost.journal

import info.bvlion.journalingpost.mood.MoodSnapshot
import info.bvlion.journalingpost.poster.JournalPoster
import info.bvlion.journalingpost.settings.RecordMode
import info.bvlion.journalingpost.settings.RecordModeRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ModeRoutingJournalRecorderTest {

  @Test
  fun `LOCAL_ONLYモードではJournalPosterが呼ばれずNOT_REQUIREDで保存される`() = runTest {
    val repository = FakeJournalEntryRepository()
    var postCalled = false
    val recorder = createRecorder(
      repository = repository,
      mode = RecordMode.LOCAL_ONLY,
      poster = { postCalled = true; true },
    )

    val result = recorder.record("today was good", mood = null, source = JournalSource.APP)

    assertEquals(DeliveryStatus.NOT_REQUIRED, result)
    assertFalse(postCalled)
    assertEquals(DeliveryStatus.NOT_REQUIRED, repository.entries.values.single().deliveryStatus)
  }

  @Test
  fun `LOCAL_AND_WEBHOOKモードではWebhookへ送信されSENTになる`() = runTest {
    val repository = FakeJournalEntryRepository()
    val recorder = createRecorder(repository = repository, mode = RecordMode.LOCAL_AND_WEBHOOK, poster = { true })

    val result = recorder.record("today was good", mood = null, source = JournalSource.APP)

    assertEquals(DeliveryStatus.SENT, result)
    assertEquals(DeliveryStatus.SENT, repository.entries.values.single().deliveryStatus)
  }

  @Test
  fun `記録開始時点のモードを1回だけ取得する`() = runTest {
    val repository = FakeJournalEntryRepository()
    val modeRepository = CountingRecordModeRepository(RecordMode.LOCAL_ONLY)
    val recorder = ModeRoutingJournalRecorder(
      recordModeRepository = modeRepository,
      localOnlyRecorder = LocalOnlyJournalRecorder(repository),
      localWebhookRecorder = LocalWebhookJournalRecorder(repository, JournalPoster { true }),
    )

    recorder.record("today was good", mood = null, source = JournalSource.APP)

    assertEquals(1, modeRepository.accessCount)
  }

  @Test
  fun `モードを変更しても既存のJournalEntryは保持される`() = runTest {
    val repository = FakeJournalEntryRepository()
    val modeRepository = FakeRecordModeRepository(RecordMode.LOCAL_AND_WEBHOOK)
    val recorder = ModeRoutingJournalRecorder(
      recordModeRepository = modeRepository,
      localOnlyRecorder = LocalOnlyJournalRecorder(repository),
      localWebhookRecorder = LocalWebhookJournalRecorder(repository, JournalPoster { true }),
    )

    recorder.record("first", mood = null, source = JournalSource.APP)
    modeRepository.setRecordMode(RecordMode.LOCAL_ONLY)
    recorder.record("second", mood = null, source = JournalSource.APP)

    assertEquals(2, repository.entries.size)
    assertEquals("first", repository.entries.getValue(1L).note)
    assertEquals(DeliveryStatus.SENT, repository.entries.getValue(1L).deliveryStatus)
    assertEquals("second", repository.entries.getValue(2L).note)
    assertEquals(DeliveryStatus.NOT_REQUIRED, repository.entries.getValue(2L).deliveryStatus)
  }

  private fun createRecorder(
    repository: FakeJournalEntryRepository,
    mode: RecordMode,
    poster: (String) -> Boolean,
  ) = ModeRoutingJournalRecorder(
    recordModeRepository = FakeRecordModeRepository(mode),
    localOnlyRecorder = LocalOnlyJournalRecorder(repository),
    localWebhookRecorder = LocalWebhookJournalRecorder(repository, JournalPoster { poster(it) }),
  )

  private class FakeRecordModeRepository(initialMode: RecordMode) : RecordModeRepository {
    private val state = MutableStateFlow(initialMode)
    override val recordMode: Flow<RecordMode> = state

    override suspend fun setRecordMode(mode: RecordMode) {
      state.value = mode
    }
  }

  private class CountingRecordModeRepository(private val mode: RecordMode) : RecordModeRepository {
    var accessCount = 0
      private set

    override val recordMode: Flow<RecordMode>
      get() {
        accessCount++
        return MutableStateFlow(mode)
      }

    override suspend fun setRecordMode(mode: RecordMode) = error("not used in this test")
  }

  private class FakeJournalEntryRepository : JournalEntryRepository {
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
