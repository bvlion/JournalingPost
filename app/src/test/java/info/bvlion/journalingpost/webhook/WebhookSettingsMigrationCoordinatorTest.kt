package info.bvlion.journalingpost.webhook

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WebhookSettingsMigrationCoordinatorTest {
  private val legacyConfig = LegacyWebhookConfig(
    postUrl = "https://example.com/webhook",
    teamId = "T1",
    token = "TOKEN",
    channel = "C1",
    user = "U1",
  )

  @Test
  fun `未migrationならmigrateIfNeeded相当が実行される`() = runTest {
    val repository = GatedWebhookSettingsRepository()

    val job = launch { WebhookSettingsMigrationCoordinator.ensureMigrated(repository) { legacyConfig } }
    runCurrent()
    repository.completeSave()
    job.join()

    assertEquals(WebhookSettingsState.Configured(legacyConfig.toWebhookSettings()), repository.settingsValue())
    assertTrue(repository.isLegacyMigrationCompleted())
  }

  @Test
  fun `migration済みならlegacyConfigProviderを呼ばない`() = runTest {
    val repository = GatedWebhookSettingsRepository(migrationCompleted = true)
    var providerCalled = false

    WebhookSettingsMigrationCoordinator.ensureMigrated(repository) { providerCalled = true; legacyConfig }

    assertFalse(providerCalled)
  }

  @Test
  fun `並行して呼ばれても同じlegacy設定を複数回importしない`() = runTest {
    val repository = GatedWebhookSettingsRepository()
    var providerCallCount = 0
    val provider = { providerCallCount++; legacyConfig }

    val job1 = launch { WebhookSettingsMigrationCoordinator.ensureMigrated(repository, provider) }
    runCurrent()
    val job2 = launch { WebhookSettingsMigrationCoordinator.ensureMigrated(repository, provider) }
    runCurrent()

    repository.completeSave()
    advanceUntilIdle()
    job1.join()
    job2.join()

    assertEquals(1, providerCallCount)
    assertEquals(1, repository.saveCallCount)
    assertTrue(repository.isLegacyMigrationCompleted())
  }

  @Test
  fun `CancellationExceptionは握り潰さずmigration未完了のまま残す`() = runTest {
    val repository = GatedWebhookSettingsRepository()
    var thrown: Throwable? = null

    val job = launch {
      try {
        WebhookSettingsMigrationCoordinator.ensureMigrated(repository) { legacyConfig }
      } catch (e: CancellationException) {
        thrown = e
        throw e
      }
    }
    runCurrent()
    job.cancelAndJoin()

    assertTrue(thrown is CancellationException)
    assertFalse(repository.isLegacyMigrationCompleted())
  }

  @Test
  fun `isLegacyMigrationCompletedの読み取り失敗は未捕捉例外にならず未完了のまま残る`() = runTest {
    val repository = GatedWebhookSettingsRepository(throwOnIsLegacyMigrationCompletedCount = 1)

    // 例外が外へ伝播しなければこの呼び出し自体が正常終了する。
    WebhookSettingsMigrationCoordinator.ensureMigrated(repository) { legacyConfig }

    assertFalse(repository.isLegacyMigrationCompleted())
  }

  /** save()をゲートで止め、migration実行中にmutexを保持し続ける状況を再現するFake。 */
  private class GatedWebhookSettingsRepository(
    private var migrationCompleted: Boolean = false,
    private val throwOnIsLegacyMigrationCompletedCount: Int = 0,
  ) : WebhookSettingsRepository {
    private val state = MutableStateFlow<WebhookSettingsState>(WebhookSettingsState.NotConfigured)
    override val settings: Flow<WebhookSettingsState> = state
    private val saveGate = CompletableDeferred<Unit>()
    private var isLegacyMigrationCompletedCallCount = 0
    var saveCallCount = 0
      private set

    fun completeSave() {
      saveGate.complete(Unit)
    }

    fun settingsValue(): WebhookSettingsState = state.value

    override suspend fun save(settings: WebhookSettings) {
      saveCallCount++
      saveGate.await()
      state.value = WebhookSettingsState.Configured(settings)
    }

    override suspend fun clear() {
      state.value = WebhookSettingsState.NotConfigured
    }

    override suspend fun isLegacyMigrationCompleted(): Boolean {
      isLegacyMigrationCompletedCallCount++
      if (isLegacyMigrationCompletedCallCount <= throwOnIsLegacyMigrationCompletedCount) {
        throw IOException("disk error")
      }
      return migrationCompleted
    }

    override suspend fun markLegacyMigrationCompleted() {
      migrationCompleted = true
    }
  }
}
