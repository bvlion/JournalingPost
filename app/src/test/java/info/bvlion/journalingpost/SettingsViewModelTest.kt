package info.bvlion.journalingpost

import info.bvlion.journalingpost.debug.DebugFixtureSeeder
import info.bvlion.journalingpost.journal.JournalEntry
import info.bvlion.journalingpost.mood.Mood
import info.bvlion.journalingpost.settings.AnalysisIntegration
import info.bvlion.journalingpost.settings.AnalysisIntegrationRepository
import info.bvlion.journalingpost.settings.NoteOnlyEntryRepository
import java.time.Instant
import java.time.ZoneOffset
import info.bvlion.journalingpost.webhook.WebhookHeader
import info.bvlion.journalingpost.webhook.WebhookSettings
import info.bvlion.journalingpost.webhook.WebhookSettingsRepository
import info.bvlion.journalingpost.webhook.WebhookSettingsState
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
  private val dispatcher = StandardTestDispatcher()

  // WhileSubscribedなStateFlowとChannel由来のeventは購読者がいる間だけ流れるため、テスト中は
  // このscopeで購読し続ける。
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
  fun `解析連携は読み込み確定まで未選択として扱う`() = runTest(dispatcher) {
    val integrationRepository = GatedAnalysisIntegrationRepository(AnalysisIntegration.CUSTOM_WEBHOOK)
    val viewModel = createViewModel(integrationRepository, FakeWebhookSettingsRepository(configuredSettings()))
    collectUiState(viewModel)
    runCurrent()

    assertNull(viewModel.uiState.value.selectedIntegration)

    integrationRepository.release()
    advanceUntilIdle()

    assertEquals(AnalysisIntegration.CUSTOM_WEBHOOK, viewModel.uiState.value.selectedIntegration)
  }

  @Test
  fun `使用しないを選ぶとNONEを保存する`() = runTest(dispatcher) {
    val integrationRepository = FakeAnalysisIntegrationRepository(AnalysisIntegration.CUSTOM_WEBHOOK)
    val viewModel = createViewModel(integrationRepository, FakeWebhookSettingsRepository(configuredSettings()))
    val events = collectEvents(viewModel)

    viewModel.setAnalysisIntegration(AnalysisIntegration.NONE)
    advanceUntilIdle()

    assertEquals(AnalysisIntegration.NONE, integrationRepository.current)
    assertTrue(events.isEmpty())
  }

  @Test
  fun `設定済みCustom Webhookを選ぶとその場で有効化する`() = runTest(dispatcher) {
    val integrationRepository = FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE)
    val viewModel = createViewModel(integrationRepository, FakeWebhookSettingsRepository(configuredSettings()))
    val events = collectEvents(viewModel)

    viewModel.setAnalysisIntegration(AnalysisIntegration.CUSTOM_WEBHOOK)
    advanceUntilIdle()

    assertEquals(AnalysisIntegration.CUSTOM_WEBHOOK, integrationRepository.current)
    assertTrue(events.isEmpty())
  }

  @Test
  fun `Webhook未設定でCustom Webhookを選ぶと設定画面を要求する`() = runTest(dispatcher) {
    val integrationRepository = FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE)
    val viewModel = createViewModel(integrationRepository, FakeWebhookSettingsRepository())
    collectUiState(viewModel)
    val events = collectEvents(viewModel)
    runCurrent()

    viewModel.setAnalysisIntegration(AnalysisIntegration.CUSTOM_WEBHOOK)
    advanceUntilIdle()

    assertEquals(AnalysisIntegration.NONE, integrationRepository.current)
    assertEquals(listOf(SettingsEvent.WebhookSetupRequested), events)
    // 保存前でもradioは利用者の選択を示す。
    assertEquals(AnalysisIntegration.CUSTOM_WEBHOOK, viewModel.uiState.value.selectedIntegration)
  }

  @Test
  fun `解析連携の保存に失敗するとIntegrationSaveFailedを1度だけ通知する`() = runTest(dispatcher) {
    val integrationRepository = FakeAnalysisIntegrationRepository(AnalysisIntegration.CUSTOM_WEBHOOK, failNextSets = 1)
    val viewModel = createViewModel(integrationRepository, FakeWebhookSettingsRepository(configuredSettings()))
    val events = collectEvents(viewModel)

    viewModel.setAnalysisIntegration(AnalysisIntegration.NONE)
    advanceUntilIdle()

    assertEquals(listOf(SettingsEvent.IntegrationSaveFailed), events)
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
    val viewModel = createViewModel(integrationRepository, webhookRepository)
    collectUiState(viewModel)
    advanceUntilIdle()

    assertTrue(viewModel.uiState.value.webhookConfigured)
    assertEquals("https://hooks.example.com", viewModel.uiState.value.webhookDestinationLabel)

    viewModel.setAnalysisIntegration(AnalysisIntegration.NONE)
    advanceUntilIdle()

    assertEquals(false, viewModel.uiState.value.webhookConfigured)
    assertNull(viewModel.uiState.value.webhookDestinationLabel)
  }

  @Test
  fun `Settings再入場後は古いCustom Webhook判定が設定画面要求を出さない`() = runTest(dispatcher) {
    val webhookRepository = GatedReadWebhookSettingsRepository(WebhookSettingsState.NotConfigured)
    val viewModel = createViewModel(FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE), webhookRepository)
    val events = collectEvents(viewModel)

    viewModel.setAnalysisIntegration(AnalysisIntegration.CUSTOM_WEBHOOK)
    runCurrent()
    viewModel.onSettingsOpened()
    webhookRepository.release()
    advanceUntilIdle()

    assertTrue(events.isEmpty())
  }

  @Test
  fun `画面へ届かなかった結果はSettings再入場へ持ち越さない`() = runTest(dispatcher) {
    val integrationRepository = FakeAnalysisIntegrationRepository(AnalysisIntegration.CUSTOM_WEBHOOK, failNextSets = 1)
    val viewModel = createViewModel(integrationRepository, FakeWebhookSettingsRepository(configuredSettings()))
    viewModel.setAnalysisIntegration(AnalysisIntegration.NONE)
    advanceUntilIdle()

    viewModel.onSettingsOpened()
    val events = collectEvents(viewModel)
    advanceUntilIdle()

    assertTrue(events.isEmpty())
  }

  @Test
  fun `Hostedを選ぶと保存前に外部送信の同意を要求する`() = runTest(dispatcher) {
    val integrationRepository = FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE)
    val viewModel = createViewModel(integrationRepository, FakeWebhookSettingsRepository())
    collectUiState(viewModel)
    val events = collectEvents(viewModel)

    viewModel.setAnalysisIntegration(AnalysisIntegration.HOSTED)
    advanceUntilIdle()

    assertEquals(listOf(SettingsEvent.HostedConsentRequested), events)
    assertEquals(AnalysisIntegration.NONE, integrationRepository.current)
    // 同意前でもradioは利用者の選択を示す。
    assertEquals(AnalysisIntegration.HOSTED, viewModel.uiState.value.selectedIntegration)
  }

  @Test
  fun `Hostedの同意でHOSTEDを保存する`() = runTest(dispatcher) {
    val integrationRepository = FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE)
    val viewModel = createViewModel(integrationRepository, FakeWebhookSettingsRepository())
    collectUiState(viewModel)

    viewModel.setAnalysisIntegration(AnalysisIntegration.HOSTED)
    advanceUntilIdle()
    viewModel.confirmHostedIntegration()
    advanceUntilIdle()

    assertEquals(AnalysisIntegration.HOSTED, integrationRepository.current)
    assertEquals(AnalysisIntegration.HOSTED, viewModel.uiState.value.selectedIntegration)
  }

  @Test
  fun `Hostedの同意を閉じると保留していた選択表示は解除される`() = runTest(dispatcher) {
    val integrationRepository = FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE)
    val viewModel = createViewModel(integrationRepository, FakeWebhookSettingsRepository())
    collectUiState(viewModel)

    viewModel.setAnalysisIntegration(AnalysisIntegration.HOSTED)
    advanceUntilIdle()
    viewModel.dismissHostedConsent()
    advanceUntilIdle()

    assertEquals(AnalysisIntegration.NONE, integrationRepository.current)
    assertEquals(AnalysisIntegration.NONE, viewModel.uiState.value.selectedIntegration)
  }

  @Test
  fun `Webhook設定画面から戻ると保留していた選択表示は解除される`() = runTest(dispatcher) {
    val integrationRepository = FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE)
    val viewModel = createViewModel(integrationRepository, FakeWebhookSettingsRepository())
    collectUiState(viewModel)
    viewModel.setAnalysisIntegration(AnalysisIntegration.CUSTOM_WEBHOOK)
    advanceUntilIdle()
    assertEquals(AnalysisIntegration.CUSTOM_WEBHOOK, viewModel.uiState.value.selectedIntegration)

    viewModel.onWebhookSettingsClosed()
    advanceUntilIdle()

    assertEquals(AnalysisIntegration.NONE, viewModel.uiState.value.selectedIntegration)
  }

  @Test
  fun `初回案内からの遷移でSettingsを開くとhighlightをtrueにする`() = runTest(dispatcher) {
    val viewModel = createViewModel(
      FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE),
      FakeWebhookSettingsRepository(),
    )
    collectHighlight(viewModel)

    viewModel.onSettingsOpened(highlightAnalysisIntegration = true)
    advanceUntilIdle()

    assertTrue(viewModel.highlightAnalysisIntegration.value)
  }

  @Test
  fun `通常のSettings表示ではhighlightをtrueにしない`() = runTest(dispatcher) {
    val viewModel = createViewModel(
      FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE),
      FakeWebhookSettingsRepository(),
    )
    collectHighlight(viewModel)

    viewModel.onSettingsOpened()
    advanceUntilIdle()

    assertEquals(false, viewModel.highlightAnalysisIntegration.value)
  }

  @Test
  fun `解析連携を選び直すとhighlightを解除する`() = runTest(dispatcher) {
    val viewModel = createViewModel(
      FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE),
      FakeWebhookSettingsRepository(),
    )
    collectHighlight(viewModel)
    viewModel.onSettingsOpened(highlightAnalysisIntegration = true)
    advanceUntilIdle()
    assertTrue(viewModel.highlightAnalysisIntegration.value)

    viewModel.setAnalysisIntegration(AnalysisIntegration.NONE)
    advanceUntilIdle()

    assertEquals(false, viewModel.highlightAnalysisIntegration.value)
  }

  private fun collectHighlight(viewModel: SettingsViewModel) {
    collectorScope.launch { viewModel.highlightAnalysisIntegration.collect {} }
  }

  private fun createViewModel(
    integrationRepository: AnalysisIntegrationRepository,
    webhookRepository: WebhookSettingsRepository,
    noteOnlyEntryRepository: NoteOnlyEntryRepository = FakeNoteOnlyEntryRepository(),
    refreshWidgets: suspend () -> Unit = {},
    debugFixtureSeeder: DebugFixtureSeeder? = null,
  ) = SettingsViewModel(
    analysisIntegrationRepository = integrationRepository,
    webhookSettingsRepository = webhookRepository,
    noteOnlyEntryRepository = noteOnlyEntryRepository,
    refreshWidgets = refreshWidgets,
    debugFixtureSeeder = debugFixtureSeeder,
  )

  @Test
  fun `動作確認用fixtureを投入するとDebugFixturesSeededを通知する`() = runTest(dispatcher) {
    val entries = mutableListOf<JournalEntry>()
    val seeder = DebugFixtureSeeder(
      journalEntryRepository = { entries += it; entries.size.toLong() },
      analysisResultWriter = { 1L },
      isAlreadySeeded = { false },
      markSeeded = {},
      moods = { listOf(Mood(id = "m", emoji = "😀", label = "嬉しい")) },
      zoneId = { ZoneOffset.UTC },
      now = { Instant.parse("2026-09-01T00:00:00Z") },
    )
    val viewModel = createViewModel(
      FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE),
      FakeWebhookSettingsRepository(),
      debugFixtureSeeder = seeder,
    )
    val events = collectEvents(viewModel)

    viewModel.seedDebugFixtures()
    advanceUntilIdle()

    val seeded = events.single() as SettingsEvent.DebugFixturesSeeded
    assertEquals(entries.size, seeded.entryCount)
    assertTrue(seeded.entryCount > 0)
  }

  @Test
  fun `投入済みならDebugFixturesAlreadySeededを通知する`() = runTest(dispatcher) {
    val seeder = DebugFixtureSeeder(
      journalEntryRepository = { error("should not insert") },
      analysisResultWriter = { error("should not save") },
      isAlreadySeeded = { true },
      markSeeded = {},
      moods = { emptyList() },
      zoneId = { ZoneOffset.UTC },
      now = { Instant.parse("2026-09-01T00:00:00Z") },
    )
    val viewModel = createViewModel(
      FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE),
      FakeWebhookSettingsRepository(),
      debugFixtureSeeder = seeder,
    )
    val events = collectEvents(viewModel)

    viewModel.seedDebugFixtures()
    advanceUntilIdle()

    assertEquals(listOf(SettingsEvent.DebugFixturesAlreadySeeded), events)
  }

  @Test
  fun `debugFixtureSeederが無ければseedDebugFixturesは何もしない`() = runTest(dispatcher) {
    val viewModel = createViewModel(
      FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE),
      FakeWebhookSettingsRepository(),
    )
    val events = collectEvents(viewModel)

    viewModel.seedDebugFixtures()
    advanceUntilIdle()

    assertTrue(events.isEmpty())
  }

  @Test
  fun `メモだけ記録を有効にすると保存して配置済みWidgetも更新する`() = runTest(dispatcher) {
    val noteOnlyEntryRepository = FakeNoteOnlyEntryRepository()
    var refreshCount = 0
    val viewModel = createViewModel(
      FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE),
      FakeWebhookSettingsRepository(),
      noteOnlyEntryRepository,
      refreshWidgets = { refreshCount++ },
    )
    collectUiState(viewModel)
    val events = collectEvents(viewModel)

    viewModel.setNoteOnlyEntryEnabled(true)
    advanceUntilIdle()

    assertEquals(true, noteOnlyEntryRepository.current)
    assertEquals(true, viewModel.uiState.value.noteOnlyEntryEnabled)
    assertEquals(1, refreshCount)
    assertTrue(events.isEmpty())
  }

  @Test
  fun `メモだけ記録の保存に失敗するとNoteOnlyEntrySaveFailedを通知しWidgetを更新しない`() = runTest(dispatcher) {
    val noteOnlyEntryRepository = FakeNoteOnlyEntryRepository(failNextSets = 1)
    var refreshCount = 0
    val viewModel = createViewModel(
      FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE),
      FakeWebhookSettingsRepository(),
      noteOnlyEntryRepository,
      refreshWidgets = { refreshCount++ },
    )
    val events = collectEvents(viewModel)

    viewModel.setNoteOnlyEntryEnabled(true)
    advanceUntilIdle()

    assertEquals(false, noteOnlyEntryRepository.current)
    assertEquals(listOf(SettingsEvent.NoteOnlyEntrySaveFailed), events)
    assertEquals(0, refreshCount)
  }

  @Test
  fun `Widget更新に失敗してもメモだけ記録の保存は失敗扱いにしない`() = runTest(dispatcher) {
    val noteOnlyEntryRepository = FakeNoteOnlyEntryRepository()
    val viewModel = createViewModel(
      FakeAnalysisIntegrationRepository(AnalysisIntegration.NONE),
      FakeWebhookSettingsRepository(),
      noteOnlyEntryRepository,
      refreshWidgets = { throw IOException("widget update failed") },
    )
    val events = collectEvents(viewModel)

    viewModel.setNoteOnlyEntryEnabled(true)
    advanceUntilIdle()

    assertEquals(true, noteOnlyEntryRepository.current)
    assertTrue(events.isEmpty())
  }

  private fun collectUiState(viewModel: SettingsViewModel) {
    collectorScope.launch { viewModel.uiState.collect {} }
  }

  private fun collectEvents(viewModel: SettingsViewModel): List<SettingsEvent> {
    val events = mutableListOf<SettingsEvent>()
    collectorScope.launch { viewModel.events.collect { events += it } }
    return events
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

  private class GatedAnalysisIntegrationRepository(
    private val resolved: AnalysisIntegration,
  ) : AnalysisIntegrationRepository {
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

  private class FakeNoteOnlyEntryRepository(
    initial: Boolean = false,
    private var failNextSets: Int = 0,
  ) : NoteOnlyEntryRepository {
    private val state = MutableStateFlow(initial)
    override val isNoteOnlyEntryEnabled: Flow<Boolean> = state
    val current: Boolean get() = state.value

    override suspend fun setNoteOnlyEntryEnabled(enabled: Boolean) {
      if (failNextSets > 0) {
        failNextSets--
        throw IOException("note only entry write failed")
      }
      state.value = enabled
    }
  }

  private class FakeWebhookSettingsRepository(initial: WebhookSettings? = null) : WebhookSettingsRepository {
    private val state = MutableStateFlow(
      initial?.let { WebhookSettingsState.Configured(it) } ?: WebhookSettingsState.NotConfigured,
    )
    override val settings: Flow<WebhookSettingsState> = state

    override suspend fun save(settings: WebhookSettings) = error("not used in this test")
  }

  private class GatedReadWebhookSettingsRepository(
    private val resolved: WebhookSettingsState,
  ) : WebhookSettingsRepository {
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
}
