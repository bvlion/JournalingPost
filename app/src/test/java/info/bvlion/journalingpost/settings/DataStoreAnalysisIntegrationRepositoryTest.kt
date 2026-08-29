package info.bvlion.journalingpost.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
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

class DataStoreAnalysisIntegrationRepositoryTest {
  @get:Rule
  val tempFolder = TemporaryFolder()

  private fun createRepository(): DataStoreAnalysisIntegrationRepository {
    val dataStore = PreferenceDataStoreFactory.create(
      produceFile = { File(tempFolder.root, "analysis_integration_settings.preferences_pb") },
    )
    return DataStoreAnalysisIntegrationRepository(dataStore)
  }

  @Test
  fun `未保存の初期状態はNONEになる`() = runTest {
    assertEquals(AnalysisIntegration.NONE, createRepository().analysisIntegration.first())
  }

  @Test
  fun `保存した解析連携方法を再取得できる`() = runTest {
    val repository = createRepository()

    repository.setAnalysisIntegration(AnalysisIntegration.CUSTOM_WEBHOOK)

    assertEquals(AnalysisIntegration.CUSTOM_WEBHOOK, repository.analysisIntegration.first())
  }

  @Test
  fun `旧record_modeだけが残っていても互換読み替えせずNONEになる`() = runTest {
    val preferences = preferencesOf(stringPreferencesKey("record_mode") to "LOCAL_AND_WEBHOOK")
    val repository = DataStoreAnalysisIntegrationRepository(StaticDataStore(preferences))

    assertEquals(AnalysisIntegration.NONE, repository.analysisIntegration.first())
  }

  @Test
  fun `使用しない選択はwrite完了前から記録のWebhook送信を止める`() = runTest {
    val dataStore = BlockingWriteDataStore(preferencesWith(AnalysisIntegration.CUSTOM_WEBHOOK))
    val integrationRepository = DataStoreAnalysisIntegrationRepository(dataStore)
    val journalRepository = FakeJournalEntryRepository()
    var postCalled = false
    val recorder = IntegrationRoutingJournalRecorder(
      analysisIntegrationRepository = integrationRepository,
      localOnlyRecorder = LocalOnlyJournalRecorder(journalRepository),
      localWebhookRecorder = LocalWebhookJournalRecorder(journalRepository, JournalPoster { postCalled = true; true }),
    )

    backgroundScope.launch { integrationRepository.setAnalysisIntegration(AnalysisIntegration.NONE) }
    runCurrent()
    val result = recorder.record("today was good", mood = null, source = JournalSource.APP)

    assertEquals(DeliveryStatus.NOT_REQUIRED, result)
    assertFalse(postCalled)
  }

  @Test
  fun `DataStore読み込みがIOExceptionならNONEへ倒す`() = runTest {
    val repository = DataStoreAnalysisIntegrationRepository(ThrowingDataStore(IOException("disk error")))

    assertEquals(AnalysisIntegration.NONE, repository.analysisIntegration.first())
  }

  @Test
  fun `DataStore読み込みの非IOExceptionは再送出する`() = runTest {
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
  fun `write失敗後は永続化済みの選択へ戻る`() = runTest {
    val repository = DataStoreAnalysisIntegrationRepository(
      FailingWriteDataStore(preferencesWith(AnalysisIntegration.CUSTOM_WEBHOOK), IOException("disk error")),
    )

    try {
      repository.setAnalysisIntegration(AnalysisIntegration.NONE)
    } catch (e: IOException) {
    }

    assertEquals(AnalysisIntegration.CUSTOM_WEBHOOK, repository.analysisIntegration.first())
  }

  private fun preferencesWith(integration: AnalysisIntegration): Preferences =
    preferencesOf(stringPreferencesKey("analysis_integration") to integration.name)

  private class StaticDataStore(initial: Preferences) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)
    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
      val updated = transform(state.value)
      state.value = updated
      return updated
    }
  }

  private class BlockingWriteDataStore(initial: Preferences) : DataStore<Preferences> {
    override val data: Flow<Preferences> = MutableStateFlow(initial)

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
      delay(Long.MAX_VALUE)
      error("unreachable")
    }
  }

  private class FailingWriteDataStore(initial: Preferences, private val error: Throwable) : DataStore<Preferences> {
    override val data: Flow<Preferences> = MutableStateFlow(initial)

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw error
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
