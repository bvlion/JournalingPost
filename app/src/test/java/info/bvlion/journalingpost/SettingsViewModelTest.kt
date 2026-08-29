package info.bvlion.journalingpost

import info.bvlion.journalingpost.settings.AnalysisIntegration
import info.bvlion.journalingpost.settings.AnalysisIntegrationRepository
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

  // ---- 解析・連携の選択(使用しない) ----

  @Test
  fun `使用しないを選ぶとその場で永続化されintegrationSaveFailedはfalseのまま`() = runTest(testDispatcher) {
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
    viewModel.setAnalysisIntegration(AnalysisIntegration.NONE)
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
    viewModel.setAnalysisIntegration(AnalysisIntegration.NONE)
    testDispatcher.scheduler.runCurrent()

    repository.fail(1, IOException("disk error"))
    testDispatcher.scheduler.advanceUntilIdle()
    repository.complete(0)
    testDispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.integrationSaveFailed.value)
    collectJob.cancel()
  }

  // ---- 解析・連携の選択(Custom Webhook) ----

  @Test
  fun `保存済みWebhook設定があればCustom Webhookを選んだ時点で有効になりセットアップ要求は出ない`() = runTest(testDispatcher) {
    val webhookRepository = FakeWebhookSettingsRepository(initial = WebhookSettingsState.Configured(configuredSettings()))
    val viewModel = SettingsViewModel(FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE), webhookRepository)
    val collectJob = launchCollection(viewModel)

    viewModel.setAnalysisIntegration(AnalysisIntegration.CUSTOM_WEBHOOK)
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(AnalysisIntegration.CUSTOM_WEBHOOK, viewModel.analysisIntegration.value)
    assertEquals(AnalysisIntegration.CUSTOM_WEBHOOK, viewModel.selectedAnalysisIntegration.value)
    assertFalse(viewModel.webhookSetupRequested.value)
    collectJob.cancel()
  }

  @Test
  fun `未設定のままCustom Webhookを選ぶと有効化せずセットアップ要求だけが出る`() = runTest(testDispatcher) {
    val webhookRepository = FakeWebhookSettingsRepository(initial = WebhookSettingsState.NotConfigured)
    val viewModel = SettingsViewModel(FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE), webhookRepository)
    val collectJob = launchCollection(viewModel)

    viewModel.setAnalysisIntegration(AnalysisIntegration.CUSTOM_WEBHOOK)
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(AnalysisIntegration.NONE, viewModel.analysisIntegration.value)
    assertEquals(AnalysisIntegration.CUSTOM_WEBHOOK, viewModel.selectedAnalysisIntegration.value)
    assertTrue(viewModel.webhookSetupRequested.value)
    collectJob.cancel()
  }

  @Test
  fun `Webhook設定を一時的に読めない場合もCustom Webhookは有効化せずセットアップ要求を出す`() = runTest(testDispatcher) {
    val webhookRepository = FakeWebhookSettingsRepository(initial = WebhookSettingsState.Unavailable)
    val viewModel = SettingsViewModel(FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE), webhookRepository)
    val collectJob = launchCollection(viewModel)

    viewModel.setAnalysisIntegration(AnalysisIntegration.CUSTOM_WEBHOOK)
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(AnalysisIntegration.NONE, viewModel.analysisIntegration.value)
    assertTrue(viewModel.webhookSetupRequested.value)
    collectJob.cancel()
  }

  @Test
  fun `consumeWebhookSetupRequestを呼ぶと要求はfalseに戻る`() = runTest(testDispatcher) {
    val webhookRepository = FakeWebhookSettingsRepository(initial = WebhookSettingsState.NotConfigured)
    val viewModel = SettingsViewModel(FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE), webhookRepository)
    viewModel.setAnalysisIntegration(AnalysisIntegration.CUSTOM_WEBHOOK)
    testDispatcher.scheduler.advanceUntilIdle()
    assertTrue(viewModel.webhookSetupRequested.value)

    viewModel.consumeWebhookSetupRequest()

    assertFalse(viewModel.webhookSetupRequested.value)
  }

  @Test
  fun `未設定のCustom Webhook選択中に使用しないへ切り替えると保留中の判定は無視される`() = runTest(testDispatcher) {
    val webhookRepository = FakeWebhookSettingsRepository(initial = WebhookSettingsState.NotConfigured)
    val viewModel = SettingsViewModel(FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE), webhookRepository)
    val collectJob = launchCollection(viewModel)

    viewModel.setAnalysisIntegration(AnalysisIntegration.CUSTOM_WEBHOOK)
    viewModel.setAnalysisIntegration(AnalysisIntegration.NONE)
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(AnalysisIntegration.NONE, viewModel.selectedAnalysisIntegration.value)
    assertFalse(viewModel.webhookSetupRequested.value)
    collectJob.cancel()
  }

  @Test
  fun `使用しないへ切り替えても保存済みWebhook設定は保持される`() = runTest(testDispatcher) {
    val existing = configuredSettings()
    val webhookRepository = FakeWebhookSettingsRepository(initial = WebhookSettingsState.Configured(existing))
    val viewModel = SettingsViewModel(FakeAnalysisIntegrationRepository(AnalysisIntegration.CUSTOM_WEBHOOK), webhookRepository)
    val collectJob = launchCollection(viewModel)

    viewModel.setAnalysisIntegration(AnalysisIntegration.NONE)
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(AnalysisIntegration.NONE, viewModel.analysisIntegration.value)
    assertEquals(WebhookSettingsState.Configured(existing), webhookRepository.currentState())
    collectJob.cancel()
  }

  @Test
  fun `onSettingsOpenedで未確定のCustom Webhook選択は使用しないへ戻る`() = runTest(testDispatcher) {
    val webhookRepository = FakeWebhookSettingsRepository(initial = WebhookSettingsState.NotConfigured)
    val viewModel = SettingsViewModel(FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE), webhookRepository)
    val collectJob = launchCollection(viewModel)
    viewModel.setAnalysisIntegration(AnalysisIntegration.CUSTOM_WEBHOOK)
    testDispatcher.scheduler.advanceUntilIdle()
    assertEquals(AnalysisIntegration.CUSTOM_WEBHOOK, viewModel.selectedAnalysisIntegration.value)

    viewModel.onSettingsOpened()
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(AnalysisIntegration.NONE, viewModel.selectedAnalysisIntegration.value)
    collectJob.cancel()
  }

  // ---- 親Settingsの「Webhook設定」項目に出す送信先label ----

  @Test
  fun `使用しないのときはwebhookDestinationLabelがnull`() = runTest(testDispatcher) {
    val webhookRepository = FakeWebhookSettingsRepository(initial = WebhookSettingsState.Configured(configuredSettings()))
    val viewModel = SettingsViewModel(FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE), webhookRepository)
    val collectJob = launchLabelCollection(viewModel)
    testDispatcher.scheduler.advanceUntilIdle()

    assertNull(viewModel.webhookDestinationLabel.value)
    collectJob.cancel()
  }

  @Test
  fun `Custom Webhookが有効かつ設定済みならwebhookDestinationLabelに送信先が入る`() = runTest(testDispatcher) {
    val webhookRepository = FakeWebhookSettingsRepository(
      initial = WebhookSettingsState.Configured(configuredSettings(url = "https://hooks.example.com/services/xxx")),
    )
    val viewModel = SettingsViewModel(FakeAnalysisIntegrationRepository(AnalysisIntegration.CUSTOM_WEBHOOK), webhookRepository)
    val collectJob = launchLabelCollection(viewModel)
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals("https://hooks.example.com", viewModel.webhookDestinationLabel.value)
    collectJob.cancel()
  }

  @Test
  fun `webhookDestinationLabelにHeader値やBody templateの情報は含まれない`() = runTest(testDispatcher) {
    val webhookRepository = FakeWebhookSettingsRepository(
      initial = WebhookSettingsState.Configured(
        configuredSettings(url = "https://hooks.example.com/webhook").copy(
          headers = listOf(WebhookHeader("Authorization", "Bearer secret-token")),
          bodyTemplate = """{"text": "{{message}}", "channel": "secret-channel"}""",
        ),
      ),
    )
    val viewModel = SettingsViewModel(FakeAnalysisIntegrationRepository(AnalysisIntegration.CUSTOM_WEBHOOK), webhookRepository)
    val collectJob = launchLabelCollection(viewModel)
    testDispatcher.scheduler.advanceUntilIdle()

    val label = viewModel.webhookDestinationLabel.value
    assertEquals("https://hooks.example.com", label)
    assertFalse(label!!.contains("secret"))
    collectJob.cancel()
  }

  // ---- Webhook設定画面: 読み込みとフォームの初期化 ----

  @Test
  fun `未設定でWebhook設定画面を開くとREADYで空フォームになる`() = runTest(testDispatcher) {
    val webhookRepository = FakeWebhookSettingsRepository(initial = WebhookSettingsState.NotConfigured)
    val viewModel = SettingsViewModel(FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE), webhookRepository)
    val collectJob = launchWebhookScreenCollection(viewModel)

    viewModel.onWebhookSettingsScreenOpened()
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(WebhookSettingsLoadState.READY, viewModel.webhookSettingsLoadState.value)
    assertEquals(WebhookFormState(), viewModel.webhookFormState.value)
    collectJob.cancel()
  }

  @Test
  fun `設定済みでWebhook設定画面を開くとREADYで既存値がフォームへ反映される`() = runTest(testDispatcher) {
    val existing = configuredSettings()
    val webhookRepository = FakeWebhookSettingsRepository(initial = WebhookSettingsState.Configured(existing))
    val viewModel = SettingsViewModel(FakeAnalysisIntegrationRepository(AnalysisIntegration.CUSTOM_WEBHOOK), webhookRepository)
    val collectJob = launchWebhookScreenCollection(viewModel)

    viewModel.onWebhookSettingsScreenOpened()
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(WebhookSettingsLoadState.READY, viewModel.webhookSettingsLoadState.value)
    assertEquals(existing.url, viewModel.webhookFormState.value.url)
    assertEquals(existing.headers, viewModel.webhookFormState.value.headers)
    assertEquals(existing.bodyTemplate, viewModel.webhookFormState.value.bodyTemplate)
    collectJob.cancel()
  }

  @Test
  fun `authoritativeな状態が分かるまではLOADINGで空フォームを編集可能にしない`() = runTest(testDispatcher) {
    val webhookRepository = ControllableWebhookSettingsRepository(initial = WebhookSettingsState.Loading)
    val viewModel = SettingsViewModel(FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE), webhookRepository)
    val collectJob = launchWebhookScreenCollection(viewModel)

    viewModel.onWebhookSettingsScreenOpened()
    testDispatcher.scheduler.runCurrent()

    assertEquals(WebhookSettingsLoadState.LOADING, viewModel.webhookSettingsLoadState.value)
    collectJob.cancel()
  }

  @Test
  fun `読み込み不能はUNAVAILABLEとして示しフォームを確定表示しない`() = runTest(testDispatcher) {
    val webhookRepository = ControllableWebhookSettingsRepository(initial = WebhookSettingsState.Unavailable)
    val viewModel = SettingsViewModel(FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE), webhookRepository)
    val collectJob = launchWebhookScreenCollection(viewModel)

    viewModel.onWebhookSettingsScreenOpened()
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(WebhookSettingsLoadState.UNAVAILABLE, viewModel.webhookSettingsLoadState.value)
    assertEquals(WebhookFormState(), viewModel.webhookFormState.value)
    collectJob.cancel()
  }

  @Test
  fun `一時的な読み込み不能から復旧すると空フォームを残さず既存設定を反映する`() = runTest(testDispatcher) {
    val existing = configuredSettings()
    val webhookRepository = ControllableWebhookSettingsRepository(initial = WebhookSettingsState.Unavailable)
    val viewModel = SettingsViewModel(FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE), webhookRepository)
    val collectJob = launchWebhookScreenCollection(viewModel)
    viewModel.onWebhookSettingsScreenOpened()
    testDispatcher.scheduler.advanceUntilIdle()
    assertEquals(WebhookSettingsLoadState.UNAVAILABLE, viewModel.webhookSettingsLoadState.value)

    webhookRepository.emit(WebhookSettingsState.Configured(existing))
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(WebhookSettingsLoadState.READY, viewModel.webhookSettingsLoadState.value)
    assertEquals(existing.url, viewModel.webhookFormState.value.url)
    collectJob.cancel()
  }

  @Test
  fun `一度読み込んだ後にフォームを編集していると後続の状態変化で上書きされない`() = runTest(testDispatcher) {
    val existing = configuredSettings()
    val webhookRepository = ControllableWebhookSettingsRepository(initial = WebhookSettingsState.NotConfigured)
    val viewModel = SettingsViewModel(FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE), webhookRepository)
    val collectJob = launchWebhookScreenCollection(viewModel)
    viewModel.onWebhookSettingsScreenOpened()
    testDispatcher.scheduler.advanceUntilIdle()

    // 未設定として開いたフォームへ入力を始めた後、legacy migration等で保存済み設定が現れた場合。
    viewModel.updateWebhookUrl("https://typing.example.com/webhook")
    webhookRepository.emit(WebhookSettingsState.Configured(existing))
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals("https://typing.example.com/webhook", viewModel.webhookFormState.value.url)
    collectJob.cancel()
  }

  @Test
  fun `Webhook設定画面を閉じると次に開いたときはフォームが読み込み直される`() = runTest(testDispatcher) {
    val webhookRepository = FakeWebhookSettingsRepository(initial = WebhookSettingsState.NotConfigured)
    val viewModel = SettingsViewModel(FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE), webhookRepository)
    val collectJob = launchWebhookScreenCollection(viewModel)
    viewModel.onWebhookSettingsScreenOpened()
    testDispatcher.scheduler.advanceUntilIdle()
    viewModel.updateWebhookUrl("https://unsaved.example.com/webhook")

    viewModel.onWebhookSettingsScreenClosed()
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(WebhookFormState(), viewModel.webhookFormState.value)
    collectJob.cancel()
  }

  @Test
  fun `未設定のセットアップ中に保存せず閉じると使用しないへ戻る`() = runTest(testDispatcher) {
    val webhookRepository = FakeWebhookSettingsRepository(initial = WebhookSettingsState.NotConfigured)
    val viewModel = SettingsViewModel(FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE), webhookRepository)
    val collectJob = launchCollection(viewModel)
    viewModel.setAnalysisIntegration(AnalysisIntegration.CUSTOM_WEBHOOK)
    testDispatcher.scheduler.advanceUntilIdle()
    viewModel.onWebhookSettingsScreenOpened()
    testDispatcher.scheduler.advanceUntilIdle()

    viewModel.onWebhookSettingsScreenClosed()
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(AnalysisIntegration.NONE, viewModel.selectedAnalysisIntegration.value)
    assertEquals(AnalysisIntegration.NONE, viewModel.analysisIntegration.value)
    collectJob.cancel()
  }

  @Test
  fun `既に有効なCustom Webhookの設定画面を保存せず閉じても有効状態は変わらない`() = runTest(testDispatcher) {
    val webhookRepository = FakeWebhookSettingsRepository(initial = WebhookSettingsState.Configured(configuredSettings()))
    val viewModel = SettingsViewModel(FakeAnalysisIntegrationRepository(AnalysisIntegration.CUSTOM_WEBHOOK), webhookRepository)
    val collectJob = launchCollection(viewModel)
    viewModel.onWebhookSettingsScreenOpened()
    testDispatcher.scheduler.advanceUntilIdle()
    viewModel.updateWebhookUrl("https://not-saved.example.com/webhook")

    viewModel.onWebhookSettingsScreenClosed()
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(AnalysisIntegration.CUSTOM_WEBHOOK, viewModel.selectedAnalysisIntegration.value)
    assertEquals(AnalysisIntegration.CUSTOM_WEBHOOK, viewModel.analysisIntegration.value)
    collectJob.cancel()
  }

  // ---- Webhook設定画面: 保存 ----

  @Test
  fun `validation失敗時にはrepositoryへ保存されず解析・連携も変わらない`() = runTest(testDispatcher) {
    val webhookRepository = FakeWebhookSettingsRepository(initial = WebhookSettingsState.NotConfigured)
    val viewModel = SettingsViewModel(FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE), webhookRepository)
    val collectJob = launchWebhookScreenCollection(viewModel)
    viewModel.onWebhookSettingsScreenOpened()
    testDispatcher.scheduler.advanceUntilIdle()

    viewModel.updateWebhookUrl("not a url")
    viewModel.updateWebhookBodyTemplate("""{"text": "{{message}}"}""")
    viewModel.saveWebhookSettings()
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(0, webhookRepository.saveCallCount)
    assertEquals(listOf(WebhookSettingsValidator.ValidationError.INVALID_URL), viewModel.webhookValidationErrors.value)
    assertEquals(AnalysisIntegration.NONE, viewModel.analysisIntegration.value)
    collectJob.cancel()
  }

  @Test
  fun `未設定のセットアップから保存に成功するとCustom Webhookが有効になる`() = runTest(testDispatcher) {
    val webhookRepository = FakeWebhookSettingsRepository(initial = WebhookSettingsState.NotConfigured)
    val viewModel = SettingsViewModel(FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE), webhookRepository)
    val collectJob = launchCollection(viewModel)
    val screenCollectJob = launchWebhookScreenCollection(viewModel)
    viewModel.setAnalysisIntegration(AnalysisIntegration.CUSTOM_WEBHOOK)
    viewModel.onWebhookSettingsScreenOpened()
    testDispatcher.scheduler.advanceUntilIdle()

    viewModel.updateWebhookUrl("https://example.com/webhook")
    viewModel.updateWebhookBodyTemplate("""{"text": "{{message}}"}""")
    viewModel.saveWebhookSettings()
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(1, webhookRepository.saveCallCount)
    assertEquals(AnalysisIntegration.CUSTOM_WEBHOOK, viewModel.analysisIntegration.value)
    assertTrue(viewModel.webhookValidationErrors.value.isEmpty())
    assertFalse(viewModel.webhookSaveFailed.value)
    screenCollectJob.cancel()
    collectJob.cancel()
  }

  @Test
  fun `保存後もフォームには保存した内容が残る`() = runTest(testDispatcher) {
    val webhookRepository = FakeWebhookSettingsRepository(initial = WebhookSettingsState.NotConfigured)
    val viewModel = SettingsViewModel(FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE), webhookRepository)
    val collectJob = launchWebhookScreenCollection(viewModel)
    viewModel.onWebhookSettingsScreenOpened()
    testDispatcher.scheduler.advanceUntilIdle()

    viewModel.updateWebhookUrl("https://example.com/webhook")
    viewModel.updateWebhookBodyTemplate("""{"text": "{{message}}"}""")
    viewModel.saveWebhookSettings()
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals("https://example.com/webhook", viewModel.webhookFormState.value.url)
    collectJob.cancel()
  }

  @Test
  fun `既に有効なCustom Webhookの設定を保存し直しても有効状態は保たれる`() = runTest(testDispatcher) {
    val webhookRepository = FakeWebhookSettingsRepository(initial = WebhookSettingsState.Configured(configuredSettings()))
    val viewModel = SettingsViewModel(FakeAnalysisIntegrationRepository(AnalysisIntegration.CUSTOM_WEBHOOK), webhookRepository)
    val collectJob = launchCollection(viewModel)
    val screenCollectJob = launchWebhookScreenCollection(viewModel)
    viewModel.onWebhookSettingsScreenOpened()
    testDispatcher.scheduler.advanceUntilIdle()

    viewModel.updateWebhookUrl("https://updated.example.com/webhook")
    viewModel.saveWebhookSettings()
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals("https://updated.example.com/webhook", webhookRepository.savedSettings?.url)
    assertEquals(AnalysisIntegration.CUSTOM_WEBHOOK, viewModel.analysisIntegration.value)
    screenCollectJob.cancel()
    collectJob.cancel()
  }

  @Test
  fun `webhook設定のrepository保存失敗時はwebhookSaveFailedがtrueになりCustom Webhookは有効化しない`() = runTest(testDispatcher) {
    val webhookRepository = FakeWebhookSettingsRepository(initial = WebhookSettingsState.NotConfigured, failNextSaves = 1)
    val viewModel = SettingsViewModel(FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE), webhookRepository)
    val collectJob = launchCollection(viewModel)
    val screenCollectJob = launchWebhookScreenCollection(viewModel)
    viewModel.onWebhookSettingsScreenOpened()
    testDispatcher.scheduler.advanceUntilIdle()

    viewModel.updateWebhookUrl("https://example.com/webhook")
    viewModel.updateWebhookBodyTemplate("""{"text": "{{message}}"}""")
    viewModel.saveWebhookSettings()
    testDispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.webhookSaveFailed.value)
    assertEquals(AnalysisIntegration.NONE, viewModel.analysisIntegration.value)
    screenCollectJob.cancel()
    collectJob.cancel()
  }

  @Test
  fun `validation errorはその場の編集では消えず修正しながら確認できる`() = runTest(testDispatcher) {
    val webhookRepository = FakeWebhookSettingsRepository(initial = WebhookSettingsState.NotConfigured)
    val viewModel = SettingsViewModel(FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE), webhookRepository)
    val collectJob = launchWebhookScreenCollection(viewModel)
    viewModel.onWebhookSettingsScreenOpened()
    testDispatcher.scheduler.advanceUntilIdle()
    viewModel.updateWebhookUrl("not a url")
    viewModel.updateWebhookBodyTemplate("""{"text": "{{message}}"}""")
    viewModel.saveWebhookSettings()
    testDispatcher.scheduler.advanceUntilIdle()
    assertEquals(listOf(WebhookSettingsValidator.ValidationError.INVALID_URL), viewModel.webhookValidationErrors.value)

    viewModel.updateWebhookBodyTemplate("""{"text": "{{message}} edited"}""")
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals(listOf(WebhookSettingsValidator.ValidationError.INVALID_URL), viewModel.webhookValidationErrors.value)
    collectJob.cancel()
  }

  @Test
  fun `validation error後に画面を開き直すとerrorが残らない`() = runTest(testDispatcher) {
    val webhookRepository = FakeWebhookSettingsRepository(initial = WebhookSettingsState.NotConfigured)
    val viewModel = SettingsViewModel(FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE), webhookRepository)
    val collectJob = launchWebhookScreenCollection(viewModel)
    viewModel.onWebhookSettingsScreenOpened()
    testDispatcher.scheduler.advanceUntilIdle()
    viewModel.updateWebhookUrl("not a url")
    viewModel.saveWebhookSettings()
    testDispatcher.scheduler.advanceUntilIdle()
    assertTrue(viewModel.webhookValidationErrors.value.isNotEmpty())

    viewModel.onWebhookSettingsScreenOpened()
    testDispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.webhookValidationErrors.value.isEmpty())
    assertFalse(viewModel.webhookSaveFailed.value)
    collectJob.cancel()
  }

  @Test
  fun `validation error後に画面を閉じるとerrorが残らない`() = runTest(testDispatcher) {
    val webhookRepository = FakeWebhookSettingsRepository(initial = WebhookSettingsState.NotConfigured)
    val viewModel = SettingsViewModel(FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE), webhookRepository)
    val collectJob = launchWebhookScreenCollection(viewModel)
    viewModel.onWebhookSettingsScreenOpened()
    testDispatcher.scheduler.advanceUntilIdle()
    viewModel.updateWebhookUrl("not a url")
    viewModel.saveWebhookSettings()
    testDispatcher.scheduler.advanceUntilIdle()
    assertTrue(viewModel.webhookValidationErrors.value.isNotEmpty())

    viewModel.onWebhookSettingsScreenClosed()

    assertTrue(viewModel.webhookValidationErrors.value.isEmpty())
    collectJob.cancel()
  }

  @Test
  fun `保存中にフォームを編集した場合、古いsaveの完了でフォーム内容が消えない`() = runTest(testDispatcher) {
    val webhookRepository = GatedSaveWebhookSettingsRepository()
    val viewModel = SettingsViewModel(FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE), webhookRepository)
    val collectJob = launchWebhookScreenCollection(viewModel)
    viewModel.onWebhookSettingsScreenOpened()
    testDispatcher.scheduler.advanceUntilIdle()
    viewModel.updateWebhookUrl("https://a.example.com/webhook")
    viewModel.updateWebhookBodyTemplate("""{"text": "{{message}}"}""")

    viewModel.saveWebhookSettings()
    testDispatcher.scheduler.runCurrent() // saveはgateで止まるため、write進行中の状態まで進む
    viewModel.updateWebhookUrl("https://b.example.com/webhook")
    webhookRepository.completeSave()
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals("https://a.example.com/webhook", webhookRepository.savedSettings?.url)
    assertEquals("https://b.example.com/webhook", viewModel.webhookFormState.value.url)
    collectJob.cancel()
  }

  @Test
  fun `保存中にフォームを編集した場合、古いsaveの失敗でもフォーム内容が消えない`() = runTest(testDispatcher) {
    val webhookRepository = GatedSaveWebhookSettingsRepository()
    val viewModel = SettingsViewModel(FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE), webhookRepository)
    val collectJob = launchWebhookScreenCollection(viewModel)
    viewModel.onWebhookSettingsScreenOpened()
    testDispatcher.scheduler.advanceUntilIdle()
    viewModel.updateWebhookUrl("https://a.example.com/webhook")
    viewModel.updateWebhookBodyTemplate("""{"text": "{{message}}"}""")

    viewModel.saveWebhookSettings()
    testDispatcher.scheduler.runCurrent()
    viewModel.updateWebhookUrl("https://b.example.com/webhook")
    webhookRepository.failSave(IOException("disk error"))
    testDispatcher.scheduler.advanceUntilIdle()

    assertEquals("https://b.example.com/webhook", viewModel.webhookFormState.value.url)
    collectJob.cancel()
  }

  @Test
  fun `保存中に編集したフォーム内容は古いsave完了後に改めて保存できる`() = runTest(testDispatcher) {
    val webhookRepository = GatedSaveWebhookSettingsRepository()
    val viewModel = SettingsViewModel(FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE), webhookRepository)
    val collectJob = launchWebhookScreenCollection(viewModel)
    viewModel.onWebhookSettingsScreenOpened()
    testDispatcher.scheduler.advanceUntilIdle()
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
    collectJob.cancel()
  }

  private fun configuredSettings(url: String = "https://example.com/webhook") = WebhookSettings(
    url = url,
    headers = listOf(WebhookHeader("Authorization", "Bearer xxxxx")),
    bodyTemplate = """{"text": "{{message}}"}""",
  )

  private fun launchCollection(viewModel: SettingsViewModel) =
    CoroutineScope(testDispatcher).launch {
      launch { viewModel.analysisIntegration.collect {} }
      launch { viewModel.selectedAnalysisIntegration.collect {} }
    }

  private fun launchLabelCollection(viewModel: SettingsViewModel) =
    CoroutineScope(testDispatcher).launch { viewModel.webhookDestinationLabel.collect {} }

  private fun launchWebhookScreenCollection(viewModel: SettingsViewModel) =
    CoroutineScope(testDispatcher).launch {
      launch { viewModel.webhookSettingsLoadState.collect {} }
      launch { viewModel.webhookFormState.collect {} }
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
    initial: WebhookSettingsState = WebhookSettingsState.NotConfigured,
    private var failNextSaves: Int = 0,
  ) : WebhookSettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings: Flow<WebhookSettingsState> = state
    var saveCallCount = 0
      private set
    var savedSettings: WebhookSettings? = null
      private set

    fun currentState(): WebhookSettingsState = state.value

    override suspend fun save(settings: WebhookSettings) {
      saveCallCount++
      if (failNextSaves > 0) {
        failNextSaves--
        throw IOException("disk error")
      }
      savedSettings = settings
      state.value = WebhookSettingsState.Configured(settings)
    }

    override suspend fun clear() {
      state.value = WebhookSettingsState.NotConfigured
    }

    override suspend fun isLegacyMigrationCompleted(): Boolean = true

    override suspend fun markLegacyMigrationCompleted() = Unit
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
