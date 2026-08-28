package info.bvlion.journalingpost.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import info.bvlion.journalingpost.journal.DeliveryStatus
import info.bvlion.journalingpost.journal.JournalEntry
import info.bvlion.journalingpost.journal.JournalEntryRepository
import info.bvlion.journalingpost.journal.JournalSource
import info.bvlion.journalingpost.journal.LocalOnlyJournalRecorder
import info.bvlion.journalingpost.journal.LocalWebhookJournalRecorder
import info.bvlion.journalingpost.journal.ModeRoutingJournalRecorder
import info.bvlion.journalingpost.poster.JournalPoster
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecordModeRepositoryStoreTest {

  @Before
  fun setUp() {
    RecordModeRepositoryStore.resetForTesting()
  }

  @Test
  fun `getInstanceは同一DataStoreに対して常に同じrepositoryインスタンスを返す`() {
    val dataStore = BlockingWriteDataStore()

    val first = RecordModeRepositoryStore.getInstance(dataStore)
    val second = RecordModeRepositoryStore.getInstance(dataStore)

    assertSame(first, second)
  }

  @Test
  fun `Settings側とMain側が別々にgetInstanceしてもwrite未完了のLOCAL_ONLYがMain側の記録へ反映される`() =
    runTest {
      val dataStore = BlockingWriteDataStore()
      val settingsSideRepository = RecordModeRepositoryStore.getInstance(dataStore)
      val mainSideRepository = RecordModeRepositoryStore.getInstance(dataStore)

      val journalRepository = FakeJournalEntryRepository()
      var postCalled = false
      val recorder = ModeRoutingJournalRecorder(
        recordModeRepository = mainSideRepository,
        localOnlyRecorder = LocalOnlyJournalRecorder(journalRepository),
        localWebhookRecorder = LocalWebhookJournalRecorder(
          journalRepository,
          JournalPoster { postCalled = true; true },
        ),
      )

      backgroundScope.launch { settingsSideRepository.setRecordMode(RecordMode.LOCAL_ONLY) }
      runCurrent()
      val result = recorder.record("today was good", mood = null, source = JournalSource.APP)

      assertEquals(DeliveryStatus.NOT_REQUIRED, result)
      assertFalse(postCalled)
    }

  private class BlockingWriteDataStore : DataStore<Preferences> {
    override val data: Flow<Preferences> = MutableStateFlow(emptyPreferences())

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
      delay(Long.MAX_VALUE)
      error("unreachable: このテストではwriteを意図的に完了させない")
    }
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
