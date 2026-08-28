package info.bvlion.journalingpost.webhook

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * legacy migrationの呼び出し元(MainActivity、Widget経由のMoodEntryActivity、WebhookJournalPoster等)に
 * 依存させず、Webhook送信が設定snapshotを取得する前に必ず完了させるためのcoordinator。
 * MainActivityとWidgetそれぞれにmigration呼び出しを複製せず、WebhookJournalPoster.post()から
 * ここを経由することで、どちらの入口から記録してもmigration完了を保証する。
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
