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
      if (repository.settings.first() == null) {
        legacyConfigProvider()?.let { repository.save(it.toWebhookSettings()) }
      }
      repository.markLegacyMigrationCompleted()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // migration失敗時は完了扱いにせず次回起動時に再試行する。アプリの起動自体は止めない。
    }
  }
}
