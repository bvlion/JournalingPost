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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WebhookSettingsViewModelTest {
  private val dispatcher = StandardTestDispatcher()

  // Channel由来のsaveResultsは購読者がいる間だけ流れるため、テスト中はこのscopeで購読し続ける。
  private val collectorScope = CoroutineScope(dispatcher)

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
  }

  @After
  fun tearDown() {
    collectorScope.cancel()
    Dispatchers.resetMain()
  }

  @Test
  fun `画面を開くと保存済み設定を読み込む`() = runTest(dispatcher) {
    val saved = configuredSettings()
    val viewModel = createViewModel(webhookRepository = FakeWebhookSettingsRepository(saved))

    viewModel.onScreenOpened(activatePendingSelection = false)
    advanceUntilIdle()

    assertEquals(WebhookSettingsLoadState.READY, viewModel.uiState.value.loadState)
    assertEquals(saved.url, viewModel.uiState.value.form.url)
    assertEquals(saved.headers, viewModel.uiState.value.form.headers)
    assertEquals(saved.bodyTemplate, viewModel.uiState.value.form.bodyTemplate)
  }

  @Test
  fun `Webhook設定を読めない間はUnavailableにする`() = runTest(dispatcher) {
    val viewModel = createViewModel(
      webhookRepository = FakeWebhookSettingsRepository(initialState = WebhookSettingsState.Unavailable),
    )

    viewModel.onScreenOpened(activatePendingSelection = false)
    advanceUntilIdle()

    assertEquals(WebhookSettingsLoadState.UNAVAILABLE, viewModel.uiState.value.loadState)
  }

  @Test
  fun `新規設定フォームは初期Body templateから始まる`() = runTest(dispatcher) {
    val viewModel = createViewModel()

    viewModel.onScreenOpened(activatePendingSelection = false)
    advanceUntilIdle()

    assertEquals(WebhookBodyTemplateRenderer.DEFAULT_TEMPLATE, viewModel.uiState.value.form.bodyTemplate)
  }

  @Test
  fun `Webhook設定の保存成功後だけCustom Webhookを有効化する`() = runTest(dispatcher) {
    val integrationRepository = FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE)
    val webhookRepository = FakeWebhookSettingsRepository()
    val viewModel = createViewModel(webhookRepository, integrationRepository)
    val results = collectSaveResults(viewModel)
    fillValidForm(viewModel)

    viewModel.save()
    advanceUntilIdle()

    assertEquals(1, webhookRepository.saveCallCount)
    assertEquals(AnalysisIntegration.CUSTOM_WEBHOOK, integrationRepository.current)
    assertEquals(listOf(WebhookSaveResult.SUCCEEDED), results)
  }

  @Test
  fun `Webhook設定は保存できてもCustom Webhook有効化に失敗したらACTIVATION_FAILEDにする`() = runTest(dispatcher) {
    val integrationRepository = FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE, failNextSets = 1)
    val webhookRepository = FakeWebhookSettingsRepository()
    val viewModel = createViewModel(webhookRepository, integrationRepository)
    val results = collectSaveResults(viewModel)
    fillValidForm(viewModel)

    viewModel.save()
    advanceUntilIdle()

    assertEquals(1, webhookRepository.saveCallCount)
    assertEquals(AnalysisIntegration.NONE, integrationRepository.current)
    assertEquals(listOf(WebhookSaveResult.ACTIVATION_FAILED), results)
  }

  @Test
  fun `Unavailableから復旧して既存設定を読み込んでも有効化に失敗したらACTIVATION_FAILEDにする`() = runTest(dispatcher) {
    val integrationRepository = FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE, failNextSets = 1)
    val webhookRepository = FakeWebhookSettingsRepository(initialState = WebhookSettingsState.Unavailable)
    val viewModel = createViewModel(webhookRepository, integrationRepository)
    val results = collectSaveResults(viewModel)

    viewModel.onScreenOpened(activatePendingSelection = true)
    advanceUntilIdle()
    assertEquals(WebhookSettingsLoadState.UNAVAILABLE, viewModel.uiState.value.loadState)

    webhookRepository.emit(WebhookSettingsState.Configured(configuredSettings()))
    advanceUntilIdle()

    assertEquals(AnalysisIntegration.NONE, integrationRepository.current)
    assertEquals(listOf(WebhookSaveResult.ACTIVATION_FAILED), results)
  }

  @Test
  fun `Unavailableから復旧して既存設定が見つかれば再保存せずCustom Webhookを有効化する`() = runTest(dispatcher) {
    val integrationRepository = FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE)
    val webhookRepository = FakeWebhookSettingsRepository(initialState = WebhookSettingsState.Unavailable)
    val viewModel = createViewModel(webhookRepository, integrationRepository)
    val results = collectSaveResults(viewModel)

    viewModel.onScreenOpened(activatePendingSelection = true)
    advanceUntilIdle()
    webhookRepository.emit(WebhookSettingsState.Configured(configuredSettings()))
    advanceUntilIdle()

    assertEquals(0, webhookRepository.saveCallCount)
    assertEquals(AnalysisIntegration.CUSTOM_WEBHOOK, integrationRepository.current)
    assertTrue(results.isEmpty())
  }

  @Test
  fun `利用者が自分で開いた場合は既存設定を読み込んでも有効化しない`() = runTest(dispatcher) {
    val integrationRepository = FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE)
    val viewModel = createViewModel(
      FakeWebhookSettingsRepository(configuredSettings()),
      integrationRepository,
    )

    viewModel.onScreenOpened(activatePendingSelection = false)
    advanceUntilIdle()

    assertEquals(AnalysisIntegration.NONE, integrationRepository.current)
  }

  @Test
  fun `Webhook設定の保存失敗時はFAILEDになりCustom Webhookを有効化しない`() = runTest(dispatcher) {
    val integrationRepository = FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE)
    val viewModel = createViewModel(FakeWebhookSettingsRepository(failNextSaves = 1), integrationRepository)
    val results = collectSaveResults(viewModel)
    fillValidForm(viewModel)

    viewModel.save()
    advanceUntilIdle()

    assertEquals(AnalysisIntegration.NONE, integrationRepository.current)
    assertEquals(listOf(WebhookSaveResult.FAILED), results)
  }

  @Test
  fun `validation失敗時はWebhook設定を保存しない`() = runTest(dispatcher) {
    val webhookRepository = FakeWebhookSettingsRepository()
    val viewModel = createViewModel(webhookRepository)
    viewModel.updateUrl("not a url")

    viewModel.save()
    advanceUntilIdle()

    assertEquals(0, webhookRepository.saveCallCount)
    assertFalse(viewModel.uiState.value.validation.isEmpty)
  }

  @Test
  fun `Body templateが有効なJSONにならないと保存しない`() = runTest(dispatcher) {
    val webhookRepository = FakeWebhookSettingsRepository()
    val viewModel = createViewModel(webhookRepository)
    viewModel.updateUrl("https://hooks.example.com/webhook")
    viewModel.updateBodyTemplate("{ not json")

    viewModel.save()
    advanceUntilIdle()

    assertEquals(0, webhookRepository.saveCallCount)
    assertTrue(
      viewModel.uiState.value.validation.all.contains(
        WebhookSettingsValidator.ValidationError.INVALID_BODY_TEMPLATE,
      ),
    )
  }

  @Test
  fun `URLを直すと保存前でもURLのvalidation errorだけ消える`() = runTest(dispatcher) {
    val viewModel = createViewModel()
    viewModel.updateUrl("not a url")
    viewModel.updateBodyTemplate("{ not json")
    viewModel.save()
    advanceUntilIdle()
    assertTrue(viewModel.uiState.value.validation.all.contains(WebhookSettingsValidator.ValidationError.INVALID_URL))

    viewModel.updateUrl("https://hooks.example.com/webhook")

    val validation = viewModel.uiState.value.validation
    assertFalse(validation.all.contains(WebhookSettingsValidator.ValidationError.INVALID_URL))
    assertTrue(validation.all.contains(WebhookSettingsValidator.ValidationError.INVALID_BODY_TEMPLATE))
  }

  @Test
  fun `ヘッダーを直すとその行のvalidation errorだけ消える`() = runTest(dispatcher) {
    val viewModel = createViewModel()
    viewModel.updateUrl("https://hooks.example.com/webhook")
    viewModel.addHeader()
    viewModel.save()
    advanceUntilIdle()
    assertEquals(setOf(0), viewModel.uiState.value.validation.headerErrors.keys)

    viewModel.updateHeaderName(0, "Authorization")

    assertTrue(viewModel.uiState.value.validation.isEmpty)
    assertTrue(viewModel.uiState.value.validation.headerErrors.isEmpty())
  }

  @Test
  fun `複数ヘッダーで別々のerrorがある状態で1行だけ直すと未修正行のerrorは残る`() = runTest(dispatcher) {
    val viewModel = newViewModelWithHeaderErrors(
      WebhookHeader("", "v"),
      WebhookHeader("X:bad", "v"),
    )

    assertEquals(
      mapOf(
        0 to listOf(WebhookSettingsValidator.ValidationError.BLANK_HEADER_NAME),
        1 to listOf(WebhookSettingsValidator.ValidationError.INVALID_HEADER_SYNTAX),
      ),
      viewModel.uiState.value.validation.headerErrors,
    )

    viewModel.updateHeaderName(0, "Authorization")

    assertEquals(
      mapOf(1 to listOf(WebhookSettingsValidator.ValidationError.INVALID_HEADER_SYNTAX)),
      viewModel.uiState.value.validation.headerErrors,
    )
  }

  @Test
  fun `Header名の重複errorが出ている状態でvalueだけ変えても重複errorは消えない`() = runTest(dispatcher) {
    val viewModel = newViewModelWithHeaderErrors(
      WebhookHeader("X-Key", "a"),
      WebhookHeader("x-key", "b"),
    )
    assertEquals(setOf(0, 1), viewModel.uiState.value.validation.headerErrors.keys)

    viewModel.updateHeaderValue(0, "changed")

    assertEquals(setOf(0, 1), viewModel.uiState.value.validation.headerErrors.keys)
    assertTrue(
      viewModel.uiState.value.validation.all.contains(
        WebhookSettingsValidator.ValidationError.DUPLICATE_HEADER_NAME,
      ),
    )
  }

  @Test
  fun `Header名を直して重複が解消されると関係した各行の重複errorも解消される`() = runTest(dispatcher) {
    val viewModel = newViewModelWithHeaderErrors(
      WebhookHeader("X-Key", "a"),
      WebhookHeader("x-key", "b"),
    )

    viewModel.updateHeaderName(1, "X-Other")

    assertTrue(viewModel.uiState.value.validation.isEmpty)
    assertTrue(viewModel.uiState.value.validation.headerErrors.isEmpty())
  }

  @Test
  fun `Headerを削除しても残った行のvalidation表示が別行へずれない`() = runTest(dispatcher) {
    val viewModel = newViewModelWithHeaderErrors(
      WebhookHeader("Authorization", "v"),
      WebhookHeader("", "v"),
    )
    assertEquals(setOf(1), viewModel.uiState.value.validation.headerErrors.keys)

    viewModel.removeHeader(0)

    assertEquals(
      mapOf(0 to listOf(WebhookSettingsValidator.ValidationError.BLANK_HEADER_NAME)),
      viewModel.uiState.value.validation.headerErrors,
    )
  }

  @Test
  fun `header errorが出ていない状態でヘッダーを編集しても先回りでerrorを出さない`() = runTest(dispatcher) {
    val viewModel = createViewModel()

    viewModel.addHeader()
    viewModel.updateHeaderName(0, "")
    viewModel.updateHeaderValue(0, "v")

    assertTrue(viewModel.uiState.value.validation.isEmpty)
    assertTrue(viewModel.uiState.value.validation.headerErrors.isEmpty())
  }

  @Test
  fun `リクエスト本文を直すと本文のvalidation errorが消える`() = runTest(dispatcher) {
    val viewModel = createViewModel()
    viewModel.updateUrl("https://hooks.example.com/webhook")
    viewModel.updateBodyTemplate("{ not json")
    viewModel.save()
    advanceUntilIdle()
    assertTrue(
      viewModel.uiState.value.validation.all.contains(
        WebhookSettingsValidator.ValidationError.INVALID_BODY_TEMPLATE,
      ),
    )

    viewModel.resetBodyTemplate()

    assertTrue(viewModel.uiState.value.validation.isEmpty)
  }

  @Test
  fun `resetBodyTemplateで初期値へ戻る`() = runTest(dispatcher) {
    val viewModel = createViewModel()
    viewModel.updateBodyTemplate("編集中のテンプレート")

    viewModel.resetBodyTemplate()

    assertEquals(WebhookBodyTemplateRenderer.DEFAULT_TEMPLATE, viewModel.uiState.value.form.bodyTemplate)
  }

  @Test
  fun `保存せず画面を閉じると保存済み設定は変わらない`() = runTest(dispatcher) {
    val saved = configuredSettings()
    val webhookRepository = FakeWebhookSettingsRepository(saved)
    val viewModel = createViewModel(webhookRepository)
    viewModel.onScreenOpened(activatePendingSelection = false)
    advanceUntilIdle()

    viewModel.updateUrl("https://edited.example.com")
    viewModel.onScreenClosed()

    assertEquals(saved, webhookRepository.savedSettings)
    assertEquals(WebhookSettingsUiState(), viewModel.uiState.value)
  }

  @Test
  fun `初期化済み画面でensureを再度呼んでも入力中フォームを巻き戻さない`() = runTest(dispatcher) {
    val viewModel = createViewModel(FakeWebhookSettingsRepository(configuredSettings()))
    viewModel.ensureScreenOpened(activatePendingSelection = false)
    advanceUntilIdle()
    viewModel.updateUrl("https://edited.example.com")

    viewModel.ensureScreenOpened(activatePendingSelection = false)
    advanceUntilIdle()

    assertEquals("https://edited.example.com", viewModel.uiState.value.form.url)
  }

  @Test
  fun `Webhook保存中に画面を離れると保存は完了させるが有効化はしない`() = runTest(dispatcher) {
    val integrationRepository = FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE)
    val webhookRepository = GatedSaveWebhookSettingsRepository()
    val viewModel = createViewModel(webhookRepository, integrationRepository)
    val results = collectSaveResults(viewModel)
    fillValidForm(viewModel)

    viewModel.save()
    runCurrent()
    viewModel.onScreenClosed()
    webhookRepository.release()
    advanceUntilIdle()

    assertEquals(1, webhookRepository.saveCallCount)
    assertEquals(AnalysisIntegration.NONE, integrationRepository.current)
    assertTrue(results.isEmpty())
  }

  private fun createViewModel(
    webhookRepository: WebhookSettingsRepository = FakeWebhookSettingsRepository(),
    integrationRepository: AnalysisIntegrationRepository =
      FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE),
  ) = WebhookSettingsViewModel(webhookRepository, integrationRepository)

  private fun collectSaveResults(viewModel: WebhookSettingsViewModel): List<WebhookSaveResult> {
    val results = mutableListOf<WebhookSaveResult>()
    collectorScope.launch { viewModel.saveResults.collect { results += it } }
    return results
  }

  private fun TestScope.newViewModelWithHeaderErrors(vararg headers: WebhookHeader): WebhookSettingsViewModel {
    val viewModel = createViewModel()
    viewModel.updateUrl("https://hooks.example.com/webhook")
    headers.forEachIndexed { index, header ->
      viewModel.addHeader()
      viewModel.updateHeaderName(index, header.name)
      viewModel.updateHeaderValue(index, header.value)
    }
    viewModel.save()
    advanceUntilIdle()
    return viewModel
  }

  private fun fillValidForm(viewModel: WebhookSettingsViewModel) {
    viewModel.updateUrl("https://hooks.example.com/webhook")
    viewModel.addHeader()
    viewModel.updateHeaderName(0, "Authorization")
    viewModel.updateHeaderValue(0, "Bearer secret")
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

  private class GatedSaveWebhookSettingsRepository : WebhookSettingsRepository {
    private val state = MutableStateFlow<WebhookSettingsState>(WebhookSettingsState.NotConfigured)
    private val gate = CompletableDeferred<Unit>()
    override val settings: Flow<WebhookSettingsState> = state
    var saveCallCount = 0
      private set

    override suspend fun save(settings: WebhookSettings) {
      saveCallCount++
      gate.await()
      state.value = WebhookSettingsState.Configured(settings)
    }

    fun release() {
      gate.complete(Unit)
    }
  }
}
