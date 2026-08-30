package info.bvlion.journalingpost

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import info.bvlion.journalingpost.settings.AnalysisIntegration
import info.bvlion.journalingpost.settings.AnalysisIntegrationRepository
import info.bvlion.journalingpost.webhook.WebhookBodyTemplateRenderer
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
  /** 読み込み確定前の値を「使用しない」と誤表示しないため、初期値はnullにする。 */
  val analysisIntegration: StateFlow<AnalysisIntegration?> = analysisIntegrationRepository.analysisIntegration
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

  private val _pendingCustomWebhookSelection = MutableStateFlow(false)

  private val selectedIntegrationFlow: Flow<AnalysisIntegration?> = combine(
    analysisIntegration,
    _pendingCustomWebhookSelection,
  ) { integration, pendingCustomWebhook ->
    if (pendingCustomWebhook) AnalysisIntegration.CUSTOM_WEBHOOK else integration
  }

  /** 未設定からCustom Webhookを選んだ直後は、保存完了前でもradioだけは利用者の選択を示す。 */
  val selectedAnalysisIntegration: StateFlow<AnalysisIntegration?> = selectedIntegrationFlow
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

  private val _integrationSaveFailed = MutableStateFlow(false)
  val integrationSaveFailed: StateFlow<Boolean> = _integrationSaveFailed.asStateFlow()

  private val webhookSettingsState: StateFlow<WebhookSettingsState> = webhookSettingsRepository.settings
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WebhookSettingsState.Loading)

  val webhookDestinationLabel: StateFlow<String?> = combine(analysisIntegration, webhookSettingsState) { integration, state ->
    if (integration != AnalysisIntegration.CUSTOM_WEBHOOK) return@combine null
    (state as? WebhookSettingsState.Configured)?.settings?.destinationLabel()
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

  private val _webhookSetupRequested = MutableStateFlow(false)
  val webhookSetupRequested: StateFlow<Boolean> = _webhookSetupRequested.asStateFlow()

  fun consumeWebhookSetupRequest() {
    _webhookSetupRequested.value = false
  }

  private val integrationRequestGeneration = AtomicInteger(0)

  /**
   * Custom Webhookは保存済み設定がある場合だけ有効化する。未設定または一時的に読み込めない場合は、
   * 選択を保留してWebhook設定画面へ進める。
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

  // 画面を開いた時点のsnapshotを編集中に上流の更新で巻き戻さない。
  private val _webhookFormLoaded = MutableStateFlow(false)

  val webhookSettingsLoadState: StateFlow<WebhookSettingsLoadState> = combine(
    webhookSettingsState,
    _webhookFormLoaded,
  ) { state, loaded ->
    when {
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
   * 前回のSettings表示中に開始したCustom Webhook判定が後から完了して、次回表示を勝手に遷移させないよう
   * 世代を進めて無効化する。
   */
  fun onSettingsOpened() {
    integrationRequestGeneration.incrementAndGet()
    _integrationSaveFailed.value = false
    _pendingCustomWebhookSelection.value = false
    _webhookSetupRequested.value = false
  }

  fun onWebhookSettingsScreenOpened() {
    webhookSettingsScreenInitialized = true
    val generation = webhookRequestGeneration.incrementAndGet()
    _webhookValidationErrors.value = emptyList()
    _webhookSaveFailed.value = false
    _webhookFormLoaded.value = false
    _webhookFormState.value = WebhookFormState()
    viewModelScope.launch {
      val settled = webhookSettingsState.first {
        it is WebhookSettingsState.NotConfigured || it is WebhookSettingsState.Configured
      }
      if (generation != webhookRequestGeneration.get()) return@launch
      if (settled is WebhookSettingsState.Configured) {
        _webhookFormState.value = settled.settings.toFormState()
        // 一時的な読み取り失敗から復旧して既存設定が見つかった場合、意味のない再保存を要求しない。
        if (_pendingCustomWebhookSelection.value) {
          persistAnalysisIntegration(AnalysisIntegration.CUSTOM_WEBHOOK, integrationRequestGeneration.incrementAndGet())
          _pendingCustomWebhookSelection.value = false
        }
      }
      _webhookFormLoaded.value = true
    }
  }

  fun onWebhookSettingsScreenClosed() {
    webhookSettingsScreenInitialized = false
    webhookRequestGeneration.incrementAndGet()
    _webhookValidationErrors.value = emptyList()
    _webhookSaveFailed.value = false
    _webhookFormLoaded.value = false
    _webhookFormState.value = WebhookFormState()
    _pendingCustomWebhookSelection.value = false
  }

  // ViewModelが生き残るrotationでは入力中フォームを再初期化せず、process recreation時だけ初期化し直す。
  private var webhookSettingsScreenInitialized = false

  fun ensureWebhookSettingsScreenOpened() {
    if (webhookSettingsScreenInitialized) return
    onWebhookSettingsScreenOpened()
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

  /** Body templateを初期値へ戻す。呼び出し側(画面)が確認を取ってから呼ぶ。 */
  fun resetWebhookBodyTemplate() {
    updateWebhookForm { it.copy(bodyTemplate = WebhookBodyTemplateRenderer.DEFAULT_TEMPLATE) }
  }

  private fun updateWebhookForm(transform: (WebhookFormState) -> WebhookFormState) {
    _webhookFormState.update(transform)
  }

  /**
   * Webhook設定の永続化に成功した後だけCustom Webhookを有効化する。保存開始後に画面を離れた場合は、
   * 古い保存完了で後続の「使用しない」選択を上書きしない。
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
      if (generation == webhookRequestGeneration.get()) {
        persistAnalysisIntegration(AnalysisIntegration.CUSTOM_WEBHOOK, integrationRequestGeneration.incrementAndGet())
        _pendingCustomWebhookSelection.value = false
      }
    }
  }
}

enum class WebhookSettingsLoadState {
  LOADING,
  UNAVAILABLE,
  READY,
}

data class WebhookFormState(
  val url: String = "",
  val headers: List<WebhookHeader> = emptyList(),
  // 新規設定は初期templateから始める。保存済み設定を開いたときは[toFormState]で上書きする。
  val bodyTemplate: String = WebhookBodyTemplateRenderer.DEFAULT_TEMPLATE,
)

private fun WebhookSettings.toFormState() =
  WebhookFormState(url = url, headers = headers, bodyTemplate = bodyTemplate)
