package info.bvlion.journalingpost.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import info.bvlion.journalingpost.journal.DeliveryStatus
import info.bvlion.journalingpost.journal.IntegrationRoutingJournalRecorder
import info.bvlion.journalingpost.journal.JournalEntry
import info.bvlion.journalingpost.journal.JournalEntryRepository
import info.bvlion.journalingpost.journal.JournalSource
import info.bvlion.journalingpost.journal.LocalOnlyJournalRecorder
import info.bvlion.journalingpost.journal.LocalWebhookJournalRecorder
import info.bvlion.journalingpost.poster.JournalPoster
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreAnalysisIntegrationRepositoryTest {
  @get:Rule
  val tempFolder = TemporaryFolder()

  private fun createRepository(): DataStoreAnalysisIntegrationRepository {
    val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
      produceFile = { File(tempFolder.root, "record_mode_settings.preferences_pb") },
    )
    return DataStoreAnalysisIntegrationRepository(dataStore)
  }

  @Test
  fun `未保存の初期状態ではCUSTOM_WEBHOOKになる`() = runTest {
    val repository = createRepository()

    assertEquals(AnalysisIntegration.CUSTOM_WEBHOOK, repository.analysisIntegration.first())
  }

  @Test
  fun `setAnalysisIntegrationで保存した選択を再取得できる`() = runTest {
    val repository = createRepository()

    repository.setAnalysisIntegration(AnalysisIntegration.NONE)

    assertEquals(AnalysisIntegration.NONE, repository.analysisIntegration.first())
  }

  @Test
  fun `名称変更前に保存されたLOCAL_ONLYはNONEとして読める`() = runTest {
    val dataStore = PreferenceDataStoreFactory.create(
      produceFile = { File(tempFolder.root, "record_mode_settings.preferences_pb") },
    )
    dataStore.edit { it[stringPreferencesKey("record_mode")] = "LOCAL_ONLY" }

    assertEquals(AnalysisIntegration.NONE, DataStoreAnalysisIntegrationRepository(dataStore).analysisIntegration.first())
  }

  @Test
  fun `名称変更前に保存されたLOCAL_AND_WEBHOOKはCUSTOM_WEBHOOKとして読める`() = runTest {
    val dataStore = PreferenceDataStoreFactory.create(
      produceFile = { File(tempFolder.root, "record_mode_settings.preferences_pb") },
    )
    dataStore.edit { it[stringPreferencesKey("record_mode")] = "LOCAL_AND_WEBHOOK" }

    assertEquals(
      AnalysisIntegration.CUSTOM_WEBHOOK,
      DataStoreAnalysisIntegrationRepository(dataStore).analysisIntegration.first(),
    )
  }

  @Test
  fun `保存した選択は同じファイルを指す別のrepositoryインスタンスからも読める`() = runTest {
    val file = File(tempFolder.root, "record_mode_settings.preferences_pb")
    val dataStore = PreferenceDataStoreFactory.create(produceFile = { file })
    DataStoreAnalysisIntegrationRepository(dataStore).setAnalysisIntegration(AnalysisIntegration.NONE)

    val reloaded = DataStoreAnalysisIntegrationRepository(dataStore)

    assertEquals(AnalysisIntegration.NONE, reloaded.analysisIntegration.first())
  }

  @Test
  fun `DataStoreへのwrite完了前でもsetAnalysisIntegration呼び出し直後にanalysisIntegrationへ反映される`() = runTest {
    val repository = DataStoreAnalysisIntegrationRepository(BlockingWriteDataStore())

    backgroundScope.launch { repository.setAnalysisIntegration(AnalysisIntegration.NONE) }
    runCurrent() // BlockingWriteDataStoreのwriteは完了しないため、pendingIntegration反映直後まで進む

    assertEquals(AnalysisIntegration.NONE, repository.analysisIntegration.first())
  }

  @Test
  fun `write未完了のまま使用しない選択直後に開始した記録はWebhookへルーティングされない`() = runTest {
    val journalRepository = FakeJournalEntryRepository()
    val repository = DataStoreAnalysisIntegrationRepository(BlockingWriteDataStore())
    var postCalled = false
    val recorder = IntegrationRoutingJournalRecorder(
      analysisIntegrationRepository = repository,
      localOnlyRecorder = LocalOnlyJournalRecorder(journalRepository),
      localWebhookRecorder = LocalWebhookJournalRecorder(
        journalRepository,
        JournalPoster { postCalled = true; true },
      ),
    )

    backgroundScope.launch { repository.setAnalysisIntegration(AnalysisIntegration.NONE) }
    runCurrent()
    val result = recorder.record("today was good", mood = null, source = JournalSource.APP)

    assertEquals(DeliveryStatus.NOT_REQUIRED, result)
    assertFalse(postCalled)
  }

  @Test
  fun `DataStore読み込みがIOExceptionを投げた場合はNONEへ倒す`() = runTest {
    val repository = DataStoreAnalysisIntegrationRepository(ThrowingDataStore(IOException("disk error")))

    assertEquals(AnalysisIntegration.NONE, repository.analysisIntegration.first())
  }

  @Test
  fun `DataStore読み込みがIOException以外を投げた場合は再送出される`() = runTest {
    val repository = DataStoreAnalysisIntegrationRepository(ThrowingDataStore(IllegalStateException("boom")))

    var thrown: Throwable? = null
    try {
      repository.analysisIntegration.first()
    } catch (e: IllegalStateException) {
      thrown = e
    }

    assertEquals("boom", thrown?.message)
  }

  @Test
  fun `read IOExceptionから復旧すると同じ購読が新しい永続値を取得できる`() = runTest {
    val repository = DataStoreAnalysisIntegrationRepository(RecoveringDataStore())

    val collected = mutableListOf<AnalysisIntegration>()
    val job = launch { repository.analysisIntegration.collect { collected += it } }
    advanceUntilIdle()

    assertEquals(listOf(AnalysisIntegration.NONE, AnalysisIntegration.CUSTOM_WEBHOOK), collected)
    job.cancel()
  }

  @Test
  fun `read error後にwriteが成功するとpending解消後も新しい選択を維持する`() = runTest {
    val repository = DataStoreAnalysisIntegrationRepository(RecoveringDataStore())
    assertEquals(AnalysisIntegration.NONE, repository.analysisIntegration.first())

    repository.setAnalysisIntegration(AnalysisIntegration.CUSTOM_WEBHOOK)

    assertEquals(AnalysisIntegration.CUSTOM_WEBHOOK, repository.analysisIntegration.first())
  }

  @Test
  fun `write中にキャンセルされてもCancellationExceptionが伝播しそのpendingは片付く`() = runTest {
    val repository = DataStoreAnalysisIntegrationRepository(BlockingWriteDataStore())
    var thrown: Throwable? = null

    val job = launch {
      try {
        repository.setAnalysisIntegration(AnalysisIntegration.NONE)
      } catch (e: CancellationException) {
        thrown = e
        throw e
      }
    }
    runCurrent()
    assertEquals(AnalysisIntegration.NONE, repository.analysisIntegration.first())

    job.cancelAndJoin()

    assertTrue(thrown is CancellationException)
    assertEquals(AnalysisIntegration.CUSTOM_WEBHOOK, repository.analysisIntegration.first())
  }

  @Test
  fun `キャンセルされた古いwriteは新しい選択のpendingを消さない`() = runTest {
    val repository = DataStoreAnalysisIntegrationRepository(ControllableWriteDataStore())

    val job = launch { repository.setAnalysisIntegration(AnalysisIntegration.NONE) }
    runCurrent()
    backgroundScope.launch { repository.setAnalysisIntegration(AnalysisIntegration.CUSTOM_WEBHOOK) }
    runCurrent()
    assertEquals(AnalysisIntegration.CUSTOM_WEBHOOK, repository.analysisIntegration.first())

    job.cancelAndJoin()

    assertEquals(AnalysisIntegration.CUSTOM_WEBHOOK, repository.analysisIntegration.first())
  }

  @Test
  fun `write失敗時はsetAnalysisIntegrationが例外を投げる`() = runTest {
    val repository = DataStoreAnalysisIntegrationRepository(FailingWriteDataStore(IOException("disk error")))

    var thrown: Throwable? = null
    try {
      repository.setAnalysisIntegration(AnalysisIntegration.NONE)
    } catch (e: IOException) {
      thrown = e
    }

    assertEquals("disk error", thrown?.message)
  }

  @Test
  fun `write失敗後はanalysisIntegrationが永続化前の有効な選択へ戻り楽観的なpendingが残り続けない`() = runTest {
    val repository = DataStoreAnalysisIntegrationRepository(FailingWriteDataStore(IOException("disk error")))
    assertEquals(AnalysisIntegration.CUSTOM_WEBHOOK, repository.analysisIntegration.first())

    try {
      repository.setAnalysisIntegration(AnalysisIntegration.NONE)
    } catch (e: IOException) {
    }

    assertEquals(AnalysisIntegration.CUSTOM_WEBHOOK, repository.analysisIntegration.first())
  }

  @Test
  fun `複数の選択変更が重なった場合、古いwriteの完了は新しい選択のpendingを巻き戻さない`() = runTest {
    val dataStore = ControllableWriteDataStore()
    val repository = DataStoreAnalysisIntegrationRepository(dataStore)

    backgroundScope.launch { repository.setAnalysisIntegration(AnalysisIntegration.NONE) }
    runCurrent()
    backgroundScope.launch { repository.setAnalysisIntegration(AnalysisIntegration.CUSTOM_WEBHOOK) }
    runCurrent()

    assertEquals(AnalysisIntegration.CUSTOM_WEBHOOK, repository.analysisIntegration.first())

    dataStore.completeWrite(0)
    runCurrent()
    assertEquals(AnalysisIntegration.CUSTOM_WEBHOOK, repository.analysisIntegration.first())

    dataStore.completeWrite(1)
    runCurrent()
    assertEquals(AnalysisIntegration.CUSTOM_WEBHOOK, repository.analysisIntegration.first())
  }

  @Test
  fun `複数の選択変更が重なった場合、古いwriteの失敗は新しい選択のpendingへ影響しない`() = runTest {
    val dataStore = ControllableWriteDataStore()
    val repository = DataStoreAnalysisIntegrationRepository(dataStore)

    backgroundScope.launch {
      try {
        repository.setAnalysisIntegration(AnalysisIntegration.NONE)
      } catch (e: IOException) {
        // backgroundScopeのjobを失敗させないためここでcatchする。
      }
    }
    runCurrent()
    backgroundScope.launch { repository.setAnalysisIntegration(AnalysisIntegration.CUSTOM_WEBHOOK) }
    runCurrent()

    assertEquals(AnalysisIntegration.CUSTOM_WEBHOOK, repository.analysisIntegration.first())

    dataStore.failWrite(0, IOException("disk error"))
    runCurrent()
    assertEquals(AnalysisIntegration.CUSTOM_WEBHOOK, repository.analysisIntegration.first())

    dataStore.completeWrite(1)
    runCurrent()
    assertEquals(AnalysisIntegration.CUSTOM_WEBHOOK, repository.analysisIntegration.first())
  }

  @Test
  fun `DataStore読み込みIOException時は記録がWebhookへルーティングされずlocal記録は継続できる`() = runTest {
    val journalRepository = FakeJournalEntryRepository()
    val repository = DataStoreAnalysisIntegrationRepository(ThrowingDataStore(IOException("disk error")))
    var postCalled = false
    val recorder = IntegrationRoutingJournalRecorder(
      analysisIntegrationRepository = repository,
      localOnlyRecorder = LocalOnlyJournalRecorder(journalRepository),
      localWebhookRecorder = LocalWebhookJournalRecorder(
        journalRepository,
        JournalPoster { postCalled = true; true },
      ),
    )

    val result = recorder.record("today was good", mood = null, source = JournalSource.APP)

    assertEquals(DeliveryStatus.NOT_REQUIRED, result)
    assertFalse(postCalled)
    assertEquals("today was good", journalRepository.entries.values.single().note)
  }

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

  /** updateData()の完了/失敗を呼び出し順と切り離して制御し、write完了順の入れ替わりを再現するFake。 */
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

  /** dataの最初の購読だけIOExceptionを投げ、以降はbackingを反映するFake。 */
  private class RecoveringDataStore : DataStore<Preferences> {
    private val backing = MutableStateFlow(emptyPreferences())
    private var readAttempt = 0

    override val data: Flow<Preferences> = flow {
      readAttempt++
      if (readAttempt == 1) throw IOException("disk error")
      emitAll(backing)
    }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
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
