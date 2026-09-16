package info.bvlion.journalingpost.analysis

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** AnalysisResultとは分離して永続化する、解析成功時の入力とHosted成功日の事実。 */
data class AnalysisExecutionState(
  val entryIdsByResultId: Map<Long, Set<Long>> = emptyMap(),
  val hostedSuccessfulDays: Set<LocalDate> = emptySet(),
)

/** Room schemaを変えず、実際に送信した記録とHostedの再解析防止状態を保持する。 */
interface AnalysisExecutionRepository {
  val state: Flow<AnalysisExecutionState>

  suspend fun recordSuccess(
    resultId: Long,
    entryIds: Set<Long>,
    hostedSuccessfulDay: LocalDate?,
  )

  suspend fun removeResult(resultId: Long)
}

internal class DataStoreAnalysisExecutionRepository(
  private val dataStore: DataStore<Preferences>,
) : AnalysisExecutionRepository {
  override val state: Flow<AnalysisExecutionState> = dataStore.data
    .map { preferences ->
      preferences[KEY_STATE]?.let { raw ->
        Json.decodeFromString<StoredState>(raw).toState()
      } ?: AnalysisExecutionState()
    }
    .catch { exception ->
      if (exception is CancellationException) throw exception
      emit(AnalysisExecutionState())
    }

  override suspend fun recordSuccess(
    resultId: Long,
    entryIds: Set<Long>,
    hostedSuccessfulDay: LocalDate?,
  ) {
    dataStore.edit { preferences ->
      val current = preferences[KEY_STATE]?.let { raw ->
        try {
          Json.decodeFromString<StoredState>(raw)
        } catch (e: Exception) {
          StoredState()
        }
      } ?: StoredState()
      val hostedSuccessfulEpochDays = if (hostedSuccessfulDay == null) {
        current.hostedSuccessfulEpochDays
      } else {
        current.hostedSuccessfulEpochDays + hostedSuccessfulDay.toEpochDay()
      }
      preferences[KEY_STATE] = Json.encodeToString(
        current.copy(
          entryIdsByResultId = current.entryIdsByResultId + (resultId.toString() to entryIds),
          hostedSuccessfulEpochDays = hostedSuccessfulEpochDays,
        ),
      )
    }
  }

  override suspend fun removeResult(resultId: Long) {
    dataStore.edit { preferences ->
      val current = preferences[KEY_STATE]?.let { raw ->
        try {
          Json.decodeFromString<StoredState>(raw)
        } catch (e: Exception) {
          StoredState()
        }
      } ?: StoredState()
      preferences[KEY_STATE] = Json.encodeToString(
        current.copy(entryIdsByResultId = current.entryIdsByResultId - resultId.toString()),
      )
    }
  }

  @Serializable
  private data class StoredState(
    val entryIdsByResultId: Map<String, Set<Long>> = emptyMap(),
    val hostedSuccessfulEpochDays: Set<Long> = emptySet(),
  ) {
    fun toState() = AnalysisExecutionState(
      entryIdsByResultId = entryIdsByResultId.mapKeys { (resultId, _) -> resultId.toLong() },
      hostedSuccessfulDays = hostedSuccessfulEpochDays.mapTo(mutableSetOf(), LocalDate::ofEpochDay),
    )
  }

  private companion object {
    val KEY_STATE = stringPreferencesKey("analysis_execution_state")
  }
}
