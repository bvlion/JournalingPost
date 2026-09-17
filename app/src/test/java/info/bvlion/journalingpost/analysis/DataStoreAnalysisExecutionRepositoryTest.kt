package info.bvlion.journalingpost.analysis

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreAnalysisExecutionRepositoryTest {
  @get:Rule
  val tempFolder = TemporaryFolder()

  private fun createRepository(): DataStoreAnalysisExecutionRepository {
    val dataStore = PreferenceDataStoreFactory.create(
      produceFile = { File(tempFolder.root, "analysis_execution_state.preferences_pb") },
    )
    return DataStoreAnalysisExecutionRepository(dataStore)
  }

  @Test
  fun `未保存なら送信済み記録とHosted成功日は空になる`() = runTest {
    val state = createRepository().state.first()

    assertTrue(state.entryIdsByResultId.isEmpty())
    assertTrue(state.hostedSuccessfulDays.isEmpty())
  }

  @Test
  fun `解析成功時の結果idと送信記録idとHosted成功日を保存する`() = runTest {
    val repository = createRepository()

    repository.recordSuccess(
      resultId = 7,
      entryIds = setOf(11, 12),
      hostedSuccessfulDay = LocalDate.of(2026, 8, 30),
    )

    assertEquals(
      AnalysisExecutionState(
        entryIdsByResultId = mapOf(7L to setOf(11L, 12L)),
        hostedSuccessfulDays = setOf(LocalDate.of(2026, 8, 30)),
      ),
      repository.state.first(),
    )
  }

  @Test
  fun `結果削除時は送信記録との対応だけを消してHosted成功日は残す`() = runTest {
    val repository = createRepository()
    repository.recordSuccess(
      resultId = 7,
      entryIds = setOf(11),
      hostedSuccessfulDay = LocalDate.of(2026, 8, 30),
    )

    repository.removeResult(7)

    val state = repository.state.first()
    assertTrue(state.entryIdsByResultId.isEmpty())
    assertEquals(setOf(LocalDate.of(2026, 8, 30)), state.hostedSuccessfulDays)
  }
}
