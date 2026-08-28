package info.bvlion.journalingpost.webhook

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebhookSettingsMigratorTest {
  private val legacyConfig = LegacyWebhookConfig(
    postUrl = "https://example.com/webhook",
    teamId = "T1",
    token = "TOKEN",
    channel = "C1",
    user = "U1",
  )

  @Test
  fun `未設定かつ未migrationの場合はlegacy設定をCustom Webhookへ移行する`() = runTest {
    val repository = FakeWebhookSettingsRepository()

    WebhookSettingsMigrator.migrateIfNeeded(repository) { legacyConfig }

    assertEquals(legacyConfig.toWebhookSettings(), repository.settings.first())
    assertTrue(repository.isLegacyMigrationCompleted())
  }

  @Test
  fun `migration済みの場合は再migrationしない`() = runTest {
    val repository = FakeWebhookSettingsRepository()
    repository.markLegacyMigrationCompleted()

    var providerCalled = false
    WebhookSettingsMigrator.migrateIfNeeded(repository) { providerCalled = true; legacyConfig }

    assertFalse(providerCalled)
    assertNull(repository.settings.first())
  }

  @Test
  fun `既にruntime設定が存在する場合はlegacy設定で上書きせずmigration完了扱いにする`() = runTest {
    val repository = FakeWebhookSettingsRepository()
    val existing = WebhookSettings(url = "https://own-server.example.com", headers = emptyList(), bodyTemplate = """{"m":"{{message}}"}""")
    repository.save(existing)

    WebhookSettingsMigrator.migrateIfNeeded(repository) { legacyConfig }

    assertEquals(existing, repository.settings.first())
    assertTrue(repository.isLegacyMigrationCompleted())
  }

  @Test
  fun `legacy設定が存在しなくても安全にmigration完了扱いになる`() = runTest {
    val repository = FakeWebhookSettingsRepository()

    WebhookSettingsMigrator.migrateIfNeeded(repository) { null }

    assertNull(repository.settings.first())
    assertTrue(repository.isLegacyMigrationCompleted())
  }

  @Test
  fun `migration後に設定を削除しても再importしない`() = runTest {
    val repository = FakeWebhookSettingsRepository()
    WebhookSettingsMigrator.migrateIfNeeded(repository) { legacyConfig }
    repository.clear()

    WebhookSettingsMigrator.migrateIfNeeded(repository) { legacyConfig }

    assertNull(repository.settings.first())
  }

  private class FakeWebhookSettingsRepository : WebhookSettingsRepository {
    private val state = MutableStateFlow<WebhookSettings?>(null)
    override val settings: Flow<WebhookSettings?> = state
    private var migrationCompleted = false

    override suspend fun save(settings: WebhookSettings) {
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
