package info.bvlion.journalingpost.webhook

import kotlinx.coroutines.flow.Flow

/** WebhookJournalPoster/SettingsViewModel/WebhookSettingsMigratorはこのinterfaceのみへ依存する。 */
interface WebhookSettingsRepository {
  /** 未設定または復号不能な場合はnullを返す。 */
  val settings: Flow<WebhookSettings?>

  /** 永続化に失敗した場合は例外を投げる(CancellationExceptionはそのまま再送出する)。 */
  suspend fun save(settings: WebhookSettings)

  /** 永続化に失敗した場合は例外を投げる(CancellationExceptionはそのまま再送出する)。legacy migration済み状態は維持する。 */
  suspend fun clear()

  suspend fun isLegacyMigrationCompleted(): Boolean

  suspend fun markLegacyMigrationCompleted()
}
