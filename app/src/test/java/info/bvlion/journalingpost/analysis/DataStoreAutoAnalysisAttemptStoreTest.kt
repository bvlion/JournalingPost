package info.bvlion.journalingpost.analysis

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import info.bvlion.journalingpost.journal.JournalEntry
import info.bvlion.journalingpost.journal.JournalSource
import java.io.File
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreAutoAnalysisAttemptStoreTest {
  @get:Rule
  val tempFolder = TemporaryFolder()

  private fun createStore(): DataStoreAutoAnalysisAttemptStore {
    val dataStore = PreferenceDataStoreFactory.create(
      produceFile = { File(tempFolder.root, "auto_analysis_state.preferences_pb") },
    )
    return DataStoreAutoAnalysisAttemptStore(dataStore)
  }

  @Test
  fun `未記録なら試行日はnull`() = runTest {
    assertNull(createStore().lastHostedAttemptDate())
  }

  @Test
  fun `記録した試行日を再取得できる`() = runTest {
    val store = createStore()

    store.recordHostedAttempt(LocalDate.of(2026, 3, 1))

    assertEquals(LocalDate.of(2026, 3, 1), store.lastHostedAttemptDate())
  }

  @Test
  fun `試行日は最後に記録した日で上書きされる`() = runTest {
    val store = createStore()

    store.recordHostedAttempt(LocalDate.of(2026, 3, 1))
    store.recordHostedAttempt(LocalDate.of(2026, 3, 2))

    assertEquals(LocalDate.of(2026, 3, 2), store.lastHostedAttemptDate())
  }

  @Test
  fun `Hosted retryの対象期間と記録を保存して再取得できる`() = runTest {
    val store = createStore()
    val snapshot = HostedAutoAnalysisRetrySnapshot(
      analysisDate = LocalDate.of(2026, 3, 1),
      periodStart = Instant.parse("2026-03-01T00:00:00Z"),
      periodEnd = Instant.parse("2026-03-02T00:00:00Z"),
      entries = listOf(
        JournalEntry(
          id = 42,
          timestamp = Instant.parse("2026-03-01T12:34:56Z"),
          moodId = "mood-id",
          moodEmoji = "🙂",
          moodLabel = "穏やか",
          note = "記録",
          source = JournalSource.WIDGET,
        ),
      ),
    )

    store.storeHostedRetrySnapshot(snapshot)

    assertEquals(snapshot, store.hostedRetrySnapshot())
  }

  @Test
  fun `Hosted retryのsnapshotを破棄できる`() = runTest {
    val store = createStore()
    store.storeHostedRetrySnapshot(
      HostedAutoAnalysisRetrySnapshot(
        analysisDate = LocalDate.of(2026, 3, 1),
        periodStart = Instant.parse("2026-03-01T00:00:00Z"),
        periodEnd = Instant.parse("2026-03-02T00:00:00Z"),
        entries = emptyList(),
      ),
    )

    store.clearHostedRetrySnapshot()

    assertNull(store.hostedRetrySnapshot())
  }

  @Test
  fun `読み込みに失敗したらnullへ倒す`() = runTest {
    val store = DataStoreAutoAnalysisAttemptStore(ThrowingDataStore(RuntimeException("disk error")))

    assertNull(store.lastHostedAttemptDate())
  }

  private class ThrowingDataStore(private val error: Throwable) : DataStore<Preferences> {
    override val data: Flow<Preferences> = flow { throw error }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
      error("not used in this test")
  }
}
