package info.bvlion.journalingpost.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen

internal class DataStoreAnalysisIntegrationRepository(
  private val dataStore: DataStore<Preferences>,
) : AnalysisIntegrationRepository {
  /**
   * 「使用しない」の選択をDataStoreのwrite完了前から記録処理へ反映し、外部送信停止の意思を待たせない。
   * generationは古いwriteの完了・失敗が新しい選択のpendingを消さないために使う。
   */
  private val pendingIntegration = MutableStateFlow<PendingIntegration?>(null)
  private val generation = AtomicInteger(0)

  private val persistedAnalysisIntegration: Flow<AnalysisIntegration> = dataStore.data
    .map { preferences -> preferences.toAnalysisIntegrationOrDefault() }
    .retryWhen { cause, _ ->
      if (cause !is IOException) return@retryWhen false
      emit(AnalysisIntegration.NONE)
      delay(RETRY_DELAY_MILLIS)
      true
    }

  override val analysisIntegration: Flow<AnalysisIntegration> =
    combine(persistedAnalysisIntegration, pendingIntegration) { persisted, pending ->
      pending?.integration ?: persisted
    }

  override suspend fun setAnalysisIntegration(integration: AnalysisIntegration) {
    val pending = PendingIntegration(integration, generation.incrementAndGet())
    pendingIntegration.value = pending
    try {
      dataStore.edit { it[KEY] = integration.name }
    } finally {
      pendingIntegration.compareAndSet(pending, null)
    }
  }

  private fun Preferences.toAnalysisIntegrationOrDefault(): AnalysisIntegration {
    val stored = this[KEY] ?: return AnalysisIntegration.NONE
    return AnalysisIntegration.entries.firstOrNull { it.name == stored } ?: AnalysisIntegration.NONE
  }

  private data class PendingIntegration(val integration: AnalysisIntegration, val generation: Int)

  private companion object {
    val KEY = stringPreferencesKey("analysis_integration")
    const val RETRY_DELAY_MILLIS = 1_000L
  }
}
