package info.bvlion.journalingpost.webhook

import kotlinx.coroutines.flow.Flow

/**
 * Loadingは購読直後、repositoryからの最初の読み取り結果がまだ届いていない状態を表す
 * (repository実装はLoadingを流さず、ViewModel側のstateIn初期値としてのみ使う想定)。
 * UnavailableはDataStore読み取りが一時的に失敗している状態で、NotConfiguredの「本当に未設定」とは
 * 区別する。両者を区別しないと、SettingsViewModelがWebhook未設定と誤認して、secretを含み得る
 * 保存済み設定を上書きしかねない新規設定フォームを確定表示してしまう。
 */
sealed interface WebhookSettingsState {
  data object Loading : WebhookSettingsState
  data object Unavailable : WebhookSettingsState
  data object NotConfigured : WebhookSettingsState
  data class Configured(val settings: WebhookSettings) : WebhookSettingsState
}

/** WebhookJournalPoster/SettingsViewModel/WebhookSettingsMigratorはこのinterfaceのみへ依存する。 */
interface WebhookSettingsRepository {
  val settings: Flow<WebhookSettingsState>

  /** 永続化に失敗した場合は例外を投げる(CancellationExceptionはそのまま再送出する)。 */
  suspend fun save(settings: WebhookSettings)

  /** 永続化に失敗した場合は例外を投げる(CancellationExceptionはそのまま再送出する)。legacy migration済み状態は維持する。 */
  suspend fun clear()

  suspend fun isLegacyMigrationCompleted(): Boolean

  suspend fun markLegacyMigrationCompleted()
}
