package info.bvlion.journalingpost.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import info.bvlion.journalingpost.journal.DeliveryStatus
import info.bvlion.journalingpost.journal.JournalEntry
import info.bvlion.journalingpost.journal.JournalEntryRepository
import info.bvlion.journalingpost.journal.JournalSource
import info.bvlion.journalingpost.journal.LocalOnlyJournalRecorder
import info.bvlion.journalingpost.journal.LocalWebhookJournalRecorder
import info.bvlion.journalingpost.journal.ModeRoutingJournalRecorder
import info.bvlion.journalingpost.poster.JournalPoster
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreRecordModeRepositoryTest {
  @get:Rule
  val tempFolder = TemporaryFolder()

  private fun createRepository(): DataStoreRecordModeRepository {
    val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
      produceFile = { File(tempFolder.root, "record_mode_settings.preferences_pb") },
    )
    return DataStoreRecordModeRepository(dataStore)
  }

  @Test
  fun `初期モードはLOCAL_AND_WEBHOOKになる`() = runTest {
    val repository = createRepository()

    assertEquals(RecordMode.LOCAL_AND_WEBHOOK, repository.recordMode.first())
  }

  @Test
  fun `setRecordModeで保存したモードを再取得できる`() = runTest {
    val repository = createRepository()

    repository.setRecordMode(RecordMode.LOCAL_ONLY)

    assertEquals(RecordMode.LOCAL_ONLY, repository.recordMode.first())
  }

  @Test
  fun `保存したモードは同じファイルを指す別のrepositoryインスタンスからも読める`() = runTest {
    val file = File(tempFolder.root, "record_mode_settings.preferences_pb")
    val dataStore = PreferenceDataStoreFactory.create(produceFile = { file })
    DataStoreRecordModeRepository(dataStore).setRecordMode(RecordMode.LOCAL_ONLY)

    val reloaded = DataStoreRecordModeRepository(dataStore)

    assertEquals(RecordMode.LOCAL_ONLY, reloaded.recordMode.first())
  }

  @Test
  fun `DataStoreへのwrite完了前でもsetRecordMode呼び出し直後にrecordModeへ反映される`() = runTest {
    val repository = DataStoreRecordModeRepository(BlockingWriteDataStore())

    backgroundScope.launch { repository.setRecordMode(RecordMode.LOCAL_ONLY) }
    // setRecordMode()内のdataStore.edit(実体はupdateData)はBlockingWriteDataStoreにより
    // このテストの間ずっと完了しない。pendingModeへの同期反映はそれより前に行われるため、
    // runCurrent()でその手前まで進めた時点で既にLOCAL_ONLYが読めることを確認する。
    runCurrent()

    assertEquals(RecordMode.LOCAL_ONLY, repository.recordMode.first())
  }

  @Test
  fun `write未完了のままLOCAL_ONLY選択直後に開始した記録はWebhookへルーティングされない`() = runTest {
    val journalRepository = FakeJournalEntryRepository()
    val repository = DataStoreRecordModeRepository(BlockingWriteDataStore())
    var postCalled = false
    val recorder = ModeRoutingJournalRecorder(
      recordModeRepository = repository,
      localOnlyRecorder = LocalOnlyJournalRecorder(journalRepository),
      localWebhookRecorder = LocalWebhookJournalRecorder(
        journalRepository,
        JournalPoster { postCalled = true; true },
        isWebhookConfigured = { true },
      ),
    )

    backgroundScope.launch { repository.setRecordMode(RecordMode.LOCAL_ONLY) }
    runCurrent()
    val result = recorder.record("today was good", mood = null, source = JournalSource.APP)

    assertEquals(DeliveryStatus.NOT_REQUIRED, result)
    assertFalse(postCalled)
  }

  @Test
  fun `DataStore読み込みがIOExceptionを投げた場合はLOCAL_ONLYへ倒す`() = runTest {
    val repository = DataStoreRecordModeRepository(ThrowingDataStore(IOException("disk error")))

    assertEquals(RecordMode.LOCAL_ONLY, repository.recordMode.first())
  }

  @Test
  fun `DataStore読み込みがIOException以外を投げた場合は再送出される`() = runTest {
    val repository = DataStoreRecordModeRepository(ThrowingDataStore(IllegalStateException("boom")))

    var thrown: Throwable? = null
    try {
      repository.recordMode.first()
    } catch (e: IllegalStateException) {
      thrown = e
    }

    assertEquals("boom", thrown?.message)
  }

  @Test
  fun `write失敗時はsetRecordModeが例外を投げる`() = runTest {
    val repository = DataStoreRecordModeRepository(FailingWriteDataStore(IOException("disk error")))

    var thrown: Throwable? = null
    try {
      repository.setRecordMode(RecordMode.LOCAL_ONLY)
    } catch (e: IOException) {
      thrown = e
    }

    assertEquals("disk error", thrown?.message)
  }

  @Test
  fun `write失敗後はrecordModeが永続化前の有効なモードへ戻り楽観的なpendingが残り続けない`() = runTest {
    val repository = DataStoreRecordModeRepository(FailingWriteDataStore(IOException("disk error")))
    assertEquals(RecordMode.LOCAL_AND_WEBHOOK, repository.recordMode.first())

    try {
      repository.setRecordMode(RecordMode.LOCAL_ONLY)
    } catch (e: IOException) {
      // このテストではwrite失敗後の状態のみ検証する。
    }

    assertEquals(RecordMode.LOCAL_AND_WEBHOOK, repository.recordMode.first())
  }

  @Test
  fun `複数のモード変更が重なった場合、古いwriteの完了は新しい選択のpendingを巻き戻さない`() = runTest {
    val dataStore = ControllableWriteDataStore()
    val repository = DataStoreRecordModeRepository(dataStore)

    backgroundScope.launch { repository.setRecordMode(RecordMode.LOCAL_ONLY) }
    runCurrent()
    backgroundScope.launch { repository.setRecordMode(RecordMode.LOCAL_AND_WEBHOOK) }
    runCurrent()

    assertEquals(RecordMode.LOCAL_AND_WEBHOOK, repository.recordMode.first())

    // 1回目(古い)のwriteが後から完了しても、2回目(新しい)の選択が巻き戻らない。
    dataStore.completeWrite(0)
    runCurrent()
    assertEquals(RecordMode.LOCAL_AND_WEBHOOK, repository.recordMode.first())

    dataStore.completeWrite(1)
    runCurrent()
    assertEquals(RecordMode.LOCAL_AND_WEBHOOK, repository.recordMode.first())
  }

  @Test
  fun `複数のモード変更が重なった場合、古いwriteの失敗は新しい選択のpendingへ影響しない`() = runTest {
    val dataStore = ControllableWriteDataStore()
    val repository = DataStoreRecordModeRepository(dataStore)

    backgroundScope.launch {
      try {
        repository.setRecordMode(RecordMode.LOCAL_ONLY)
      } catch (e: IOException) {
        // 古い(1回目の)選択のwrite失敗。ここでは新しい選択への非干渉のみ検証する。
      }
    }
    runCurrent()
    backgroundScope.launch { repository.setRecordMode(RecordMode.LOCAL_AND_WEBHOOK) }
    runCurrent()

    assertEquals(RecordMode.LOCAL_AND_WEBHOOK, repository.recordMode.first())

    dataStore.failWrite(0, IOException("disk error"))
    runCurrent()
    assertEquals(RecordMode.LOCAL_AND_WEBHOOK, repository.recordMode.first())

    dataStore.completeWrite(1)
    runCurrent()
    assertEquals(RecordMode.LOCAL_AND_WEBHOOK, repository.recordMode.first())
  }

  @Test
  fun `DataStore読み込みIOException時は記録がWebhookへルーティングされずlocal記録は継続できる`() = runTest {
    val journalRepository = FakeJournalEntryRepository()
    val repository = DataStoreRecordModeRepository(ThrowingDataStore(IOException("disk error")))
    var postCalled = false
    val recorder = ModeRoutingJournalRecorder(
      recordModeRepository = repository,
      localOnlyRecorder = LocalOnlyJournalRecorder(journalRepository),
      localWebhookRecorder = LocalWebhookJournalRecorder(
        journalRepository,
        JournalPoster { postCalled = true; true },
        isWebhookConfigured = { true },
      ),
    )

    val result = recorder.record("today was good", mood = null, source = JournalSource.APP)

    assertEquals(DeliveryStatus.NOT_REQUIRED, result)
    assertFalse(postCalled)
    assertEquals("today was good", journalRepository.entries.values.single().note)
  }

  /** dataStore.editの実体であるupdateData()を、テスト中ずっと完了しないようにするFake。 */
  private class BlockingWriteDataStore : DataStore<Preferences> {
    override val data: Flow<Preferences> = MutableStateFlow(emptyPreferences())

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
      delay(Long.MAX_VALUE)
      error("unreachable: このテストではwriteを意図的に完了させない")
    }
  }

  private class FailingWriteDataStore(private val error: Throwable) : DataStore<Preferences> {
    override val data: Flow<Preferences> = MutableStateFlow(emptyPreferences())

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw error
  }

  /**
   * updateData()の呼び出しごとにgate(index順)を積み、テスト側がcompleteWrite/failWriteで
   * 呼び出し順とは独立に完了・失敗させられるFake。複数のsetRecordMode呼び出しが重なった
   * 場合の完了/失敗順序を制御するために使う。
   */
  private class ControllableWriteDataStore : DataStore<Preferences> {
    private val backing = MutableStateFlow(emptyPreferences())
    override val data: Flow<Preferences> = backing
    private val gates = mutableListOf<CompletableDeferred<Throwable?>>()

    fun completeWrite(index: Int) {
      gates[index].complete(null)
    }

    fun failWrite(index: Int, error: Throwable) {
      gates[index].complete(error)
    }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
      val gate = CompletableDeferred<Throwable?>()
      gates += gate
      val error = gate.await()
      if (error != null) throw error
      val updated = transform(backing.value)
      backing.value = updated
      return updated
    }
  }

  private class ThrowingDataStore(private val error: Throwable) : DataStore<Preferences> {
    override val data: Flow<Preferences> = flow { throw error }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
      error("not used in this test")
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
