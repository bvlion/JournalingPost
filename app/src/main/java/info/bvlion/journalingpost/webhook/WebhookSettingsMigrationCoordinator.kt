package info.bvlion.journalingpost.webhook

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * legacy migrationを、呼び出し元ごとに個別実装させず、この1箇所だけに集約するためのcoordinator。
 * MainActivityの起動時処理、`WebhookAwareAnalysisIntegrationRepository`(routingの有効/無効判定前)、
 * `SettingsViewModel`(Webhook設定画面がauthoritativeな状態を確定させる前)、`WebhookJournalPoster`
 * (実際の送信直前)がそれぞれここを経由する。どれか1箇所だけがmigrationを保証している構成ではなく、
 * migration未完了のままlegacy設定を見落とし得る参照点すべてがここを通る必要がある。新しい参照点を
 * 追加する場合も、そこで初めてWebhook設定を読む前にここを経由すること。
 *
 * completedフラグをprocess内キャッシュとして持たず、毎回repository.isLegacyMigrationCompleted()
 * (DataStoreのin-memory stateを読むだけで軽量)を確認することで、migration失敗時に誤って
 * 「完了扱い」のまま次の呼び出しを早期returnさせず、再試行の余地を残す。
 * mutexで並行呼び出しをserializeし、同じlegacy設定を競合して複数回importしないようにする。
 * isLegacyMigrationCompleted()自体のDataStore読み取りがIOException等で失敗する可能性があるため
 * 全体をtry/catchで囲む。MainActivityはこれをlifecycleScope.launchから未捕捉のまま呼んでおり、
 * ここで拾わないと一時的な読み取り失敗が起動時の未捕捉例外としてアプリをクラッシュさせてしまう。
 */
object WebhookSettingsMigrationCoordinator {
  private val mutex = Mutex()

  suspend fun ensureMigrated(
    repository: WebhookSettingsRepository,
    legacyConfigProvider: () -> LegacyWebhookConfig?,
  ) {
    try {
      if (repository.isLegacyMigrationCompleted()) return
      mutex.withLock {
        if (repository.isLegacyMigrationCompleted()) return
        WebhookSettingsMigrator.migrateIfNeeded(repository, legacyConfigProvider)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // migration未完了のまま次回呼び出しで再試行する。
    }
  }
}
