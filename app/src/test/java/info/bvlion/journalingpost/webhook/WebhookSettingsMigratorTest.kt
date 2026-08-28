package info.bvlion.journalingpost.webhook

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    assertEquals(WebhookSettingsState.Configured(legacyConfig.toWebhookSettings()), repository.settings.first())
    assertTrue(repository.isLegacyMigrationCompleted())
  }

  @Test
  fun `migration済みの場合は再migrationしない`() = runTest {
    val repository = FakeWebhookSettingsRepository()
    repository.markLegacyMigrationCompleted()

    var providerCalled = false
    WebhookSettingsMigrator.migrateIfNeeded(repository) { providerCalled = true; legacyConfig }

    assertFalse(providerCalled)
    assertEquals(WebhookSettingsState.NotConfigured, repository.settings.first())
  }

  @Test
  fun `既にruntime設定が存在する場合はlegacy設定で上書きせずmigration完了扱いにする`() = runTest {
    val repository = FakeWebhookSettingsRepository()
    val existing = WebhookSettings(url = "https://own-server.example.com", headers = emptyList(), bodyTemplate = """{"m":"{{message}}"}""")
    repository.save(existing)

    WebhookSettingsMigrator.migrateIfNeeded(repository) { legacyConfig }

    assertEquals(WebhookSettingsState.Configured(existing), repository.settings.first())
    assertTrue(repository.isLegacyMigrationCompleted())
  }

  @Test
  fun `legacy設定が存在しなくても安全にmigration完了扱いになる`() = runTest {
    val repository = FakeWebhookSettingsRepository()

    WebhookSettingsMigrator.migrateIfNeeded(repository) { null }

    assertEquals(WebhookSettingsState.NotConfigured, repository.settings.first())
    assertTrue(repository.isLegacyMigrationCompleted())
  }

  @Test
  fun `migration後に設定を削除しても再importしない`() = runTest {
    val repository = FakeWebhookSettingsRepository()
    WebhookSettingsMigrator.migrateIfNeeded(repository) { legacyConfig }
    repository.clear()

    WebhookSettingsMigrator.migrateIfNeeded(repository) { legacyConfig }

    assertEquals(WebhookSettingsState.NotConfigured, repository.settings.first())
  }

  @Test
  fun `読み取りが一時的に不能な場合はimportせず完了扱いにもしない`() = runTest {
    val repository = FakeWebhookSettingsRepository(initial = WebhookSettingsState.Unavailable)
    var providerCalled = false

    WebhookSettingsMigrator.migrateIfNeeded(repository) { providerCalled = true; legacyConfig }

    assertFalse(providerCalled)
    assertFalse(repository.isLegacyMigrationCompleted())
  }

  private class FakeWebhookSettingsRepository(
    initial: WebhookSettingsState = WebhookSettingsState.NotConfigured,
  ) : WebhookSettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings: Flow<WebhookSettingsState> = state
    private var migrationCompleted = false

    override suspend fun save(settings: WebhookSettings) {
      state.value = WebhookSettingsState.Configured(settings)
    }

    override suspend fun clear() {
      state.value = WebhookSettingsState.NotConfigured
    }

    override suspend fun isLegacyMigrationCompleted(): Boolean = migrationCompleted

    override suspend fun markLegacyMigrationCompleted() {
      migrationCompleted = true
    }
  }
}
