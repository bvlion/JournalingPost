package info.bvlion.journalingpost

import info.bvlion.journalingpost.settings.AnalysisIntegration
import info.bvlion.journalingpost.settings.AnalysisIntegrationRepository
import info.bvlion.journalingpost.webhook.WebhookHeader
import info.bvlion.journalingpost.webhook.WebhookSettings
import info.bvlion.journalingpost.webhook.WebhookSettingsOverview
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
  fun `setAnalysisIntegration成功時は選択が更新されintegrationSaveFailedはfalseのまま`() = runTest(testDispatcher) {
    val repository = FakeAnalysisIntegrationRepository(AnalysisIntegration.CUSTOM_WEBHOOK)
    val viewModel = SettingsViewModel(repository, FakeWebhookSettingsRepository())
    val collectJob = launchCollection(viewModel)

    viewModel.setAnalysisIntegration(AnalysisIntegration.NONE)
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(AnalysisIntegration.NONE, viewModel.analysisIntegration.value)
    assertFalse(viewModel.integrationSaveFailed.value)
    collectJob.cancel()
  }

  @Test
  fun `write失敗時は未処理例外にならずintegrationSaveFailedがtrueになる`() = runTest(testDispatcher) {
    val repository = FakeAnalysisIntegrationRepository(AnalysisIntegration.CUSTOM_WEBHOOK, failNextWrites = 1)
    val viewModel = SettingsViewModel(repository, FakeWebhookSettingsRepository())
    val collectJob = launchCollection(viewModel)

    viewModel.setAnalysisIntegration(AnalysisIntegration.NONE)
    testDispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.integrationSaveFailed.value)
    collectJob.cancel()
  }

  @Test
  fun `write失敗後は選択が永続化前の有効な値へ戻る`() = runTest(testDispatcher) {
    val repository = FakeAnalysisIntegrationRepository(AnalysisIntegration.CUSTOM_WEBHOOK, failNextWrites = 1)
    val viewModel = SettingsViewModel(repository, FakeWebhookSettingsRepository())
    val collectJob = launchCollection(viewModel)

    viewModel.setAnalysisIntegration(AnalysisIntegration.NONE)
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(AnalysisIntegration.CUSTOM_WEBHOOK, viewModel.analysisIntegration.value)
    collectJob.cancel()
  }

  @Test
  fun `write失敗後に再度成功するとintegrationSaveFailedがfalseに戻り選択も更新される`() = runTest(testDispatcher) {
    val repository = FakeAnalysisIntegrationRepository(AnalysisIntegration.CUSTOM_WEBHOOK, failNextWrites = 1)
    val viewModel = SettingsViewModel(repository, FakeWebhookSettingsRepository())
    val collectJob = launchCollection(viewModel)
    viewModel.setAnalysisIntegration(AnalysisIntegration.NONE)
    testDispatcher.scheduler.advanceUntilIdle()
    assertTrue(viewModel.integrationSaveFailed.value)

    viewModel.setAnalysisIntegration(AnalysisIntegration.NONE)
    testDispatcher.scheduler.advanceUntilIdle()

    assertFalse(viewModel.integrationSaveFailed.value)
    assertEquals(AnalysisIntegration.NONE, viewModel.analysisIntegration.value)
    collectJob.cancel()
  }

  @Test
  fun `古いwriteが後から失敗しても新しいwriteの成功が優先されintegrationSaveFailedはfalseのまま`() = runTest(testDispatcher) {
    val repository = ControllableAnalysisIntegrationRepository(AnalysisIntegration.CUSTOM_WEBHOOK)
    val viewModel = SettingsViewModel(repository, FakeWebhookSettingsRepository())
    val collectJob = launchCollection(viewModel)

    viewModel.setAnalysisIntegration(AnalysisIntegration.NONE)
    viewModel.setAnalysisIntegration(AnalysisIntegration.CUSTOM_WEBHOOK)
    testDispatcher.scheduler.runCurrent()

    repository.complete(1)
    testDispatcher.scheduler.advanceUntilIdle()
    repository.fail(0, IOException("disk error"))
    testDispatcher.scheduler.advanceUntilIdle()

    assertFalse(viewModel.integrationSaveFailed.value)
    collectJob.cancel()
  }

  @Test
  fun `古いwriteが後から成功しても新しいwriteの失敗が優先されintegrationSaveFailedはtrueのまま`() = runTest(testDispatcher) {
    val repository = ControllableAnalysisIntegrationRepository(AnalysisIntegration.CUSTOM_WEBHOOK)
    val viewModel = SettingsViewModel(repository, FakeWebhookSettingsRepository())
    val collectJob = launchCollection(viewModel)

    viewModel.setAnalysisIntegration(AnalysisIntegration.NONE)
    viewModel.setAnalysisIntegration(AnalysisIntegration.CUSTOM_WEBHOOK)
    testDispatcher.scheduler.runCurrent()

    repository.fail(1, IOException("disk error"))
    testDispatcher.scheduler.advanceUntilIdle()
    repository.complete(0)
    testDispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.integrationSaveFailed.value)
    collectJob.cancel()
  }

  @Test
  fun `保存成功時にisWebhookConfiguredがtrueになりvalidation errorはなく編集は終了する`() = runTest(testDispatcher) {
    val webhookRepository = FakeWebhookSettingsRepository()
    val viewModel = createViewModel(AnalysisIntegration.CUSTOM_WEBHOOK, webhookRepository)
    val collectJob = launchWebhookCollection(viewModel)

    viewModel.updateWebhookUrl("https://example.com/webhook")
    viewModel.updateWebhookBodyTemplate("""{"text": "{{message}}"}""")
    viewModel.saveWebhookSettings()
    testDispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.isWebhookConfigured.value)
    assertTrue(viewModel.webhookValidationErrors.value.isEmpty())
    assertNull(viewModel.webhookOperationFailure.value)
    assertFalse(viewModel.isWebhookEditing.value)
    assertEquals(WebhookFormState(), viewModel.webhookFormState.value)
    collectJob.cancel()
  }

  @Test
  fun `validation失敗時にはrepositoryへ保存されない`() = runTest(testDispatcher) {
    val webhookRepository = FakeWebhookSettingsRepository()
    val viewModel = createViewModel(AnalysisIntegration.CUSTOM_WEBHOOK, webhookRepository)
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
  fun `webhook設定のrepository保存失敗時はwebhookOperationFailureがSAVEになる`() = runTest(testDispatcher) {
    val webhookRepository = FakeWebhookSettingsRepository(failNextSaves = 1)
    val viewModel = createViewModel(AnalysisIntegration.CUSTOM_WEBHOOK, webhookRepository)
    val collectJob = launchWebhookCollection(viewModel)

    viewModel.updateWebhookUrl("https://example.com/webhook")
    viewModel.updateWebhookBodyTemplate("""{"text": "{{message}}"}""")
    viewModel.saveWebhookSettings()
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(WebhookOperationFailure.SAVE, viewModel.webhookOperationFailure.value)
    assertFalse(viewModel.isWebhookConfigured.value)
    collectJob.cancel()
  }

  @Test
  fun `削除すると保存済み設定が消え解析・連携も使用しないへ戻る`() = runTest(testDispatcher) {
    val webhookRepository = FakeWebhookSettingsRepository(initial = configuredSettings())
    val integrationRepository = FakeAnalysisIntegrationRepository(AnalysisIntegration.CUSTOM_WEBHOOK)
    val viewModel = SettingsViewModel(integrationRepository, webhookRepository)
    val collectJob = launchCollection(viewModel)
    val webhookCollectJob = launchWebhookCollection(viewModel)
    testDispatcher.scheduler.advanceUntilIdle()
    assertTrue(viewModel.isWebhookConfigured.value)

    viewModel.deleteWebhookSettings()
    testDispatcher.scheduler.advanceUntilIdle()

    assertFalse(viewModel.isWebhookConfigured.value)
    assertEquals(AnalysisIntegration.NONE, viewModel.analysisIntegration.value)
    assertEquals(WebhookFormState(), viewModel.webhookFormState.value)
    assertFalse(viewModel.isWebhookEditing.value)
    webhookCollectJob.cancel()
    collectJob.cancel()
  }

  @Test
  fun `削除で選択の更新に失敗した場合は設定を消さずwebhookOperationFailureがDELETEになる`() = runTest(testDispatcher) {
    val webhookRepository = FakeWebhookSettingsRepository(initial = configuredSettings())
    val integrationRepository = FakeAnalysisIntegrationRepository(AnalysisIntegration.CUSTOM_WEBHOOK, failNextWrites = 1)
    val viewModel = SettingsViewModel(integrationRepository, webhookRepository)
    val collectJob = launchCollection(viewModel)
    val webhookCollectJob = launchWebhookCollection(viewModel)
    testDispatcher.scheduler.advanceUntilIdle()

    viewModel.deleteWebhookSettings()
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(WebhookOperationFailure.DELETE, viewModel.webhookOperationFailure.value)
    assertTrue(viewModel.isWebhookConfigured.value)
    assertEquals(AnalysisIntegration.CUSTOM_WEBHOOK, viewModel.analysisIntegration.value)
    webhookCollectJob.cancel()
    collectJob.cancel()
  }

  @Test
  fun `Custom Webhookを選んでいて未設定なら新規入力のため編集状態から始まる`() = runTest(testDispatcher) {
    val viewModel = createViewModel(AnalysisIntegration.CUSTOM_WEBHOOK, FakeWebhookSettingsRepository(initial = null))
    val collectJob = launchWebhookCollection(viewModel)
    testDispatcher.scheduler.advanceUntilIdle()

    assertFalse(viewModel.isWebhookConfigured.value)
    assertTrue(viewModel.isWebhookEditing.value)
    collectJob.cancel()
  }

  @Test
  fun `保存済み設定がある通常状態では編集せずsecretを含まない要約だけを公開する`() = runTest(testDispatcher) {
    val existing = configuredSettings()
    val viewModel = createViewModel(AnalysisIntegration.CUSTOM_WEBHOOK, FakeWebhookSettingsRepository(initial = existing))
    val collectJob = launchWebhookCollection(viewModel)
    testDispatcher.scheduler.advanceUntilIdle()

    assertFalse(viewModel.isWebhookEditing.value)
    assertEquals(WebhookFormState(), viewModel.webhookFormState.value)
    assertEquals(
      WebhookSettingsOverview.Configured(
        destination = "https://example.com",
        headerNames = listOf("Authorization"),
        bodyTemplatePlaceholders = listOf("message"),
      ),
      viewModel.webhookOverview.value,
    )
    collectJob.cancel()
  }

  @Test
  fun `解析・連携が使用しないなら保存済み設定があっても編集状態にならない`() = runTest(testDispatcher) {
    val viewModel = createViewModel(AnalysisIntegration.NONE, FakeWebhookSettingsRepository(initial = configuredSettings()))
    val collectJob = launchWebhookCollection(viewModel)
    testDispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.isWebhookConfigured.value)
    assertFalse(viewModel.isWebhookEditing.value)
    collectJob.cancel()
  }

  @Test
  fun `使用しないへ切り替えても保存済みWebhook設定は保持される`() = runTest(testDispatcher) {
    val existing = configuredSettings()
    val webhookRepository = FakeWebhookSettingsRepository(initial = existing)
    val viewModel = createViewModel(AnalysisIntegration.CUSTOM_WEBHOOK, webhookRepository)
    val collectJob = launchCollection(viewModel)
    val webhookCollectJob = launchWebhookCollection(viewModel)
    testDispatcher.scheduler.advanceUntilIdle()

    viewModel.setAnalysisIntegration(AnalysisIntegration.NONE)
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(AnalysisIntegration.NONE, viewModel.analysisIntegration.value)
    assertTrue(viewModel.isWebhookConfigured.value)
    assertEquals(WebhookSettingsState.Configured(existing), webhookRepository.settings.first())
    webhookCollectJob.cancel()
    collectJob.cancel()
  }

  @Test
  fun `Custom Webhookへ戻すと保存済み設定をそのまま編集で再利用できる`() = runTest(testDispatcher) {
    val existing = configuredSettings()
    val viewModel = createViewModel(AnalysisIntegration.CUSTOM_WEBHOOK, FakeWebhookSettingsRepository(initial = existing))
    val collectJob = launchCollection(viewModel)
    val webhookCollectJob = launchWebhookCollection(viewModel)
    viewModel.setAnalysisIntegration(AnalysisIntegration.NONE)
    testDispatcher.scheduler.advanceUntilIdle()

    viewModel.setAnalysisIntegration(AnalysisIntegration.CUSTOM_WEBHOOK)
    viewModel.startWebhookEdit()
    testDispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.isWebhookEditing.value)
    assertEquals(existing.url, viewModel.webhookFormState.value.url)
    assertEquals(existing.headers, viewModel.webhookFormState.value.headers)
    assertEquals(existing.bodyTemplate, viewModel.webhookFormState.value.bodyTemplate)
    webhookCollectJob.cancel()
    collectJob.cancel()
  }

  @Test
  fun `編集中に使用しないへ切り替えると編集状態を残さない`() = runTest(testDispatcher) {
    val viewModel = createViewModel(AnalysisIntegration.CUSTOM_WEBHOOK, FakeWebhookSettingsRepository(initial = configuredSettings()))
    val collectJob = launchCollection(viewModel)
    val webhookCollectJob = launchWebhookCollection(viewModel)
    viewModel.startWebhookEdit()
    testDispatcher.scheduler.advanceUntilIdle()
    assertTrue(viewModel.isWebhookEditing.value)

    viewModel.setAnalysisIntegration(AnalysisIntegration.NONE)
    testDispatcher.scheduler.advanceUntilIdle()

    assertFalse(viewModel.isWebhookEditing.value)
    assertEquals(WebhookFormState(), viewModel.webhookFormState.value)
    webhookCollectJob.cancel()
    collectJob.cancel()
  }

  @Test
  fun `startWebhookEditを呼ぶと保存済み設定がフォームへ反映され編集状態になる`() = runTest(testDispatcher) {
    val existing = configuredSettings()
    val viewModel = createViewModel(AnalysisIntegration.CUSTOM_WEBHOOK, FakeWebhookSettingsRepository(initial = existing))
    val collectJob = launchWebhookCollection(viewModel)
    testDispatcher.scheduler.advanceUntilIdle()

    viewModel.startWebhookEdit()
    testDispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.isWebhookEditing.value)
    assertEquals(existing.url, viewModel.webhookFormState.value.url)
    assertEquals(existing.headers, viewModel.webhookFormState.value.headers)
    assertEquals(existing.bodyTemplate, viewModel.webhookFormState.value.bodyTemplate)
    collectJob.cancel()
  }

  @Test
  fun `編集を開始した後にcancelWebhookEditで確認状態へ戻せる`() = runTest(testDispatcher) {
    val viewModel = createViewModel(AnalysisIntegration.CUSTOM_WEBHOOK, FakeWebhookSettingsRepository(initial = configuredSettings()))
    val collectJob = launchWebhookCollection(viewModel)
    viewModel.startWebhookEdit()
    testDispatcher.scheduler.advanceUntilIdle()
    assertTrue(viewModel.isWebhookEditing.value)

    viewModel.cancelWebhookEdit()
    testDispatcher.scheduler.advanceUntilIdle()

    assertFalse(viewModel.isWebhookEditing.value)
    assertEquals(WebhookFormState(), viewModel.webhookFormState.value)
    assertTrue(viewModel.isWebhookConfigured.value)
    collectJob.cancel()
  }

  @Test
  fun `編集開始時にUnavailableだった場合は編集状態にせず後から復旧しても自動で開かない`() = runTest(testDispatcher) {
    val existing = configuredSettings()
    val webhookRepository = ControllableWebhookSettingsRepository(initial = WebhookSettingsState.Unavailable)
    val viewModel = createViewModel(AnalysisIntegration.CUSTOM_WEBHOOK, webhookRepository)
    val collectJob = launchWebhookCollection(viewModel)
    testDispatcher.scheduler.runCurrent()

    viewModel.startWebhookEdit()
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(WebhookFormState(), viewModel.webhookFormState.value)
    assertFalse(viewModel.isWebhookEditing.value)

    // repositoryが後からConfiguredへ復旧しても、明示的な編集開始なしにフォームは表示されない。
    webhookRepository.emit(WebhookSettingsState.Configured(existing))
    testDispatcher.scheduler.advanceUntilIdle()

    assertFalse(viewModel.isWebhookEditing.value)
    assertEquals(WebhookFormState(), viewModel.webhookFormState.value)

    viewModel.startWebhookEdit()
    testDispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.isWebhookEditing.value)
    assertEquals(existing.url, viewModel.webhookFormState.value.url)
    collectJob.cancel()
  }

  @Test
  fun `初回読み込み完了前は編集状態にせず新規フォームを編集可能にしない`() = runTest(testDispatcher) {
    val webhookRepository = ControllableWebhookSettingsRepository(initial = WebhookSettingsState.Loading)
    val viewModel = createViewModel(AnalysisIntegration.CUSTOM_WEBHOOK, webhookRepository)
    val collectJob = launchWebhookCollection(viewModel)
    testDispatcher.scheduler.runCurrent()

    assertEquals(WebhookSettingsOverview.Loading, viewModel.webhookOverview.value)
    assertFalse(viewModel.isWebhookEditing.value)
    collectJob.cancel()
  }

  @Test
  fun `Loadingから未設定へ遷移すると新規入力フォームが表示される`() = runTest(testDispatcher) {
    val webhookRepository = ControllableWebhookSettingsRepository(initial = WebhookSettingsState.Loading)
    val viewModel = createViewModel(AnalysisIntegration.CUSTOM_WEBHOOK, webhookRepository)
    val collectJob = launchWebhookCollection(viewModel)
    testDispatcher.scheduler.runCurrent()
    assertFalse(viewModel.isWebhookEditing.value)

    webhookRepository.emit(WebhookSettingsState.NotConfigured)
    testDispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.isWebhookEditing.value)
    collectJob.cancel()
  }

  @Test
  fun `一時的な読み込み不能から既存設定へ復旧すると空フォームを残さず確認状態になる`() = runTest(testDispatcher) {
    val existing = configuredSettings()
    val webhookRepository = ControllableWebhookSettingsRepository(initial = WebhookSettingsState.Unavailable)
    val viewModel = createViewModel(AnalysisIntegration.CUSTOM_WEBHOOK, webhookRepository)
    val collectJob = launchWebhookCollection(viewModel)
    testDispatcher.scheduler.runCurrent()
    assertFalse(viewModel.isWebhookEditing.value)

    webhookRepository.emit(WebhookSettingsState.Configured(existing))
    testDispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.isWebhookConfigured.value)
    assertFalse(viewModel.isWebhookEditing.value)
    assertEquals(WebhookFormState(), viewModel.webhookFormState.value)
    collectJob.cancel()
  }

  @Test
  fun `migration前のLoadingからmigration済み設定へ遷移すると空フォームを残さず確認状態になる`() = runTest(testDispatcher) {
    val migrated = WebhookSettings(
      url = "https://legacy.example.com/webhook",
      headers = emptyList(),
      bodyTemplate = """{"text": "{{message}}"}""",
    )
    val webhookRepository = ControllableWebhookSettingsRepository(initial = WebhookSettingsState.Loading)
    val viewModel = createViewModel(AnalysisIntegration.CUSTOM_WEBHOOK, webhookRepository)
    val collectJob = launchWebhookCollection(viewModel)
    testDispatcher.scheduler.runCurrent()

    webhookRepository.emit(WebhookSettingsState.Configured(migrated))
    testDispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.isWebhookConfigured.value)
    assertFalse(viewModel.isWebhookEditing.value)
    assertEquals(WebhookFormState(), viewModel.webhookFormState.value)
    collectJob.cancel()
  }

  @Test
  fun `未設定として入力中に保存済み設定が現れてもその入力を既存設定の編集として扱わない`() = runTest(testDispatcher) {
    val migrated = WebhookSettings(
      url = "https://legacy.example.com/webhook",
      headers = listOf(WebhookHeader("Authorization", "Bearer legacy")),
      bodyTemplate = """{"text": "{{message}}"}""",
    )
    val webhookRepository = ControllableWebhookSettingsRepository(initial = WebhookSettingsState.NotConfigured)
    val viewModel = createViewModel(AnalysisIntegration.CUSTOM_WEBHOOK, webhookRepository)
    val collectJob = launchWebhookCollection(viewModel)
    viewModel.updateWebhookUrl("https://typing.example.com")
    testDispatcher.scheduler.advanceUntilIdle()
    assertTrue(viewModel.isWebhookEditing.value)

    // legacy migration等で、入力中に保存済み設定が現れた場合。
    webhookRepository.emit(WebhookSettingsState.Configured(migrated))
    testDispatcher.scheduler.advanceUntilIdle()

    assertFalse(viewModel.isWebhookEditing.value)
    collectJob.cancel()
  }

  @Test
  fun `validation error表示中はその場の編集では消えず修正しながら確認できる`() = runTest(testDispatcher) {
    val viewModel = createViewModel(AnalysisIntegration.CUSTOM_WEBHOOK, FakeWebhookSettingsRepository())
    val collectJob = launchWebhookCollection(viewModel)
    viewModel.updateWebhookUrl("not a url")
    viewModel.updateWebhookBodyTemplate("""{"text": "{{message}}"}""")
    viewModel.saveWebhookSettings()
    testDispatcher.scheduler.advanceUntilIdle()
    assertEquals(listOf(WebhookSettingsValidator.ValidationError.INVALID_URL), viewModel.webhookValidationErrors.value)

    viewModel.updateWebhookUrl("https://example.com/webhook")
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(listOf(WebhookSettingsValidator.ValidationError.INVALID_URL), viewModel.webhookValidationErrors.value)
    collectJob.cancel()
  }

  @Test
  fun `validation error後に設定画面へ入り直すとerrorが残らない`() = runTest(testDispatcher) {
    val viewModel = createViewModel(AnalysisIntegration.CUSTOM_WEBHOOK, FakeWebhookSettingsRepository())
    val collectJob = launchWebhookCollection(viewModel)
    viewModel.updateWebhookUrl("not a url")
    viewModel.saveWebhookSettings()
    testDispatcher.scheduler.advanceUntilIdle()
    assertTrue(viewModel.webhookValidationErrors.value.isNotEmpty())

    viewModel.onSettingsOpened()
    testDispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.webhookValidationErrors.value.isEmpty())
    assertNull(viewModel.webhookOperationFailure.value)
    collectJob.cancel()
  }

  @Test
  fun `validation error後に編集をやめて開き直すとerrorが残らない`() = runTest(testDispatcher) {
    val existing = configuredSettings()
    val viewModel = createViewModel(AnalysisIntegration.CUSTOM_WEBHOOK, FakeWebhookSettingsRepository(initial = existing))
    val collectJob = launchWebhookCollection(viewModel)
    viewModel.startWebhookEdit()
    testDispatcher.scheduler.advanceUntilIdle()
    viewModel.updateWebhookUrl("not a url")
    viewModel.saveWebhookSettings()
    testDispatcher.scheduler.advanceUntilIdle()
    assertTrue(viewModel.webhookValidationErrors.value.isNotEmpty())

    viewModel.cancelWebhookEdit()
    viewModel.startWebhookEdit()
    testDispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.webhookValidationErrors.value.isEmpty())
    assertEquals(existing.url, viewModel.webhookFormState.value.url)
    collectJob.cancel()
  }

  @Test
  fun `設定画面へ入り直すと保存済み設定の編集は閉じた状態から始まる`() = runTest(testDispatcher) {
    val viewModel = createViewModel(AnalysisIntegration.CUSTOM_WEBHOOK, FakeWebhookSettingsRepository(initial = configuredSettings()))
    val collectJob = launchWebhookCollection(viewModel)
    viewModel.startWebhookEdit()
    testDispatcher.scheduler.advanceUntilIdle()
    assertTrue(viewModel.isWebhookEditing.value)

    viewModel.onSettingsOpened()
    testDispatcher.scheduler.advanceUntilIdle()

    assertFalse(viewModel.isWebhookEditing.value)
    assertEquals(WebhookFormState(), viewModel.webhookFormState.value)
    collectJob.cancel()
  }

  @Test
  fun `保存中にフォームを編集した場合、古いsaveの完了でフォーム内容が消えない`() = runTest(testDispatcher) {
    val webhookRepository = GatedSaveWebhookSettingsRepository()
    val viewModel = createViewModel(AnalysisIntegration.CUSTOM_WEBHOOK, webhookRepository)
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
    assertTrue(viewModel.isWebhookEditing.value)
    collectJob.cancel()
  }

  @Test
  fun `保存中にフォームを編集した場合、古いsaveの失敗でもフォーム内容が消えない`() = runTest(testDispatcher) {
    val webhookRepository = GatedSaveWebhookSettingsRepository()
    val viewModel = createViewModel(AnalysisIntegration.CUSTOM_WEBHOOK, webhookRepository)
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
    assertTrue(viewModel.isWebhookEditing.value)
    collectJob.cancel()
  }

  @Test
  fun `保存中に編集したフォーム内容は古いsave完了後に改めて保存できる`() = runTest(testDispatcher) {
    val webhookRepository = GatedSaveWebhookSettingsRepository()
    val viewModel = createViewModel(AnalysisIntegration.CUSTOM_WEBHOOK, webhookRepository)
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
    assertFalse(viewModel.isWebhookEditing.value)
    assertEquals(WebhookFormState(), viewModel.webhookFormState.value)
    collectJob.cancel()
  }

  private fun createViewModel(
    integration: AnalysisIntegration,
    webhookRepository: WebhookSettingsRepository,
  ) = SettingsViewModel(FakeAnalysisIntegrationRepository(integration), webhookRepository)

  private fun configuredSettings() = WebhookSettings(
    url = "https://example.com/webhook",
    headers = listOf(WebhookHeader("Authorization", "Bearer xxxxx")),
    bodyTemplate = """{"text": "{{message}}"}""",
  )

  private fun launchCollection(viewModel: SettingsViewModel) =
    CoroutineScope(testDispatcher).launch { viewModel.analysisIntegration.collect {} }

  private fun launchWebhookCollection(viewModel: SettingsViewModel) =
    CoroutineScope(testDispatcher).launch {
      launch { viewModel.isWebhookConfigured.collect {} }
      launch { viewModel.isWebhookEditing.collect {} }
      launch { viewModel.webhookOverview.collect {} }
    }

  /** setAnalysisIntegration()の完了/失敗を呼び出し順と切り離して制御し、write完了順の入れ替わりを再現するFake。 */
  private class ControllableAnalysisIntegrationRepository(initial: AnalysisIntegration) : AnalysisIntegrationRepository {
    private val state = MutableStateFlow(initial)
    override val analysisIntegration: Flow<AnalysisIntegration> = state
    private val gates = mutableListOf<CompletableDeferred<Throwable?>>()

    fun complete(index: Int) {
      gates[index].complete(null)
    }

    fun fail(index: Int, error: Throwable) {
      gates[index].complete(error)
    }

    override suspend fun setAnalysisIntegration(integration: AnalysisIntegration) {
      val gate = CompletableDeferred<Throwable?>()
      gates += gate
      val error = gate.await()
      if (error != null) throw error
      state.value = integration
    }
  }

  private class FakeAnalysisIntegrationRepository(
    initial: AnalysisIntegration,
    private var failNextWrites: Int = 0,
  ) : AnalysisIntegrationRepository {
    private val state = MutableStateFlow(initial)
    override val analysisIntegration: Flow<AnalysisIntegration> = state

    override suspend fun setAnalysisIntegration(integration: AnalysisIntegration) {
      if (failNextWrites > 0) {
        failNextWrites--
        throw IOException("disk error")
      }
      state.value = integration
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
