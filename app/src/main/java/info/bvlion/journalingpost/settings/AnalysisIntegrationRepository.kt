package info.bvlion.journalingpost.settings

import kotlinx.coroutines.flow.Flow

/** JournalRecorder/SettingsViewModelはこのinterfaceのみへ依存する。 */
interface AnalysisIntegrationRepository {
  val analysisIntegration: Flow<AnalysisIntegration>

  /** 永続化に失敗した場合は例外を投げる(CancellationExceptionはそのまま再送出する)。 */
  suspend fun setAnalysisIntegration(integration: AnalysisIntegration)
}
