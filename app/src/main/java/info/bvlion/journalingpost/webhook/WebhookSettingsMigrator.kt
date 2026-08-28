package info.bvlion.journalingpost.webhook

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * 既存の自分用Webhook設定を、debug buildに限りCustom Webhookへ1度だけ移行する。
 * migrationを試行した後はlegacy値の有無に関わらず完了扱いにし、ユーザーが後で設定を削除しても
 * legacy設定を再importしない。
 */
object WebhookSettingsMigrator {
  suspend fun migrateIfNeeded(
    repository: WebhookSettingsRepository,
    legacyConfigProvider: () -> LegacyWebhookConfig?,
  ) {
    try {
      if (repository.isLegacyMigrationCompleted()) return
      when (val state = repository.settings.first()) {
        WebhookSettingsState.NotConfigured -> {
          legacyConfigProvider()?.let { repository.save(it.toWebhookSettings()) }
          repository.markLegacyMigrationCompleted()
        }
        is WebhookSettingsState.Configured -> {
          // 既にruntime設定があるためlegacy値で上書きしない。試行済みとして完了扱いにする。
          repository.markLegacyMigrationCompleted()
        }
        WebhookSettingsState.Unavailable -> {
          // 一時的に読み取れないだけの可能性があり、既存設定を誤って上書きしないためここではimportせず、
          // 完了扱いにもしない(読み取りが復旧した後の次回呼び出しで再判定する)。
        }
        WebhookSettingsState.Loading -> Unit // repository実装はLoadingを流さないため到達しない
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // migration失敗時は完了扱いにせず次回起動時に再試行する。アプリの起動自体は止めない。
    }
  }
}
