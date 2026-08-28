package info.bvlion.journalingpost.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

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
}
