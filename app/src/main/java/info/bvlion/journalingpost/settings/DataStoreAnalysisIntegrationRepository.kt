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
   * write完了前でも選択を記録処理へ即時反映するための値。generationは、複数回の
   * setAnalysisIntegration()が重なった際、古い呼び出しのwrite完了/失敗/キャンセルがより新しい選択を
   * 誤って上書き・巻き戻ししないよう、pendingIntegrationをクリアする際に確認する世代番号。
   */
  private val pendingIntegration = MutableStateFlow<PendingIntegration?>(null)
  private val generation = AtomicInteger(0)

  private val persistedAnalysisIntegration: Flow<AnalysisIntegration> = dataStore.data
    .map { preferences -> preferences.toAnalysisIntegrationOrDefault() }
    .retryWhen { cause, _ ->
      // 読み取れない場合は安全側(送信しないNONE)へ一旦フォールバックしたうえで再購読を試み続ける。
      // IOException以外は再送出する(リトライしない)。
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

  /**
   * key/fileの名前と、#31以前に保存された値(LOCAL_AND_WEBHOOK/LOCAL_ONLY)の読み取りは、
   * 既存端末の選択を引き継ぐためそのまま受け付ける。名称変更のためだけに保存済みの選択を
   * 既定値へ戻さない。既定値も#31以前と同じ意味(Custom Webhookへ送信する)を維持している。
   */
  private fun Preferences.toAnalysisIntegrationOrDefault(): AnalysisIntegration =
    when (val stored = this[KEY]) {
      null -> AnalysisIntegration.CUSTOM_WEBHOOK
      LEGACY_LOCAL_ONLY -> AnalysisIntegration.NONE
      LEGACY_LOCAL_AND_WEBHOOK -> AnalysisIntegration.CUSTOM_WEBHOOK
      else -> AnalysisIntegration.entries.firstOrNull { it.name == stored } ?: AnalysisIntegration.CUSTOM_WEBHOOK
    }

  private data class PendingIntegration(val integration: AnalysisIntegration, val generation: Int)

  private companion object {
    val KEY = stringPreferencesKey("record_mode")
    const val LEGACY_LOCAL_ONLY = "LOCAL_ONLY"
    const val LEGACY_LOCAL_AND_WEBHOOK = "LOCAL_AND_WEBHOOK"
    const val RETRY_DELAY_MILLIS = 1_000L
  }
}
