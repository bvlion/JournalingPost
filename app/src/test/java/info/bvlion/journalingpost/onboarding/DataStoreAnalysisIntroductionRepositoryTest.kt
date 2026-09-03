package info.bvlion.journalingpost.onboarding

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

class DataStoreAnalysisIntroductionRepositoryTest {
  @get:Rule
  val tempFolder = TemporaryFolder()

  private fun createRepository(): DataStoreAnalysisIntroductionRepository {
    val dataStore = PreferenceDataStoreFactory.create(
      produceFile = { File(tempFolder.root, "analysis_introduction_state.preferences_pb") },
    )
    return DataStoreAnalysisIntroductionRepository(dataStore)
  }

  @Test
  fun `未保存の初期状態は未案内扱いになる`() = runTest {
    assertEquals(false, createRepository().isIntroductionSeen.first())
  }

  @Test
  fun `既読を記録すると再取得できる`() = runTest {
    val repository = createRepository()

    repository.markIntroductionSeen()

    assertEquals(true, repository.isIntroductionSeen.first())
  }

  @Test
  fun `DataStore読み込みがIOExceptionなら未案内扱いへ倒す`() = runTest {
    val repository = DataStoreAnalysisIntroductionRepository(ThrowingDataStore(IOException("disk error")))

    assertEquals(false, repository.isIntroductionSeen.first())
  }

  @Test
  fun `DataStore読み込みの非IOExceptionは再送出する`() = runTest {
    val repository = DataStoreAnalysisIntroductionRepository(ThrowingDataStore(IllegalStateException("boom")))

    var thrown: Throwable? = null
    try {
      repository.isIntroductionSeen.first()
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
