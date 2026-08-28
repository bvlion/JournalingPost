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

/**
 * MainViewModelFactory/SettingsViewModelFactoryがそれぞれ別々にRecordModeRepositoryを
 * 生成すると、DataStoreRecordModeRepository.pendingMode(記録開始時点のモードをDataStore
 * write完了前でも参照できるようにするための即時反映用の値)がインスタンスごとに分かれてしまい、
 * 設定画面での変更が記録処理側へ伝わらない競合が再発する。ここではRecordModeRepositoryStore
 * がその2箇所に対して常に同一インスタンスを返すことを検証する。
 */
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
      // MainViewModelFactory.initialize()とSettingsViewModelFactory.initialize()が
      // それぞれ別のタイミングでRecordModeRepositoryStore.getInstance(context)を呼ぶ状況を再現する。
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
          isWebhookConfigured = { true },
        ),
      )

      backgroundScope.launch { settingsSideRepository.setRecordMode(RecordMode.LOCAL_ONLY) }
      runCurrent()
      val result = recorder.record("today was good", mood = null, source = JournalSource.APP)

      assertEquals(DeliveryStatus.NOT_REQUIRED, result)
      assertFalse(postCalled)
    }

  /** dataStore.editの実体であるupdateData()を、テスト中ずっと完了しないようにするFake。 */
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
