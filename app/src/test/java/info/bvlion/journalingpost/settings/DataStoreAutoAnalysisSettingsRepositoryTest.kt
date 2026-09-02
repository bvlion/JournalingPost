package info.bvlion.journalingpost.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import java.io.IOException
import java.time.LocalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreAutoAnalysisSettingsRepositoryTest {
  @get:Rule
  val tempFolder = TemporaryFolder()

  private fun createRepository(): DataStoreAutoAnalysisSettingsRepository {
    val dataStore = PreferenceDataStoreFactory.create(
      produceFile = { File(tempFolder.root, "auto_analysis_settings.preferences_pb") },
    )
    return DataStoreAutoAnalysisSettingsRepository(dataStore)
  }

  @Test
  fun `未保存の初期状態は既定値になる`() = runTest {
    assertEquals(AutoAnalysisSettings.DEFAULT, createRepository().autoAnalysisSettings.first())
  }

  @Test
  fun `保存した設定を再取得できる`() = runTest {
    val repository = createRepository()
    val settings = AutoAnalysisSettings(
      enabled = true,
      timeOfDay = LocalTime.of(21, 30),
      targetDay = AutoAnalysisTargetDay.TODAY,
    )

    repository.setAutoAnalysisSettings(settings)

    assertEquals(settings, repository.autoAnalysisSettings.first())
  }

  @Test
  fun `時刻は分単位で保存し再取得できる`() = runTest {
    val repository = createRepository()

    repository.setAutoAnalysisSettings(
      AutoAnalysisSettings(enabled = false, timeOfDay = LocalTime.of(7, 15), targetDay = AutoAnalysisTargetDay.YESTERDAY),
    )

    assertEquals(LocalTime.of(7, 15), repository.autoAnalysisSettings.first().timeOfDay)
  }

  @Test
  fun `DataStore読み込みがIOExceptionなら既定値へ倒す`() = runTest {
    val repository = DataStoreAutoAnalysisSettingsRepository(ThrowingDataStore(IOException("disk error")))

    assertEquals(AutoAnalysisSettings.DEFAULT, repository.autoAnalysisSettings.first())
  }

  @Test
  fun `DataStore読み込みの非IOExceptionは再送出する`() = runTest {
    val repository = DataStoreAutoAnalysisSettingsRepository(ThrowingDataStore(IllegalStateException("boom")))

    var thrown: Throwable? = null
    try {
      repository.autoAnalysisSettings.first()
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
