package info.bvlion.journalingpost

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import info.bvlion.journalingpost.settings.AnalysisIntegration
import info.bvlion.journalingpost.settings.AnalysisIntegrationRepository
import info.bvlion.journalingpost.webhook.WebhookHeader
import info.bvlion.journalingpost.webhook.WebhookSettings
import info.bvlion.journalingpost.webhook.WebhookSettingsRepository
import info.bvlion.journalingpost.webhook.WebhookSettingsState
import info.bvlion.journalingpost.webhook.WebhookSettingsValidator
import info.bvlion.journalingpost.webhook.destinationLabel
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsViewModel(
  private val analysisIntegrationRepository: AnalysisIntegrationRepository,
  private val webhookSettingsRepository: WebhookSettingsRepository,
) : ViewModel() {
  /** 実際に有効になっている解析・連携。CUSTOM_WEBHOOKなら保存済みWebhook設定が存在する。 */
  val analysisIntegration: StateFlow<AnalysisIntegration> = analysisIntegrationRepository.analysisIntegration
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnalysisIntegration.NONE)

  // Webhookが未設定のままCustom Webhookを選んだ状態。まだ有効化はせず、Webhook設定画面へ進むための
  // 画面上の選択としてのみ扱う(設定を保存しないまま画面を離れれば「使用しない」のまま)。
  private val _pendingCustomWebhookSelection = MutableStateFlow(false)

  private val selectedIntegrationFlow: Flow<AnalysisIntegration> = combine(
    analysisIntegrationRepository.analysisIntegration,
    _pendingCustomWebhookSelection,
  ) { integration, pendingCustomWebhook ->
    if (pendingCustomWebhook) AnalysisIntegration.CUSTOM_WEBHOOK else integration
  }

  /** 親Settingsのradioが示す選択。未確定のCustom Webhook選択を含むため、[analysisIntegration]とは一致しないことがある。 */
  val selectedAnalysisIntegration: StateFlow<AnalysisIntegration> = selectedIntegrationFlow
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnalysisIntegration.NONE)

  private val _integrationSaveFailed = MutableStateFlow(false)
  val integrationSaveFailed: StateFlow<Boolean> = _integrationSaveFailed.asStateFlow()

  private val webhookSettingsState: StateFlow<WebhookSettingsState> = webhookSettingsRepository.settings
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WebhookSettingsState.Loading)

  /**
   * 親Settingsの「Webhook設定」項目を出すかどうかと、その短い送信先表示を兼ねる。CUSTOM_WEBHOOKが
   * 実際に有効(保存済み設定が存在する)場合のみ非nullになるため、この値がnullの間は項目自体を出さない。
   */
  val webhookDestinationLabel: StateFlow<String?> = combine(analysisIntegration, webhookSettingsState) { integration, state ->
    if (integration != AnalysisIntegration.CUSTOM_WEBHOOK) return@combine null
    (state as? WebhookSettingsState.Configured)?.settings?.destinationLabel()
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

  /** Webhook設定画面へ、未設定のセットアップとして進むべきタイミングで一度だけtrueになる。 */
  private val _webhookSetupRequested = MutableStateFlow(false)
  val webhookSetupRequested: StateFlow<Boolean> = _webhookSetupRequested.asStateFlow()

  fun consumeWebhookSetupRequest() {
    _webhookSetupRequested.value = false
  }

  // 呼び出し完了時に、より新しい選択が既に行われていないかを確認するための世代番号。
  private val integrationRequestGeneration = AtomicInteger(0)

  /**
   * 使用しないを選んだ場合はその時点で永続化する。Custom Webhookは、保存済み設定がある場合だけ
   * この時点で有効化する。未設定(またはDataStoreを一時的に読めない)場合は有効化せず、
   * Webhook設定画面でのセットアップへ進む要求を出す(有効なのに送信先が無い状態を通常操作で
   * 作らないため)。未設定から選んだ場合は、設定の保存に成功した時点で有効になる([saveWebhookSettings])。
   */
  fun setAnalysisIntegration(integration: AnalysisIntegration) {
    val generation = integrationRequestGeneration.incrementAndGet()
    _integrationSaveFailed.value = false
    if (integration != AnalysisIntegration.CUSTOM_WEBHOOK) {
      _pendingCustomWebhookSelection.value = false
      viewModelScope.launch { persistAnalysisIntegration(integration, generation) }
      return
    }
    _pendingCustomWebhookSelection.value = true
    viewModelScope.launch {
      val current = webhookSettingsRepository.settings.first()
      if (generation != integrationRequestGeneration.get()) return@launch
      if (current is WebhookSettingsState.Configured) {
        persistAnalysisIntegration(integration, generation)
        if (generation == integrationRequestGeneration.get()) _pendingCustomWebhookSelection.value = false
      } else {
        _webhookSetupRequested.value = true
      }
    }
  }

  private suspend fun persistAnalysisIntegration(integration: AnalysisIntegration, generation: Int) {
    try {
      analysisIntegrationRepository.setAnalysisIntegration(integration)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (generation == integrationRequestGeneration.get()) _integrationSaveFailed.value = true
    }
  }

  private val _webhookFormState = MutableStateFlow(WebhookFormState())
  val webhookFormState: StateFlow<WebhookFormState> = _webhookFormState.asStateFlow()

  // 今回のWebhook設定画面の表示中に、フォームへ初期値を読み込み済みかどうか。読み込み済みになった後は
  // 上流のwebhookSettingsStateが変化しても(legacy migration等)フォーム値を上書きしない。
  private val _webhookFormLoaded = MutableStateFlow(false)

  val webhookSettingsLoadState: StateFlow<WebhookSettingsLoadState> = combine(
    webhookSettingsState,
    _webhookFormLoaded,
  ) { state, loaded ->
    when {
      // 一度フォームへ読み込んだ後は、上流が一時的にUnavailableへ揺れても編集画面を隠さない。
      loaded -> WebhookSettingsLoadState.READY
      state is WebhookSettingsState.NotConfigured || state is WebhookSettingsState.Configured -> WebhookSettingsLoadState.READY
      state is WebhookSettingsState.Unavailable -> WebhookSettingsLoadState.UNAVAILABLE
      else -> WebhookSettingsLoadState.LOADING
    }
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WebhookSettingsLoadState.LOADING)

  private val _webhookValidationErrors = MutableStateFlow<List<WebhookSettingsValidator.ValidationError>>(emptyList())
  val webhookValidationErrors: StateFlow<List<WebhookSettingsValidator.ValidationError>> = _webhookValidationErrors.asStateFlow()

  private val _webhookSaveFailed = MutableStateFlow(false)
  val webhookSaveFailed: StateFlow<Boolean> = _webhookSaveFailed.asStateFlow()

  private val webhookRequestGeneration = AtomicInteger(0)

  /**
   * 設定画面へ入り直したときは、前回の未確定なCustom Webhook選択を持ち越さない(保存済みWebhook設定が
   * ないままWebhook設定画面から戻っていた場合、選択は「使用しない」へ戻る)。
   */
  fun onSettingsOpened() {
    _integrationSaveFailed.value = false
    _pendingCustomWebhookSelection.value = false
  }

  /**
   * Webhook設定画面を開いたときに呼ぶ。authoritativeな状態(NotConfigured/Configured)が分かった
   * 最初の1回だけフォームへ値を読み込み、以降はこの画面を表示している間ずっとその値を保持する。
   * 前回の保存結果(validation error / 保存失敗)もここでクリアする。
   */
  fun onWebhookSettingsScreenOpened() {
    val generation = webhookRequestGeneration.incrementAndGet()
    _webhookValidationErrors.value = emptyList()
    _webhookSaveFailed.value = false
    _webhookFormLoaded.value = false
    _webhookFormState.value = WebhookFormState()
    viewModelScope.launch {
      // Loading/Unavailableの間はスキップし、authoritativeな状態(NotConfigured/Configured)の
      // 最初の1件だけを待つ。firstは条件を満たした時点で購読を終えるため、この画面を何度
      // 開閉してもコルーチンが残り続けない。
      val settled = webhookSettingsState.first {
        it is WebhookSettingsState.NotConfigured || it is WebhookSettingsState.Configured
      }
      if (generation != webhookRequestGeneration.get()) return@launch
      if (settled is WebhookSettingsState.Configured) {
        _webhookFormState.value = settled.settings.toFormState()
      }
      _webhookFormLoaded.value = true
    }
  }

  /**
   * Webhook設定画面を閉じたとき(Back)に呼ぶ。保存しなかった編集内容は破棄する。未設定のまま
   * セットアップ中だった場合、Custom Webhookの選択も未確定のままなので「使用しない」へ戻す。
   */
  fun onWebhookSettingsScreenClosed() {
    webhookRequestGeneration.incrementAndGet()
    _webhookValidationErrors.value = emptyList()
    _webhookSaveFailed.value = false
    _webhookFormLoaded.value = false
    _webhookFormState.value = WebhookFormState()
    _pendingCustomWebhookSelection.value = false
  }

  fun updateWebhookUrl(url: String) {
    updateWebhookForm { it.copy(url = url) }
  }

  fun addWebhookHeader() {
    updateWebhookForm { it.copy(headers = it.headers + WebhookHeader(name = "", value = "")) }
  }

  fun removeWebhookHeader(index: Int) {
    updateWebhookForm { state -> state.copy(headers = state.headers.filterIndexed { i, _ -> i != index }) }
  }

  fun updateWebhookHeaderName(index: Int, name: String) {
    updateWebhookForm { state ->
      state.copy(headers = state.headers.mapIndexed { i, header -> if (i == index) header.copy(name = name) else header })
    }
  }

  fun updateWebhookHeaderValue(index: Int, value: String) {
    updateWebhookForm { state ->
      state.copy(headers = state.headers.mapIndexed { i, header -> if (i == index) header.copy(value = value) else header })
    }
  }

  fun updateWebhookBodyTemplate(bodyTemplate: String) {
    updateWebhookForm { it.copy(bodyTemplate = bodyTemplate) }
  }

  private fun updateWebhookForm(transform: (WebhookFormState) -> WebhookFormState) {
    _webhookFormState.update(transform)
  }

  /**
   * 保存に成功した時点で初めてCustom Webhookを有効にする(保存失敗時は途中でreturnするため、
   * 「有効なのに設定がない」状態を作らない)。編集内容は保存後もフォームへ残したままにする
   * (このPRでは確認/編集を分けないため、保存後も画面はそのまま表示・再編集できる)。
   *
   * 保存中に利用者がさらに編集した場合(generationが進んだ場合)も、この関数はwebhookFormStateを
   * 書き換えない。保存開始時点のスナップショットで永続化する一方、フォームに残っているのは常に
   * 利用者の最新の入力なので、上書きすると保存中に行った編集が失われてしまう。
   */
  fun saveWebhookSettings() {
    val generation = webhookRequestGeneration.incrementAndGet()
    val form = _webhookFormState.value
    val validation = WebhookSettingsValidator.validate(form.url, form.headers, form.bodyTemplate)
    if (validation.errors.isNotEmpty()) {
      _webhookValidationErrors.value = validation.errors
      return
    }
    _webhookValidationErrors.value = emptyList()
    _webhookSaveFailed.value = false
    viewModelScope.launch {
      try {
        webhookSettingsRepository.save(WebhookSettings(form.url, validation.normalizedHeaders, form.bodyTemplate))
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        if (generation == webhookRequestGeneration.get()) _webhookSaveFailed.value = true
        return@launch
      }
      persistAnalysisIntegration(AnalysisIntegration.CUSTOM_WEBHOOK, integrationRequestGeneration.incrementAndGet())
      _pendingCustomWebhookSelection.value = false
    }
  }
}

/** Webhook設定画面で、読み込み中/読み込み不能/編集可能のどれを表示するか。 */
enum class WebhookSettingsLoadState {
  LOADING,
  UNAVAILABLE,
  READY,
}

data class WebhookFormState(
  val url: String = "",
  val headers: List<WebhookHeader> = emptyList(),
  val bodyTemplate: String = "",
)

private fun WebhookSettings.toFormState() = WebhookFormState(url = url, headers = headers, bodyTemplate = bodyTemplate)
