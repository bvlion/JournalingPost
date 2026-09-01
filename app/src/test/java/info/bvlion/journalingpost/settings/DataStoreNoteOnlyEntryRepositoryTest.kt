package info.bvlion.journalingpost.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreNoteOnlyEntryRepositoryTest {
  @get:Rule
  val tempFolder = TemporaryFolder()

  private fun createRepository(): DataStoreNoteOnlyEntryRepository {
    val dataStore = PreferenceDataStoreFactory.create(
      produceFile = { File(tempFolder.root, "note_only_entry_settings.preferences_pb") },
    )
    return DataStoreNoteOnlyEntryRepository(dataStore)
  }

  @Test
  fun `未保存の初期状態は無効になる`() = runTest {
    assertEquals(false, createRepository().isNoteOnlyEntryEnabled.first())
  }

  @Test
  fun `保存した表示設定を再取得できる`() = runTest {
    val repository = createRepository()

    repository.setNoteOnlyEntryEnabled(true)

    assertEquals(true, repository.isNoteOnlyEntryEnabled.first())
  }

  @Test
  fun `DataStore読み込みがIOExceptionなら無効へ倒す`() = runTest {
    val repository = DataStoreNoteOnlyEntryRepository(ThrowingDataStore(IOException("disk error")))

    assertEquals(false, repository.isNoteOnlyEntryEnabled.first())
  }

  @Test
  fun `DataStore読み込みの非IOExceptionは再送出する`() = runTest {
    val repository = DataStoreNoteOnlyEntryRepository(ThrowingDataStore(IllegalStateException("boom")))

    var thrown: Throwable? = null
    try {
      repository.isNoteOnlyEntryEnabled.first()
    } catch (e: IllegalStateException) {
      thrown = e
    }

    assertEquals("boom", thrown?.message)
  }

  private class ThrowingDataStore(private val error: Throwable) : DataStore<Preferences> {
    override val data: Flow<Preferences> = flow { throw error }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
      error("not used in this test")
  }
}
