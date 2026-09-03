package info.bvlion.journalingpost.hosted

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

class DataStoreHostedConsentRepositoryTest {
  @get:Rule
  val tempFolder = TemporaryFolder()

  private fun createRepository(): DataStoreHostedConsentRepository {
    val dataStore = PreferenceDataStoreFactory.create(
      produceFile = { File(tempFolder.root, "hosted_consent_state.preferences_pb") },
    )
    return DataStoreHostedConsentRepository(dataStore)
  }

  @Test
  fun `未保存の初期状態は未同意扱いになる`() = runTest {
    assertEquals(false, createRepository().hasConsented.first())
  }

  @Test
  fun `同意を記録すると再取得できる`() = runTest {
    val repository = createRepository()

    repository.markConsented()

    assertEquals(true, repository.hasConsented.first())
  }

  @Test
  fun `DataStore読み込みがIOExceptionなら未同意扱いへ倒す`() = runTest {
    val repository = DataStoreHostedConsentRepository(ThrowingDataStore(IOException("disk error")))

    assertEquals(false, repository.hasConsented.first())
  }

  @Test
  fun `DataStore読み込みの非IOExceptionは再送出する`() = runTest {
    val repository = DataStoreHostedConsentRepository(ThrowingDataStore(IllegalStateException("boom")))

    var thrown: Throwable? = null
    try {
      repository.hasConsented.first()
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
