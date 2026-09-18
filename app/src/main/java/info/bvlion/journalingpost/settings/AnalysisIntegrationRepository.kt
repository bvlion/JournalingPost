package info.bvlion.journalingpost.settings

import kotlinx.coroutines.flow.Flow

/** 解析先の設定と解析実行は、このinterfaceを介して現在の選択を共有する。 */
interface AnalysisIntegrationRepository {
  val analysisIntegration: Flow<AnalysisIntegration>

  /** 永続化に失敗した場合は例外を投げる(CancellationExceptionはそのまま再送出する)。 */
  suspend fun setAnalysisIntegration(integration: AnalysisIntegration)
}
