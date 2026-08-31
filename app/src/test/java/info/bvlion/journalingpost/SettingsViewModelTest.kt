package info.bvlion.journalingpost

import info.bvlion.journalingpost.settings.AnalysisIntegration
import info.bvlion.journalingpost.settings.AnalysisIntegrationRepository
import info.bvlion.journalingpost.webhook.WebhookBodyTemplateRenderer
import info.bvlion.journalingpost.webhook.WebhookHeader
import info.bvlion.journalingpost.webhook.WebhookSettings
import info.bvlion.journalingpost.webhook.WebhookSettingsRepository
import info.bvlion.journalingpost.webhook.WebhookSettingsState
import info.bvlion.journalingpost.webhook.WebhookSettingsValidator
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
  private val dispatcher = StandardTestDispatcher()

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun `解析連携は読み込み確定まで未選択として扱う`() = runTest(dispatcher) {
    val integrationRepository = GatedAnalysisIntegrationRepository(AnalysisIntegration.CUSTOM_WEBHOOK)
    val viewModel = SettingsViewModel(integrationRepository, FakeWebhookSettingsRepository(configuredSettings()))
    val collection = collectState(viewModel)
    runCurrent()

    assertNull(viewModel.analysisIntegration.value)
    assertNull(viewModel.selectedAnalysisIntegration.value)

    integrationRepository.release()
    advanceUntilIdle()

    assertEquals(AnalysisIntegration.CUSTOM_WEBHOOK, viewModel.selectedAnalysisIntegration.value)
    collection.cancel()
  }

  @Test
  fun `使用しないを選ぶとNONEを保存する`() = runTest(dispatcher) {
    val integrationRepository = FakeAnalysisIntegrationRepository(AnalysisIntegration.CUSTOM_WEBHOOK)
    val viewModel = SettingsViewModel(integrationRepository, FakeWebhookSettingsRepository(configuredSettings()))

    viewModel.setAnalysisIntegration(AnalysisIntegration.NONE)
    advanceUntilIdle()

    assertEquals(AnalysisIntegration.NONE, integrationRepository.current)
    assertFalse(viewModel.integrationSaveFailed.value)
  }

  @Test
  fun `設定済みCustom Webhookを選ぶとその場で有効化する`() = runTest(dispatcher) {
    val integrationRepository = FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE)
    val viewModel = SettingsViewModel(integrationRepository, FakeWebhookSettingsRepository(configuredSettings()))

    viewModel.setAnalysisIntegration(AnalysisIntegration.CUSTOM_WEBHOOK)
    advanceUntilIdle()

    assertEquals(AnalysisIntegration.CUSTOM_WEBHOOK, integrationRepository.current)
    assertFalse(viewModel.webhookSetupRequested.value)
  }

  @Test
  fun `Webhook未設定でCustom Webhookを選ぶと設定画面を要求する`() = runTest(dispatcher) {
    val integrationRepository = FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE)
    val viewModel = SettingsViewModel(integrationRepository, FakeWebhookSettingsRepository())
    val collection = collectState(viewModel)
    runCurrent()

    viewModel.setAnalysisIntegration(AnalysisIntegration.CUSTOM_WEBHOOK)
    advanceUntilIdle()

    assertEquals(AnalysisIntegration.NONE, integrationRepository.current)
    assertTrue(viewModel.webhookSetupRequested.value)
    assertEquals(AnalysisIntegration.CUSTOM_WEBHOOK, viewModel.selectedAnalysisIntegration.value)
    collection.cancel()
  }

  @Test
  fun `Custom Webhook有効時だけ親Settingsへ安全な送信先を出す`() = runTest(dispatcher) {
    val integrationRepository = FakeAnalysisIntegrationRepository(AnalysisIntegration.CUSTOM_WEBHOOK)
    val webhookRepository = FakeWebhookSettingsRepository(
      WebhookSettings(
        url = "https://hooks.example.com/path?token=secret",
        headers = emptyList(),
        bodyTemplate = "{}",
      ),
    )
    val viewModel = SettingsViewModel(integrationRepository, webhookRepository)
    val collection = collectState(viewModel)
    advanceUntilIdle()

    assertEquals("https://hooks.example.com", viewModel.webhookDestinationLabel.value)

    viewModel.setAnalysisIntegration(AnalysisIntegration.NONE)
    advanceUntilIdle()

    assertNull(viewModel.webhookDestinationLabel.value)
    collection.cancel()
  }

  @Test
  fun `Webhook設定画面を開くと保存済み設定を読み込む`() = runTest(dispatcher) {
    val saved = configuredSettings()
    val viewModel = SettingsViewModel(
      FakeAnalysisIntegrationRepository(AnalysisIntegration.CUSTOM_WEBHOOK),
      FakeWebhookSettingsRepository(saved),
    )
    val collection = collectState(viewModel)

    viewModel.onWebhookSettingsScreenOpened()
    advanceUntilIdle()

    assertEquals(WebhookSettingsLoadState.READY, viewModel.webhookSettingsLoadState.value)
    assertEquals(saved.url, viewModel.webhookFormState.value.url)
    assertEquals(saved.headers, viewModel.webhookFormState.value.headers)
    assertEquals(saved.bodyTemplate, viewModel.webhookFormState.value.bodyTemplate)
    collection.cancel()
  }

  @Test
  fun `Webhook設定を読めない間はUnavailableにする`() = runTest(dispatcher) {
    val viewModel = SettingsViewModel(
      FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE),
      FakeWebhookSettingsRepository(initialState = WebhookSettingsState.Unavailable),
    )
    val collection = collectState(viewModel)
    advanceUntilIdle()

    assertEquals(WebhookSettingsLoadState.UNAVAILABLE, viewModel.webhookSettingsLoadState.value)
    collection.cancel()
  }

  @Test
  fun `Webhook設定の保存成功後だけCustom Webhookを有効化する`() = runTest(dispatcher) {
    val integrationRepository = FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE)
    val webhookRepository = FakeWebhookSettingsRepository()
    val viewModel = SettingsViewModel(integrationRepository, webhookRepository)
    fillValidForm(viewModel)

    viewModel.saveWebhookSettings()
    advanceUntilIdle()

    assertEquals(1, webhookRepository.saveCallCount)
    assertEquals(AnalysisIntegration.CUSTOM_WEBHOOK, integrationRepository.current)
    assertEquals(WebhookSaveResult.SUCCEEDED, viewModel.webhookSaveResult.value)
  }

  @Test
  fun `Webhook設定の保存に成功するとSUCCEEDEDになりconsumeでnullへ戻る`() = runTest(dispatcher) {
    val viewModel = SettingsViewModel(
      FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE),
      FakeWebhookSettingsRepository(),
    )
    fillValidForm(viewModel)

    viewModel.saveWebhookSettings()
    advanceUntilIdle()

    assertEquals(WebhookSaveResult.SUCCEEDED, viewModel.webhookSaveResult.value)

    viewModel.consumeWebhookSaveResult()

    assertNull(viewModel.webhookSaveResult.value)
  }

  @Test
  fun `Webhook設定は保存できてもCustom Webhook有効化に失敗したらACTIVATION_FAILEDにする`() = runTest(dispatcher) {
    val integrationRepository = FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE, failNextSets = 1)
    val webhookRepository = FakeWebhookSettingsRepository()
    val viewModel = SettingsViewModel(integrationRepository, webhookRepository)
    fillValidForm(viewModel)

    viewModel.saveWebhookSettings()
    advanceUntilIdle()

    assertEquals(1, webhookRepository.saveCallCount)
    assertEquals(AnalysisIntegration.NONE, integrationRepository.current)
    assertEquals(WebhookSaveResult.ACTIVATION_FAILED, viewModel.webhookSaveResult.value)
    // Webhook画面のSnackbarで伝えるので、親Settingsのエラー表示は二重に出さない。
    assertFalse(viewModel.integrationSaveFailed.value)
  }

  @Test
  fun `Unavailableから復旧して既存設定を読み込んでも有効化に失敗したらACTIVATION_FAILEDにする`() = runTest(dispatcher) {
    val integrationRepository = FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE, failNextSets = 1)
    val webhookRepository = FakeWebhookSettingsRepository(initialState = WebhookSettingsState.Unavailable)
    val viewModel = SettingsViewModel(integrationRepository, webhookRepository)
    val collection = collectState(viewModel)

    viewModel.setAnalysisIntegration(AnalysisIntegration.CUSTOM_WEBHOOK)
    advanceUntilIdle()
    assertTrue(viewModel.webhookSetupRequested.value)

    webhookRepository.emit(WebhookSettingsState.Configured(configuredSettings()))
    viewModel.onWebhookSettingsScreenOpened()
    advanceUntilIdle()

    assertEquals(AnalysisIntegration.NONE, integrationRepository.current)
    assertEquals(WebhookSaveResult.ACTIVATION_FAILED, viewModel.webhookSaveResult.value)
    assertFalse(viewModel.integrationSaveFailed.value)

    viewModel.consumeWebhookSaveResult()
    assertNull(viewModel.webhookSaveResult.value)
    collection.cancel()
  }

  @Test
  fun `Webhook設定の保存失敗時はFAILEDになる`() = runTest(dispatcher) {
    val viewModel = SettingsViewModel(
      FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE),
      FakeWebhookSettingsRepository(failNextSaves = 1),
    )
    fillValidForm(viewModel)

    viewModel.saveWebhookSettings()
    advanceUntilIdle()

    assertEquals(WebhookSaveResult.FAILED, viewModel.webhookSaveResult.value)
  }

  @Test
  fun `Webhook設定の保存失敗時はCustom Webhookを有効化しない`() = runTest(dispatcher) {
    val integrationRepository = FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE)
    val webhookRepository = FakeWebhookSettingsRepository(failNextSaves = 1)
    val viewModel = SettingsViewModel(integrationRepository, webhookRepository)
    fillValidForm(viewModel)

    viewModel.saveWebhookSettings()
    advanceUntilIdle()

    assertEquals(AnalysisIntegration.NONE, integrationRepository.current)
    assertEquals(WebhookSaveResult.FAILED, viewModel.webhookSaveResult.value)
  }

  @Test
  fun `validation失敗時はWebhook設定を保存しない`() = runTest(dispatcher) {
    val webhookRepository = FakeWebhookSettingsRepository()
    val viewModel = SettingsViewModel(FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE), webhookRepository)
    viewModel.updateWebhookUrl("not a url")

    viewModel.saveWebhookSettings()
    advanceUntilIdle()

    assertEquals(0, webhookRepository.saveCallCount)
    assertFalse(viewModel.webhookValidation.value.isEmpty)
  }

  @Test
  fun `URLを直すと保存前でもURLのvalidation errorだけ消える`() = runTest(dispatcher) {
    val viewModel = SettingsViewModel(
      FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE),
      FakeWebhookSettingsRepository(),
    )
    viewModel.updateWebhookUrl("not a url")
    viewModel.updateWebhookBodyTemplate("{ not json")
    viewModel.saveWebhookSettings()
    advanceUntilIdle()
    assertTrue(viewModel.webhookValidation.value.all.contains(WebhookSettingsValidator.ValidationError.INVALID_URL))

    viewModel.updateWebhookUrl("https://hooks.example.com/webhook")

    assertFalse(viewModel.webhookValidation.value.all.contains(WebhookSettingsValidator.ValidationError.INVALID_URL))
    assertTrue(viewModel.webhookValidation.value.all.contains(WebhookSettingsValidator.ValidationError.INVALID_BODY_TEMPLATE))
  }

  @Test
  fun `ヘッダーを直すとヘッダーのvalidation errorがindexごと消える`() = runTest(dispatcher) {
    val viewModel = SettingsViewModel(
      FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE),
      FakeWebhookSettingsRepository(),
    )
    viewModel.updateWebhookUrl("https://hooks.example.com/webhook")
    viewModel.addWebhookHeader()
    viewModel.saveWebhookSettings()
    advanceUntilIdle()
    assertEquals(setOf(0), viewModel.webhookValidation.value.headerErrors.keys)
    assertTrue(viewModel.webhookValidation.value.all.contains(WebhookSettingsValidator.ValidationError.BLANK_HEADER_NAME))

    viewModel.updateWebhookHeaderName(0, "Authorization")

    assertTrue(viewModel.webhookValidation.value.isEmpty)
    assertTrue(viewModel.webhookValidation.value.headerErrors.isEmpty())
  }

  @Test
  fun `リクエスト本文を直すと本文のvalidation errorが消える`() = runTest(dispatcher) {
    val viewModel = SettingsViewModel(
      FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE),
      FakeWebhookSettingsRepository(),
    )
    viewModel.updateWebhookUrl("https://hooks.example.com/webhook")
    viewModel.updateWebhookBodyTemplate("{ not json")
    viewModel.saveWebhookSettings()
    advanceUntilIdle()
    assertTrue(viewModel.webhookValidation.value.all.contains(WebhookSettingsValidator.ValidationError.INVALID_BODY_TEMPLATE))

    viewModel.resetWebhookBodyTemplate()

    assertTrue(viewModel.webhookValidation.value.isEmpty)
  }

  @Test
  fun `新規設定フォームは初期Body templateから始まる`() = runTest(dispatcher) {
    val viewModel = SettingsViewModel(
      FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE),
      FakeWebhookSettingsRepository(),
    )
    val collection = collectState(viewModel)

    viewModel.onWebhookSettingsScreenOpened()
    advanceUntilIdle()

    assertEquals(WebhookBodyTemplateRenderer.DEFAULT_TEMPLATE, viewModel.webhookFormState.value.bodyTemplate)
    collection.cancel()
  }

  @Test
  fun `resetWebhookBodyTemplateで初期値へ戻る`() = runTest(dispatcher) {
    val viewModel = SettingsViewModel(
      FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE),
      FakeWebhookSettingsRepository(),
    )
    viewModel.updateWebhookBodyTemplate("編集中のテンプレート")

    viewModel.resetWebhookBodyTemplate()

    assertEquals(WebhookBodyTemplateRenderer.DEFAULT_TEMPLATE, viewModel.webhookFormState.value.bodyTemplate)
  }

  @Test
  fun `Body templateが有効なJSONにならないと保存しない`() = runTest(dispatcher) {
    val webhookRepository = FakeWebhookSettingsRepository()
    val viewModel = SettingsViewModel(FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE), webhookRepository)
    viewModel.updateWebhookUrl("https://hooks.example.com/webhook")
    viewModel.updateWebhookBodyTemplate("{ not json")

    viewModel.saveWebhookSettings()
    advanceUntilIdle()

    assertEquals(0, webhookRepository.saveCallCount)
    assertTrue(
      viewModel.webhookValidation.value.all.contains(
        WebhookSettingsValidator.ValidationError.INVALID_BODY_TEMPLATE,
      ),
    )
  }

  @Test
  fun `保存せずWebhook設定画面を閉じると保存済み設定は変わらない`() = runTest(dispatcher) {
    val saved = configuredSettings()
    val webhookRepository = FakeWebhookSettingsRepository(saved)
    val viewModel = SettingsViewModel(
      FakeAnalysisIntegrationRepository(AnalysisIntegration.CUSTOM_WEBHOOK),
      webhookRepository,
    )
    val collection = collectState(viewModel)
    viewModel.onWebhookSettingsScreenOpened()
    advanceUntilIdle()

    viewModel.updateWebhookUrl("https://edited.example.com")
    viewModel.onWebhookSettingsScreenClosed()

    assertEquals(saved, webhookRepository.savedSettings)
    assertEquals(WebhookFormState(), viewModel.webhookFormState.value)
    collection.cancel()
  }

  @Test
  fun `初期化済み画面でensureを再度呼んでも入力中フォームを巻き戻さない`() = runTest(dispatcher) {
    val viewModel = SettingsViewModel(
      FakeAnalysisIntegrationRepository(AnalysisIntegration.CUSTOM_WEBHOOK),
      FakeWebhookSettingsRepository(configuredSettings()),
    )
    val collection = collectState(viewModel)
    viewModel.ensureWebhookSettingsScreenOpened()
    advanceUntilIdle()
    viewModel.updateWebhookUrl("https://edited.example.com")

    viewModel.ensureWebhookSettingsScreenOpened()
    advanceUntilIdle()

    assertEquals("https://edited.example.com", viewModel.webhookFormState.value.url)
    collection.cancel()
  }

  @Test
  fun `Settings再入場後は古いCustom Webhook判定が設定画面要求を出さない`() = runTest(dispatcher) {
    val webhookRepository = GatedReadWebhookSettingsRepository(WebhookSettingsState.NotConfigured)
    val viewModel = SettingsViewModel(FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE), webhookRepository)

    viewModel.setAnalysisIntegration(AnalysisIntegration.CUSTOM_WEBHOOK)
    runCurrent()
    viewModel.onSettingsOpened()
    webhookRepository.release()
    advanceUntilIdle()

    assertFalse(viewModel.webhookSetupRequested.value)
  }

  @Test
  fun `Webhook保存中に画面を離れると後から完了した保存で有効化しない`() = runTest(dispatcher) {
    val integrationRepository = FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE)
    val webhookRepository = GatedSaveWebhookSettingsRepository()
    val viewModel = SettingsViewModel(integrationRepository, webhookRepository)
    fillValidForm(viewModel)

    viewModel.saveWebhookSettings()
    runCurrent()
    viewModel.onWebhookSettingsScreenClosed()
    webhookRepository.release()
    advanceUntilIdle()

    assertEquals(AnalysisIntegration.NONE, integrationRepository.current)
  }

  private fun TestScope.collectState(viewModel: SettingsViewModel): Job = backgroundScope.launch {
    launch { viewModel.analysisIntegration.collect {} }
    launch { viewModel.selectedAnalysisIntegration.collect {} }
    launch { viewModel.webhookDestinationLabel.collect {} }
    launch { viewModel.webhookSettingsLoadState.collect {} }
  }

  private fun fillValidForm(viewModel: SettingsViewModel) {
    viewModel.updateWebhookUrl("https://hooks.example.com/webhook")
    viewModel.addWebhookHeader()
    viewModel.updateWebhookHeaderName(0, "Authorization")
    viewModel.updateWebhookHeaderValue(0, "Bearer secret")
  }

  private fun configuredSettings() = WebhookSettings(
    url = "https://hooks.example.com/webhook",
    headers = listOf(WebhookHeader("Authorization", "Bearer secret")),
    bodyTemplate = """{"period":{"start":"{{periodStart}}","end":"{{periodEnd}}"},"entries":{{entries}}}""",
  )

  private class FakeAnalysisIntegrationRepository(
    initial: AnalysisIntegration,
    private var failNextSets: Int = 0,
  ) : AnalysisIntegrationRepository {
    private val state = MutableStateFlow(initial)
    override val analysisIntegration: Flow<AnalysisIntegration> = state
    val current: AnalysisIntegration get() = state.value

    override suspend fun setAnalysisIntegration(integration: AnalysisIntegration) {
      if (failNextSets > 0) {
        failNextSets--
        throw IOException("analysis integration write failed")
      }
      state.value = integration
    }
  }

  private class GatedAnalysisIntegrationRepository(private val resolved: AnalysisIntegration) : AnalysisIntegrationRepository {
    private val gate = CompletableDeferred<Unit>()
    override val analysisIntegration: Flow<AnalysisIntegration> = flow {
      gate.await()
      emit(resolved)
    }

    fun release() {
      gate.complete(Unit)
    }

    override suspend fun setAnalysisIntegration(integration: AnalysisIntegration) = error("not used in this test")
  }

  private class FakeWebhookSettingsRepository(
    initial: WebhookSettings? = null,
    initialState: WebhookSettingsState? = null,
    private var failNextSaves: Int = 0,
  ) : WebhookSettingsRepository {
    private val state = MutableStateFlow(
      initialState ?: initial?.let { WebhookSettingsState.Configured(it) } ?: WebhookSettingsState.NotConfigured,
    )
    override val settings: Flow<WebhookSettingsState> = state
    var saveCallCount = 0
      private set
    var savedSettings: WebhookSettings? = initial
      private set

    fun emit(newState: WebhookSettingsState) {
      state.value = newState
    }

    override suspend fun save(settings: WebhookSettings) {
      saveCallCount++
      if (failNextSaves > 0) {
        failNextSaves--
        throw IOException("save failed")
      }
      savedSettings = settings
      state.value = WebhookSettingsState.Configured(settings)
    }
  }

  private class GatedReadWebhookSettingsRepository(private val resolved: WebhookSettingsState) : WebhookSettingsRepository {
    private val gate = CompletableDeferred<Unit>()
    override val settings: Flow<WebhookSettingsState> = flow {
      gate.await()
      emit(resolved)
    }

    fun release() {
      gate.complete(Unit)
    }

    override suspend fun save(settings: WebhookSettings) = error("not used in this test")
  }

  private class GatedSaveWebhookSettingsRepository : WebhookSettingsRepository {
    private val state = MutableStateFlow<WebhookSettingsState>(WebhookSettingsState.NotConfigured)
    private val gate = CompletableDeferred<Unit>()
    override val settings: Flow<WebhookSettingsState> = state

    override suspend fun save(settings: WebhookSettings) {
      gate.await()
      state.value = WebhookSettingsState.Configured(settings)
    }

    fun release() {
      gate.complete(Unit)
    }
  }

}
