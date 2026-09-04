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

class DataStoreMoodNoteInputRepositoryTest {
  @get:Rule
  val tempFolder = TemporaryFolder()

  private fun createRepository(): DataStoreMoodNoteInputRepository {
    val dataStore = PreferenceDataStoreFactory.create(
      produceFile = { File(tempFolder.root, "mood_note_input_settings.preferences_pb") },
    )
    return DataStoreMoodNoteInputRepository(dataStore)
  }

  @Test
  fun `未保存の初期状態ではメモ入力を開かない`() = runTest {
    assertEquals(false, createRepository().isMoodNoteInputInitiallyOpen.first())
  }

  @Test
  fun `保存した初期表示設定を再取得できる`() = runTest {
    val repository = createRepository()

    repository.setMoodNoteInputInitiallyOpen(true)

    assertEquals(true, repository.isMoodNoteInputInitiallyOpen.first())
  }

  @Test
  fun `DataStore読み込みがIOExceptionならメモ入力を開かない`() = runTest {
    val repository = DataStoreMoodNoteInputRepository(ThrowingDataStore(IOException("disk error")))

    assertEquals(false, repository.isMoodNoteInputInitiallyOpen.first())
  }

  @Test
  fun `DataStore読み込みの非IOExceptionは再送出する`() = runTest {
    val repository = DataStoreMoodNoteInputRepository(ThrowingDataStore(IllegalStateException("boom")))

    var thrown: Throwable? = null
    try {
      repository.isMoodNoteInputInitiallyOpen.first()
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
