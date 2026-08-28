package info.bvlion.journalingpost.webhook

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

    assertEquals(legacyConfig.toWebhookSettings(), repository.settingsValue())
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

  /** save()をゲートで止め、migration実行中にmutexを保持し続ける状況を再現するFake。 */
  private class GatedWebhookSettingsRepository(
    private var migrationCompleted: Boolean = false,
  ) : WebhookSettingsRepository {
    private val state = MutableStateFlow<WebhookSettings?>(null)
    override val settings: Flow<WebhookSettings?> = state
    private val saveGate = CompletableDeferred<Unit>()
    var saveCallCount = 0
      private set

    fun completeSave() {
      saveGate.complete(Unit)
    }

    fun settingsValue(): WebhookSettings? = state.value

    override suspend fun save(settings: WebhookSettings) {
      saveCallCount++
      saveGate.await()
      state.value = settings
    }

    override suspend fun clear() {
      state.value = null
    }

    override suspend fun isLegacyMigrationCompleted(): Boolean = migrationCompleted

    override suspend fun markLegacyMigrationCompleted() {
      migrationCompleted = true
    }
  }
}
