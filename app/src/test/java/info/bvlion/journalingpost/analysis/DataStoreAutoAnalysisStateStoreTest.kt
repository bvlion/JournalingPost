package info.bvlion.journalingpost.analysis

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreAutoAnalysisStateStoreTest {
  @get:Rule
  val tempFolder = TemporaryFolder()

  private fun createStore(): DataStoreAutoAnalysisStateStore {
    val dataStore = PreferenceDataStoreFactory.create(
      produceFile = { File(tempFolder.root, "auto_analysis_state.preferences_pb") },
    )
    return DataStoreAutoAnalysisStateStore(dataStore)
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
  fun `未記録なら予約timezoneはnull`() = runTest {
    assertNull(createStore().scheduledZoneId())
  }

  @Test
  fun `記録した予約timezoneを再取得でき解除もできる`() = runTest {
    val store = createStore()

    store.setScheduledZoneId("Asia/Tokyo")
    assertEquals("Asia/Tokyo", store.scheduledZoneId())

    store.setScheduledZoneId("Europe/London")
    assertEquals("Europe/London", store.scheduledZoneId())

    store.clearScheduledZoneId()
    assertNull(store.scheduledZoneId())
  }

  @Test
  fun `試行日と予約timezoneは独立して保持する`() = runTest {
    val store = createStore()

    store.recordHostedAttempt(LocalDate.of(2026, 3, 1))
    store.setScheduledZoneId("Asia/Tokyo")
    store.clearScheduledZoneId()

    assertEquals(LocalDate.of(2026, 3, 1), store.lastHostedAttemptDate())
    assertNull(store.scheduledZoneId())
  }

  @Test
  fun `読み込みに失敗したらnullへ倒す`() = runTest {
    val store = DataStoreAutoAnalysisStateStore(ThrowingDataStore(RuntimeException("disk error")))

    assertNull(store.lastHostedAttemptDate())
    assertNull(store.scheduledZoneId())
  }

  private class ThrowingDataStore(private val error: Throwable) : DataStore<Preferences> {
    override val data: Flow<Preferences> = flow { throw error }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
      error("not used in this test")
  }
}
