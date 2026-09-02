package info.bvlion.journalingpost.settings

import kotlinx.coroutines.flow.Flow

/** 設定画面と自動解析のscheduler/Workerはこのinterfaceのみへ依存する。 */
interface AutoAnalysisSettingsRepository {
  val autoAnalysisSettings: Flow<AutoAnalysisSettings>

  /** 永続化に失敗した場合は例外を投げる(CancellationExceptionはそのまま再送出する)。 */
  suspend fun setAutoAnalysisSettings(settings: AutoAnalysisSettings)
}
