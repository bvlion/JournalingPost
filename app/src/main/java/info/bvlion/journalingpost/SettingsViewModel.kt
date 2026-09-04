package info.bvlion.journalingpost

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import info.bvlion.journalingpost.debug.DebugFixtureSeedResult
import info.bvlion.journalingpost.debug.DebugFixtureSeeder
import info.bvlion.journalingpost.hosted.HostedConsentRepository
import info.bvlion.journalingpost.settings.AnalysisIntegration
import info.bvlion.journalingpost.settings.AnalysisIntegrationRepository
import info.bvlion.journalingpost.settings.MoodNoteInputRepository
import info.bvlion.journalingpost.settings.NoteOnlyEntryRepository
import info.bvlion.journalingpost.webhook.WebhookSettingsRepository
import info.bvlion.journalingpost.webhook.WebhookSettingsState
import info.bvlion.journalingpost.webhook.destinationLabelOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Settings画面(解析・連携の選択)の状態を持つ。Custom Webhookの詳細設定は
 * [WebhookSettingsViewModel]の責務で、ここでは現在の送信先を示す情報までを扱う。
 */
class SettingsViewModel(
  private val analysisIntegrationRepository: AnalysisIntegrationRepository,
  private val webhookSettingsRepository: WebhookSettingsRepository,
  private val noteOnlyEntryRepository: NoteOnlyEntryRepository,
  private val moodNoteInputRepository: MoodNoteInputRepository,
  private val hostedConsentRepository: HostedConsentRepository,
  private val refreshWidgets: suspend () -> Unit,
  /** debugビルドでのみ非null。動作確認用fixtureの投入導線を出すかどうかの判定にも使う。 */
  private val debugFixtureSeeder: DebugFixtureSeeder? = null,
) : ViewModel() {
  /** 未設定からCustom Webhookを選んだ直後は、保存完了前でもradioだけは利用者の選択を示す。 */
  private val pendingCustomWebhookSelection = MutableStateFlow(false)

  /** Hostedを選んで外部送信の同意ダイアログを表示している間、radioだけは利用者の選択を示す。 */
  private val pendingHostedSelection = MutableStateFlow(false)

  /**
   * 初回案内(#67)から「設定する」で遷移した直後だけtrue。解析・連携セクションの場所を一度だけ示す
   * 短い補助表示に使う。他のUiStateとは更新頻度も購読先も異なるため、combine対象へは含めない。
   */
  private val _highlightAnalysisIntegration = MutableStateFlow(false)
  val highlightAnalysisIntegration: StateFlow<Boolean> = _highlightAnalysisIntegration.asStateFlow()

  val uiState: StateFlow<SettingsUiState> = combine(
    analysisIntegrationRepository.analysisIntegration,
    webhookSettingsRepository.settings,
    pendingCustomWebhookSelection,
    pendingHostedSelection,
    combine(
      noteOnlyEntryRepository.isNoteOnlyEntryEnabled,
      moodNoteInputRepository.isMoodNoteInputInitiallyOpen,
    ) { noteOnlyEntryEnabled, isMoodNoteInputInitiallyOpen ->
      noteOnlyEntryEnabled to isMoodNoteInputInitiallyOpen
    },
  ) { integration, webhookSettings, pendingCustomWebhook, pendingHosted, recordingSettings ->
    SettingsUiState(
      selectedIntegration = when {
        pendingCustomWebhook -> AnalysisIntegration.CUSTOM_WEBHOOK
        pendingHosted -> AnalysisIntegration.HOSTED
        else -> integration
      },
      webhookConfigured = integration == AnalysisIntegration.CUSTOM_WEBHOOK &&
        webhookSettings is WebhookSettingsState.Configured,
      webhookDestinationLabel = if (integration == AnalysisIntegration.CUSTOM_WEBHOOK) {
        (webhookSettings as? WebhookSettingsState.Configured)?.settings?.destinationLabelOrNull()
      } else {
        null
      },
      noteOnlyEntryEnabled = recordingSettings.first,
      isMoodNoteInputInitiallyOpen = recordingSettings.second,
    )
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

  private val _events = Channel<SettingsEvent>(Channel.BUFFERED)
  val events: Flow<SettingsEvent> = _events.receiveAsFlow()

  /**
   * 解析・連携の選択1回分を識別する。選び直しやSettingsの再表示より後に完了した判定・保存は、
   * 現在の選択やSnackbarを上書きしない。DataStoreへの書き込み自体はcancelせず最後まで行う。
   * ViewModelの公開関数とviewModelScopeはmain threadで動くため、単純なvarで足りる。
   */
  private var selectionSession = Any()

  /**
   * 前回のSettings表示中に開始したCustom Webhook判定が後から完了して、次回表示を勝手に遷移させないよう
   * sessionを切り替えて無効化する。保留していた選択表示も解除する。
   *
   * [highlightAnalysisIntegration]は初回案内から遷移した今回の表示でだけ立てる。次に表示を開いたとき
   * (デフォルト引数のfalse)には出さない。
   */
  fun onSettingsOpened(highlightAnalysisIntegration: Boolean = false) {
    selectionSession = Any()
    pendingCustomWebhookSelection.value = false
    pendingHostedSelection.value = false
    _highlightAnalysisIntegration.value = highlightAnalysisIntegration
    // 前回の表示中に発生して画面へ届かなかった結果は、次回表示へ持ち越さない。
    while (_events.tryReceive().isSuccess) Unit
  }

  /** Webhook設定画面から戻ったら、保留していた選択表示は解除して永続化済みの値へ従う。 */
  fun onWebhookSettingsClosed() {
    pendingCustomWebhookSelection.value = false
  }

  /**
   * Custom Webhookは保存済み設定がある場合だけ有効化する。未設定または一時的に読み込めない場合は、
   * 選択を保留して[SettingsEvent.WebhookSetupRequested]でWebhook設定画面へ進める。
   * Hostedは初回有効化時だけ外部送信の同意を[SettingsEvent.HostedConsentRequested]で確認する。
   * 一度同意していれば、以後は同じ確認を繰り返さずそのまま有効化する。
   */
  fun setAnalysisIntegration(integration: AnalysisIntegration) {
    _highlightAnalysisIntegration.value = false
    val session = Any()
    selectionSession = session

    when (integration) {
      AnalysisIntegration.NONE -> {
        pendingCustomWebhookSelection.value = false
        pendingHostedSelection.value = false
        viewModelScope.launch { persistAnalysisIntegration(integration, session) }
      }

      AnalysisIntegration.CUSTOM_WEBHOOK -> {
        pendingHostedSelection.value = false
        pendingCustomWebhookSelection.value = true
        viewModelScope.launch {
          val current = webhookSettingsRepository.settings.first()
          if (session !== selectionSession) return@launch
          if (current is WebhookSettingsState.Configured) {
            persistAnalysisIntegration(integration, session)
            if (session === selectionSession) pendingCustomWebhookSelection.value = false
          } else {
            _events.send(SettingsEvent.WebhookSetupRequested)
          }
        }
      }

      AnalysisIntegration.HOSTED -> {
        pendingCustomWebhookSelection.value = false
        pendingHostedSelection.value = true
        viewModelScope.launch {
          val consented = hostedConsentRepository.hasConsented.first()
          if (session !== selectionSession) return@launch
          if (consented) {
            persistAnalysisIntegration(integration, session)
            if (session === selectionSession) pendingHostedSelection.value = false
          } else {
            _events.send(SettingsEvent.HostedConsentRequested)
          }
        }
      }
    }
  }

  /**
   * Hostedの外部送信に初めて同意した。同意を記録してから永続化する。
   * 同意の記録中に選び直された場合、その後のsessionチェックで古い同意によるHOSTEDの
   * 永続化を止め、後から選ばれた値を上書きしないようにする。
   */
  fun confirmHostedIntegration() {
    val session = Any()
    selectionSession = session
    viewModelScope.launch {
      try {
        hostedConsentRepository.markConsented()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // 同意記録に失敗しても今回の有効化は進める。次に選び直す際は再度この確認を経る。
      }
      if (session !== selectionSession) return@launch
      persistAnalysisIntegration(AnalysisIntegration.HOSTED, session)
      if (session === selectionSession) pendingHostedSelection.value = false
    }
  }

  /** 同意ダイアログを閉じた(同意しなかった)。保留していた選択表示は解除する。 */
  fun dismissHostedConsent() {
    pendingHostedSelection.value = false
  }

  /** 「メモだけ記録」の表示設定は記録画面とWidgetで共有するため、保存後に配置済みWidgetも更新する。 */
  fun setNoteOnlyEntryEnabled(enabled: Boolean) {
    viewModelScope.launch {
      try {
        noteOnlyEntryRepository.setNoteOnlyEntryEnabled(enabled)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        _events.send(SettingsEvent.NoteOnlyEntrySaveFailed)
        return@launch
      }
      try {
        refreshWidgets()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // 設定自体は保存済みで、Widgetは次回のsystem updateでも同じRepositoryから再描画される。
      }
    }
  }

  fun setMoodNoteInputInitiallyOpen(isOpen: Boolean) {
    viewModelScope.launch {
      try {
        moodNoteInputRepository.setMoodNoteInputInitiallyOpen(isOpen)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        _events.send(SettingsEvent.MoodNoteInputSaveFailed)
      }
    }
  }

  /** debugビルドの設定画面からのみ呼ばれる。releaseでは[debugFixtureSeeder]がnullで何もしない。 */
  fun seedDebugFixtures() {
    val seeder = debugFixtureSeeder ?: return
    viewModelScope.launch {
      val event = try {
        when (val result = seeder.seed()) {
          is DebugFixtureSeedResult.Seeded ->
            SettingsEvent.DebugFixturesSeeded(result.entryCount, result.analysisResultCount)

          DebugFixtureSeedResult.AlreadySeeded -> SettingsEvent.DebugFixturesAlreadySeeded
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        SettingsEvent.DebugFixturesSeedFailed
      }
      _events.send(event)
    }
  }

  private suspend fun persistAnalysisIntegration(integration: AnalysisIntegration, session: Any) {
    try {
      analysisIntegrationRepository.setAnalysisIntegration(integration)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (session === selectionSession) _events.send(SettingsEvent.IntegrationSaveFailed)
    }
  }
}

/**
 * 読み込み確定前の値を「使用しない」と誤表示しないため、[selectedIntegration]の初期値はnullにする。
 */
data class SettingsUiState(
  val selectedIntegration: AnalysisIntegration? = null,
  /** Custom Webhookが解析先で、かつ保存済み設定が存在するとき true。設定項目を出すかどうかに使う。 */
  val webhookConfigured: Boolean = false,
  /** 現在の送信先を安全に示す短い文字列。作れない場合はnull(画面側でfallback表示)。 */
  val webhookDestinationLabel: String? = null,
  /** 「メモだけ記録」を表示するか。読み込み確定前はnull。 */
  val noteOnlyEntryEnabled: Boolean? = null,
  /** Mood記録の開始時からメモ入力を開くか。読み込み確定前はnull。 */
  val isMoodNoteInputInitiallyOpen: Boolean? = null,
)

sealed interface SettingsEvent {
  data object IntegrationSaveFailed : SettingsEvent

  data object NoteOnlyEntrySaveFailed : SettingsEvent

  data object MoodNoteInputSaveFailed : SettingsEvent

  /** Custom Webhookを選んだが保存済み設定が無く、Webhook設定画面へ進める必要がある。 */
  data object WebhookSetupRequested : SettingsEvent

  /** Hostedを選んだので、JournalEntryが外部送信されることへの同意を確認する。 */
  data object HostedConsentRequested : SettingsEvent

  data class DebugFixturesSeeded(val entryCount: Int, val analysisResultCount: Int) : SettingsEvent

  data object DebugFixturesAlreadySeeded : SettingsEvent

  data object DebugFixturesSeedFailed : SettingsEvent
}
