package info.bvlion.journalingpost

import info.bvlion.journalingpost.settings.RecordMode
import info.bvlion.journalingpost.settings.RecordModeRepository
import info.bvlion.journalingpost.webhook.WebhookHeader
import info.bvlion.journalingpost.webhook.WebhookSettings
import info.bvlion.journalingpost.webhook.WebhookSettingsRepository
import info.bvlion.journalingpost.webhook.WebhookSettingsState
import info.bvlion.journalingpost.webhook.WebhookSettingsValidator
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
  private val testDispatcher = StandardTestDispatcher()

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun `setRecordMode成功時はrecordModeが更新されsaveFailedはfalseのまま`() = runTest(testDispatcher) {
    val repository = FakeRecordModeRepository(RecordMode.LOCAL_AND_WEBHOOK)
    val viewModel = SettingsViewModel(repository, FakeWebhookSettingsRepository())
    val collectJob = launchCollection(viewModel)

    viewModel.setRecordMode(RecordMode.LOCAL_ONLY)
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(RecordMode.LOCAL_ONLY, viewModel.recordMode.value)
    assertFalse(viewModel.saveFailed.value)
    collectJob.cancel()
  }

  @Test
  fun `write失敗時は未処理例外にならずsaveFailedがtrueになる`() = runTest(testDispatcher) {
    val repository = FakeRecordModeRepository(RecordMode.LOCAL_AND_WEBHOOK, failNextWrites = 1)
    val viewModel = SettingsViewModel(repository, FakeWebhookSettingsRepository())
    val collectJob = launchCollection(viewModel)

    viewModel.setRecordMode(RecordMode.LOCAL_ONLY)
    testDispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.saveFailed.value)
    collectJob.cancel()
  }

  @Test
  fun `write失敗後はrecordModeが永続化前の有効なモードへ戻る`() = runTest(testDispatcher) {
    val repository = FakeRecordModeRepository(RecordMode.LOCAL_AND_WEBHOOK, failNextWrites = 1)
    val viewModel = SettingsViewModel(repository, FakeWebhookSettingsRepository())
    val collectJob = launchCollection(viewModel)

    viewModel.setRecordMode(RecordMode.LOCAL_ONLY)
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(RecordMode.LOCAL_AND_WEBHOOK, viewModel.recordMode.value)
    collectJob.cancel()
  }

  @Test
  fun `write失敗後に再度成功するとsaveFailedがfalseに戻りrecordModeも更新される`() = runTest(testDispatcher) {
    val repository = FakeRecordModeRepository(RecordMode.LOCAL_AND_WEBHOOK, failNextWrites = 1)
    val viewModel = SettingsViewModel(repository, FakeWebhookSettingsRepository())
    val collectJob = launchCollection(viewModel)
    viewModel.setRecordMode(RecordMode.LOCAL_ONLY)
    testDispatcher.scheduler.advanceUntilIdle()
    assertTrue(viewModel.saveFailed.value)

    viewModel.setRecordMode(RecordMode.LOCAL_ONLY)
    testDispatcher.scheduler.advanceUntilIdle()

    assertFalse(viewModel.saveFailed.value)
    assertEquals(RecordMode.LOCAL_ONLY, viewModel.recordMode.value)
    collectJob.cancel()
  }

  @Test
  fun `古いwriteが後から失敗しても新しいwriteの成功が優先されsaveFailedはfalseのまま`() = runTest(testDispatcher) {
    val repository = ControllableRecordModeRepository(RecordMode.LOCAL_AND_WEBHOOK)
    val viewModel = SettingsViewModel(repository, FakeWebhookSettingsRepository())
    val collectJob = launchCollection(viewModel)

    viewModel.setRecordMode(RecordMode.LOCAL_ONLY)
    viewModel.setRecordMode(RecordMode.LOCAL_AND_WEBHOOK)
    testDispatcher.scheduler.runCurrent()

    repository.complete(1)
    testDispatcher.scheduler.advanceUntilIdle()
    repository.fail(0, IOException("disk error"))
    testDispatcher.scheduler.advanceUntilIdle()

    assertFalse(viewModel.saveFailed.value)
    collectJob.cancel()
  }

  @Test
  fun `古いwriteが後から成功しても新しいwriteの失敗が優先されsaveFailedはtrueのまま`() = runTest(testDispatcher) {
    val repository = ControllableRecordModeRepository(RecordMode.LOCAL_AND_WEBHOOK)
    val viewModel = SettingsViewModel(repository, FakeWebhookSettingsRepository())
    val collectJob = launchCollection(viewModel)

    viewModel.setRecordMode(RecordMode.LOCAL_ONLY)
    viewModel.setRecordMode(RecordMode.LOCAL_AND_WEBHOOK)
    testDispatcher.scheduler.runCurrent()

    repository.fail(1, IOException("disk error"))
    testDispatcher.scheduler.advanceUntilIdle()
    repository.complete(0)
    testDispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.saveFailed.value)
    collectJob.cancel()
  }

  @Test
  fun `保存成功時にisWebhookConfiguredがtrueになりvalidation errorはなくフォームは再び隠れる`() = runTest(testDispatcher) {
    val webhookRepository = FakeWebhookSettingsRepository()
    val viewModel = SettingsViewModel(FakeRecordModeRepository(RecordMode.LOCAL_AND_WEBHOOK), webhookRepository)
    val collectJob = launchWebhookCollection(viewModel)

    viewModel.updateWebhookUrl("https://example.com/webhook")
    viewModel.updateWebhookBodyTemplate("""{"text": "{{message}}"}""")
    viewModel.saveWebhookSettings()
    testDispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.isWebhookConfigured.value)
    assertTrue(viewModel.webhookValidationErrors.value.isEmpty())
    assertFalse(viewModel.webhookSaveFailed.value)
    assertFalse(viewModel.isWebhookFormVisible.value)
    assertEquals(WebhookFormState(), viewModel.webhookFormState.value)
    collectJob.cancel()
  }

  @Test
  fun `validation失敗時にはrepositoryへ保存されない`() = runTest(testDispatcher) {
    val webhookRepository = FakeWebhookSettingsRepository()
    val viewModel = SettingsViewModel(FakeRecordModeRepository(RecordMode.LOCAL_AND_WEBHOOK), webhookRepository)
    val collectJob = launchWebhookCollection(viewModel)

    viewModel.updateWebhookUrl("not a url")
    viewModel.updateWebhookBodyTemplate("""{"text": "{{message}}"}""")
    viewModel.saveWebhookSettings()
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(0, webhookRepository.saveCallCount)
    assertEquals(listOf(WebhookSettingsValidator.ValidationError.INVALID_URL), viewModel.webhookValidationErrors.value)
    assertFalse(viewModel.isWebhookConfigured.value)
    collectJob.cancel()
  }

  @Test
  fun `webhook設定のrepository保存失敗時はwebhookSaveFailedがtrueになる`() = runTest(testDispatcher) {
    val webhookRepository = FakeWebhookSettingsRepository(failNextSaves = 1)
    val viewModel = SettingsViewModel(FakeRecordModeRepository(RecordMode.LOCAL_AND_WEBHOOK), webhookRepository)
    val collectJob = launchWebhookCollection(viewModel)

    viewModel.updateWebhookUrl("https://example.com/webhook")
    viewModel.updateWebhookBodyTemplate("""{"text": "{{message}}"}""")
    viewModel.saveWebhookSettings()
    testDispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.webhookSaveFailed.value)
    assertFalse(viewModel.isWebhookConfigured.value)
    collectJob.cancel()
  }

  @Test
  fun `削除するとisWebhookConfiguredがfalseになりフォームが空になる`() = runTest(testDispatcher) {
    val existing = WebhookSettings(
      url = "https://example.com/webhook",
      headers = listOf(WebhookHeader("Authorization", "Bearer xxxxx")),
      bodyTemplate = """{"text": "{{message}}"}""",
    )
    val webhookRepository = FakeWebhookSettingsRepository(initial = existing)
    val viewModel = SettingsViewModel(FakeRecordModeRepository(RecordMode.LOCAL_AND_WEBHOOK), webhookRepository)
    val collectJob = launchWebhookCollection(viewModel)
    testDispatcher.scheduler.advanceUntilIdle()
    assertTrue(viewModel.isWebhookConfigured.value)

    viewModel.deleteWebhookSettings()
    testDispatcher.scheduler.advanceUntilIdle()

    assertFalse(viewModel.isWebhookConfigured.value)
    assertEquals(WebhookFormState(), viewModel.webhookFormState.value)
    assertTrue(viewModel.isWebhookFormVisible.value)
    collectJob.cancel()
  }

  @Test
  fun `保存済み設定がある初期状態ではフォームを展開せずisWebhookFormVisibleはfalse`() = runTest(testDispatcher) {
    val existing = WebhookSettings(
      url = "https://example.com/webhook",
      headers = listOf(WebhookHeader("Authorization", "Bearer xxxxx")),
      bodyTemplate = """{"text": "{{message}}"}""",
    )
    val webhookRepository = FakeWebhookSettingsRepository(initial = existing)
    val viewModel = SettingsViewModel(FakeRecordModeRepository(RecordMode.LOCAL_AND_WEBHOOK), webhookRepository)
    val collectJob = launchWebhookCollection(viewModel)
    testDispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.isWebhookConfigured.value)
    assertFalse(viewModel.isWebhookFormVisible.value)
    assertEquals(WebhookFormState(), viewModel.webhookFormState.value)
    collectJob.cancel()
  }

  @Test
  fun `未設定なら初期状態からisWebhookFormVisibleはtrueで新規入力できる`() = runTest(testDispatcher) {
    val webhookRepository = FakeWebhookSettingsRepository(initial = null)
    val viewModel = SettingsViewModel(FakeRecordModeRepository(RecordMode.LOCAL_AND_WEBHOOK), webhookRepository)
    val collectJob = launchWebhookCollection(viewModel)
    testDispatcher.scheduler.advanceUntilIdle()

    assertFalse(viewModel.isWebhookConfigured.value)
    assertTrue(viewModel.isWebhookFormVisible.value)
    collectJob.cancel()
  }

  @Test
  fun `revealWebhookFormを呼ぶと保存済み設定がフォームへ反映されisWebhookFormVisibleがtrueになる`() = runTest(testDispatcher) {
    val existing = WebhookSettings(
      url = "https://example.com/webhook",
      headers = listOf(WebhookHeader("Authorization", "Bearer xxxxx")),
      bodyTemplate = """{"text": "{{message}}"}""",
    )
    val webhookRepository = FakeWebhookSettingsRepository(initial = existing)
    val viewModel = SettingsViewModel(FakeRecordModeRepository(RecordMode.LOCAL_AND_WEBHOOK), webhookRepository)
    val collectJob = launchWebhookCollection(viewModel)
    testDispatcher.scheduler.advanceUntilIdle()

    viewModel.revealWebhookForm()
    testDispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.isWebhookFormVisible.value)
    assertEquals(existing.url, viewModel.webhookFormState.value.url)
    assertEquals(existing.headers, viewModel.webhookFormState.value.headers)
    assertEquals(existing.bodyTemplate, viewModel.webhookFormState.value.bodyTemplate)
    collectJob.cancel()
  }

  @Test
  fun `初回読み込み完了前はisWebhookFormVisibleがfalseで新規フォームを編集可能にしない`() = runTest(testDispatcher) {
    val webhookRepository = ControllableWebhookSettingsRepository(initial = WebhookSettingsState.Loading)
    val viewModel = SettingsViewModel(FakeRecordModeRepository(RecordMode.LOCAL_AND_WEBHOOK), webhookRepository)
    val collectJob = launchWebhookCollection(viewModel)
    testDispatcher.scheduler.runCurrent()

    assertEquals(WebhookSettingsState.Loading, viewModel.webhookSettingsState.value)
    assertFalse(viewModel.isWebhookFormVisible.value)
    collectJob.cancel()
  }

  @Test
  fun `Loadingから未設定へ遷移すると新規フォームが表示される`() = runTest(testDispatcher) {
    val webhookRepository = ControllableWebhookSettingsRepository(initial = WebhookSettingsState.Loading)
    val viewModel = SettingsViewModel(FakeRecordModeRepository(RecordMode.LOCAL_AND_WEBHOOK), webhookRepository)
    val collectJob = launchWebhookCollection(viewModel)
    testDispatcher.scheduler.runCurrent()
    assertFalse(viewModel.isWebhookFormVisible.value)

    webhookRepository.emit(WebhookSettingsState.NotConfigured)
    testDispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.isWebhookFormVisible.value)
    collectJob.cancel()
  }

  @Test
  fun `一時的な読み込み不能から既存設定へ復旧すると空フォームを残さず設定済み状態になる`() = runTest(testDispatcher) {
    val existing = WebhookSettings(
      url = "https://example.com/webhook",
      headers = emptyList(),
      bodyTemplate = """{"text": "{{message}}"}""",
    )
    val webhookRepository = ControllableWebhookSettingsRepository(initial = WebhookSettingsState.Unavailable)
    val viewModel = SettingsViewModel(FakeRecordModeRepository(RecordMode.LOCAL_AND_WEBHOOK), webhookRepository)
    val collectJob = launchWebhookCollection(viewModel)
    testDispatcher.scheduler.runCurrent()
    assertFalse(viewModel.isWebhookFormVisible.value)

    webhookRepository.emit(WebhookSettingsState.Configured(existing))
    testDispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.isWebhookConfigured.value)
    assertFalse(viewModel.isWebhookFormVisible.value)
    assertEquals(WebhookFormState(), viewModel.webhookFormState.value)
    collectJob.cancel()
  }

  @Test
  fun `migration前のLoadingからmigration済み設定へ遷移すると空フォームを残さず設定済み状態になる`() = runTest(testDispatcher) {
    val migrated = WebhookSettings(
      url = "https://legacy.example.com/webhook",
      headers = emptyList(),
      bodyTemplate = """{"text": "{{message}}"}""",
    )
    val webhookRepository = ControllableWebhookSettingsRepository(initial = WebhookSettingsState.Loading)
    val viewModel = SettingsViewModel(FakeRecordModeRepository(RecordMode.LOCAL_AND_WEBHOOK), webhookRepository)
    val collectJob = launchWebhookCollection(viewModel)
    testDispatcher.scheduler.runCurrent()

    webhookRepository.emit(WebhookSettingsState.Configured(migrated))
    testDispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.isWebhookConfigured.value)
    assertFalse(viewModel.isWebhookFormVisible.value)
    assertEquals(WebhookFormState(), viewModel.webhookFormState.value)
    collectJob.cancel()
  }

  @Test
  fun `保存中にフォームを編集した場合、古いsaveの完了でフォーム内容が消えない`() = runTest(testDispatcher) {
    val webhookRepository = GatedSaveWebhookSettingsRepository()
    val viewModel = SettingsViewModel(FakeRecordModeRepository(RecordMode.LOCAL_AND_WEBHOOK), webhookRepository)
    val collectJob = launchWebhookCollection(viewModel)
    viewModel.updateWebhookUrl("https://a.example.com/webhook")
    viewModel.updateWebhookBodyTemplate("""{"text": "{{message}}"}""")

    viewModel.saveWebhookSettings()
    testDispatcher.scheduler.runCurrent() // saveはgateで止まるため、write進行中の状態まで進む
    viewModel.updateWebhookUrl("https://b.example.com/webhook")
    webhookRepository.completeSave()
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals("https://a.example.com/webhook", webhookRepository.savedSettings?.url)
    assertEquals("https://b.example.com/webhook", viewModel.webhookFormState.value.url)
    assertEquals("""{"text": "{{message}}"}""", viewModel.webhookFormState.value.bodyTemplate)
    assertTrue(viewModel.isWebhookFormVisible.value)
    collectJob.cancel()
  }

  @Test
  fun `保存中にフォームを編集した場合、古いsaveの失敗でもフォーム内容が消えない`() = runTest(testDispatcher) {
    val webhookRepository = GatedSaveWebhookSettingsRepository()
    val viewModel = SettingsViewModel(FakeRecordModeRepository(RecordMode.LOCAL_AND_WEBHOOK), webhookRepository)
    val collectJob = launchWebhookCollection(viewModel)
    viewModel.updateWebhookUrl("https://a.example.com/webhook")
    viewModel.updateWebhookBodyTemplate("""{"text": "{{message}}"}""")

    viewModel.saveWebhookSettings()
    testDispatcher.scheduler.runCurrent()
    viewModel.updateWebhookUrl("https://b.example.com/webhook")
    webhookRepository.failSave(IOException("disk error"))
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals("https://b.example.com/webhook", viewModel.webhookFormState.value.url)
    assertEquals("""{"text": "{{message}}"}""", viewModel.webhookFormState.value.bodyTemplate)
    assertTrue(viewModel.isWebhookFormVisible.value)
    collectJob.cancel()
  }

  @Test
  fun `保存中に編集したフォーム内容は古いsave完了後に改めて保存できる`() = runTest(testDispatcher) {
    val webhookRepository = GatedSaveWebhookSettingsRepository()
    val viewModel = SettingsViewModel(FakeRecordModeRepository(RecordMode.LOCAL_AND_WEBHOOK), webhookRepository)
    val collectJob = launchWebhookCollection(viewModel)
    viewModel.updateWebhookUrl("https://a.example.com/webhook")
    viewModel.updateWebhookBodyTemplate("""{"text": "{{message}}"}""")
    viewModel.saveWebhookSettings()
    testDispatcher.scheduler.runCurrent()
    viewModel.updateWebhookUrl("https://b.example.com/webhook")
    webhookRepository.completeSave()
    testDispatcher.scheduler.advanceUntilIdle()

    viewModel.saveWebhookSettings()
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals("https://b.example.com/webhook", webhookRepository.savedSettings?.url)
    assertFalse(viewModel.isWebhookFormVisible.value)
    assertEquals(WebhookFormState(), viewModel.webhookFormState.value)
    collectJob.cancel()
  }

  private fun launchCollection(viewModel: SettingsViewModel) =
    CoroutineScope(testDispatcher).launch { viewModel.recordMode.collect {} }

  private fun launchWebhookCollection(viewModel: SettingsViewModel) =
    CoroutineScope(testDispatcher).launch {
      launch { viewModel.isWebhookConfigured.collect {} }
      launch { viewModel.isWebhookFormVisible.collect {} }
    }

  /** setRecordMode()の完了/失敗を呼び出し順と切り離して制御し、write完了順の入れ替わりを再現するFake。 */
  private class ControllableRecordModeRepository(initial: RecordMode) : RecordModeRepository {
    private val state = MutableStateFlow(initial)
    override val recordMode: Flow<RecordMode> = state
    private val gates = mutableListOf<CompletableDeferred<Throwable?>>()

    fun complete(index: Int) {
      gates[index].complete(null)
    }

    fun fail(index: Int, error: Throwable) {
      gates[index].complete(error)
    }

    override suspend fun setRecordMode(mode: RecordMode) {
      val gate = CompletableDeferred<Throwable?>()
      gates += gate
      val error = gate.await()
      if (error != null) throw error
      state.value = mode
    }
  }

  private class FakeRecordModeRepository(
    initial: RecordMode,
    private var failNextWrites: Int = 0,
  ) : RecordModeRepository {
    private val state = MutableStateFlow(initial)
    override val recordMode: Flow<RecordMode> = state

    override suspend fun setRecordMode(mode: RecordMode) {
      if (failNextWrites > 0) {
        failNextWrites--
        throw IOException("disk error")
      }
      state.value = mode
    }
  }

  private class FakeWebhookSettingsRepository(
    initial: WebhookSettings? = null,
    private var failNextSaves: Int = 0,
  ) : WebhookSettingsRepository {
    private val state = MutableStateFlow(initial.toState())
    override val settings: Flow<WebhookSettingsState> = state
    var saveCallCount = 0
      private set

    override suspend fun save(settings: WebhookSettings) {
      saveCallCount++
      if (failNextSaves > 0) {
        failNextSaves--
        throw IOException("disk error")
      }
      state.value = WebhookSettingsState.Configured(settings)
    }

    override suspend fun clear() {
      state.value = WebhookSettingsState.NotConfigured
    }

    override suspend fun isLegacyMigrationCompleted(): Boolean = true

    override suspend fun markLegacyMigrationCompleted() = Unit

    private fun WebhookSettings?.toState(): WebhookSettingsState =
      this?.let { WebhookSettingsState.Configured(it) } ?: WebhookSettingsState.NotConfigured
  }

  /** save()の完了/失敗をテストから制御し、DataStore write進行中のユーザー編集を再現するFake。 */
  private class GatedSaveWebhookSettingsRepository : WebhookSettingsRepository {
    private val state = MutableStateFlow<WebhookSettingsState>(WebhookSettingsState.NotConfigured)
    override val settings: Flow<WebhookSettingsState> = state
    private val gate = CompletableDeferred<Throwable?>()
    var savedSettings: WebhookSettings? = null
      private set

    fun completeSave() {
      gate.complete(null)
    }

    fun failSave(error: Throwable) {
      gate.complete(error)
    }

    override suspend fun save(settings: WebhookSettings) {
      val error = gate.await()
      if (error != null) throw error
      savedSettings = settings
      state.value = WebhookSettingsState.Configured(settings)
    }

    override suspend fun clear() {
      state.value = WebhookSettingsState.NotConfigured
    }

    override suspend fun isLegacyMigrationCompleted(): Boolean = true

    override suspend fun markLegacyMigrationCompleted() = Unit
  }

  /** テストからstate遷移(Loading→Unavailable/NotConfigured/Configured)を直接制御するFake。 */
  private class ControllableWebhookSettingsRepository(
    initial: WebhookSettingsState,
  ) : WebhookSettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings: Flow<WebhookSettingsState> = state

    fun emit(newState: WebhookSettingsState) {
      state.value = newState
    }

    override suspend fun save(settings: WebhookSettings) {
      state.value = WebhookSettingsState.Configured(settings)
    }

    override suspend fun clear() {
      state.value = WebhookSettingsState.NotConfigured
    }

    override suspend fun isLegacyMigrationCompleted(): Boolean = true

    override suspend fun markLegacyMigrationCompleted() = Unit
  }
}
