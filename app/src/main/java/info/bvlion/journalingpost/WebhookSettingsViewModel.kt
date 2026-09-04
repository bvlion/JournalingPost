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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Custom Webhook設定画面の状態を持つ。永続化済み設定の読み込み、編集中フォーム、直近のvalidation結果は
 * 同時に整合している必要があるため1つの[WebhookSettingsUiState]として扱い、保存操作の結果だけを
 * 1度きりの[saveResults]で伝える。
 */
class WebhookSettingsViewModel(
  private val webhookSettingsRepository: WebhookSettingsRepository,
  private val analysisIntegrationRepository: AnalysisIntegrationRepository,
) : ViewModel() {
  private val _uiState = MutableStateFlow(WebhookSettingsUiState())
  val uiState: StateFlow<WebhookSettingsUiState> = _uiState.asStateFlow()

  private val _saveResults = Channel<WebhookSaveResult>(Channel.BUFFERED)
  val saveResults: Flow<WebhookSaveResult> = _saveResults.receiveAsFlow()

  /**
   * 画面を開いてから閉じるまでのsessionと、その中の保存操作1回分を識別する。画面を離れた後や
   * 保存し直した後に完了した処理で、現在のフォームやAnalysisIntegrationを書き換えないために使う。
   *
   * 進行中の保存自体はcancelしない。画面を離れても、開始済みのWebhook設定の永続化は完了させる。
   * ViewModelの公開関数とviewModelScopeはmain threadで動くため、単純なvarで足りる。
   */
  private var requestSession = Any()

  // ViewModelが生き残るrotationでは入力中フォームを再初期化せず、process recreation時だけ初期化し直す。
  private var screenInitialized = false

  /**
   * [activatePendingSelection] は、Settingsで保存済み設定が無い状態からCustom Webhookを選んで
   * この画面へ来た場合にtrue。一時的な読み取り失敗から復旧して既存設定が見つかったときは、意味のない
   * 再保存を求めず、その場でCustom Webhookを有効化する。
   */
  fun onScreenOpened(activatePendingSelection: Boolean) {
    screenInitialized = true
    val session = Any()
    requestSession = session
    _uiState.value = WebhookSettingsUiState()
    viewModelScope.launch {
      // authoritativeな状態が分かるまでは、上流の読み込み状態をそのまま画面へ見せる。
      val settled = webhookSettingsRepository.settings
        .onEach { state ->
          if (session === requestSession) _uiState.update { it.copy(loadState = state.toLoadState()) }
        }
        .first { it is WebhookSettingsState.NotConfigured || it is WebhookSettingsState.Configured }
      if (session !== requestSession) return@launch

      // ここから先は画面を開いた時点のsnapshotを編集対象にし、上流の更新で巻き戻さない。
      _uiState.update { current ->
        current.copy(
          loadState = WebhookSettingsLoadState.READY,
          form = if (settled is WebhookSettingsState.Configured) settled.settings.toFormState() else current.form,
        )
      }

      if (activatePendingSelection && settled is WebhookSettingsState.Configured) {
        // ここでの有効化に失敗すると実効integrationはNONEのままなので、保存フローと同じfeedbackを出す。
        if (!activateCustomWebhook()) _saveResults.send(WebhookSaveResult.ACTIVATION_FAILED)
      }
    }
  }

  /** process recreationでこの画面がそのまま復元された場合のフォールバック。 */
  fun ensureScreenOpened(activatePendingSelection: Boolean) {
    if (screenInitialized) return
    onScreenOpened(activatePendingSelection)
  }

  fun onScreenClosed() {
    screenInitialized = false
    requestSession = Any()
    _uiState.value = WebhookSettingsUiState()
  }

  fun updateUrl(url: String) {
    updateForm { it.copy(url = url) }
    dropValidation(WebhookSettingsValidator.ValidationError.INVALID_URL)
  }

  fun addHeader() {
    updateForm { it.copy(headers = it.headers + WebhookHeader(name = "", value = "")) }
    refreshHeaderValidation()
  }

  fun removeHeader(index: Int) {
    updateForm { state -> state.copy(headers = state.headers.filterIndexed { i, _ -> i != index }) }
    refreshHeaderValidation()
  }

  fun updateHeaderName(index: Int, name: String) {
    updateForm { state ->
      state.copy(headers = state.headers.mapIndexed { i, header -> if (i == index) header.copy(name = name) else header })
    }
    refreshHeaderValidation()
  }

  fun updateHeaderValue(index: Int, value: String) {
    updateForm { state ->
      state.copy(headers = state.headers.mapIndexed { i, header -> if (i == index) header.copy(value = value) else header })
    }
    refreshHeaderValidation()
  }

  fun updateBodyTemplate(bodyTemplate: String) {
    updateForm { it.copy(bodyTemplate = bodyTemplate) }
    dropValidation(WebhookSettingsValidator.ValidationError.INVALID_BODY_TEMPLATE)
  }

  /** Body templateを初期値へ戻す。呼び出し側(画面)が確認を取ってから呼ぶ。 */
  fun resetBodyTemplate() {
    updateForm { it.copy(bodyTemplate = WebhookBodyTemplateRenderer.DEFAULT_TEMPLATE) }
    dropValidation(WebhookSettingsValidator.ValidationError.INVALID_BODY_TEMPLATE)
  }

  /**
   * Webhook設定の永続化に成功した後だけCustom Webhookを有効化する。保存開始後に画面を離れた場合は、
   * 古い保存完了で後続の「使用しない」選択を上書きしない。
   */
  fun save() {
    val session = Any()
    requestSession = session
    val form = _uiState.value.form
    val validation = WebhookSettingsValidator.validate(form.url, form.headers, form.bodyTemplate)
    if (validation.errors.isNotEmpty()) {
      _uiState.update { it.copy(validation = WebhookValidationState(validation.errors, validation.headerErrors)) }
      return
    }
    _uiState.update { it.copy(validation = WebhookValidationState()) }

    viewModelScope.launch {
      try {
        webhookSettingsRepository.save(WebhookSettings(form.url, validation.normalizedHeaders, form.bodyTemplate))
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        if (session === requestSession) _saveResults.send(WebhookSaveResult.FAILED)
        return@launch
      }
      if (session !== requestSession) return@launch
      // Webhook設定の保存に成功しても、Custom Webhookの有効化まで成功して初めて「保存できた」と
      // 見せる。有効化に失敗した場合は実効AnalysisIntegrationがNONEのままなので、その旨を伝える。
      val activated = activateCustomWebhook()
      _saveResults.send(if (activated) WebhookSaveResult.SUCCEEDED else WebhookSaveResult.ACTIVATION_FAILED)
    }
  }

  /** 永続化できたら true。失敗はこの画面のfeedback([saveResults])で扱うため、例外は握って返り値で示す。 */
  private suspend fun activateCustomWebhook(): Boolean =
    try {
      analysisIntegrationRepository.setAnalysisIntegration(AnalysisIntegration.CUSTOM_WEBHOOK)
      true
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      false
    }

  private fun updateForm(transform: (WebhookFormState) -> WebhookFormState) {
    _uiState.update { it.copy(form = transform(it.form)) }
  }

  // 利用者が該当欄を編集したら、その欄由来のvalidation errorだけ消す(次の保存までerrorを残さない)。
  private fun dropValidation(vararg kinds: WebhookSettingsValidator.ValidationError) {
    _uiState.update { it.copy(validation = it.validation.copy(all = it.validation.all - kinds.toSet())) }
  }

  /**
   * 表示中のheader errorを現在の入力へ追随させる。header errorがまだ1つも出ていない状態では、
   * 保存前に先回りしてerrorを出さないため何もしない。1行だけ直しても未修正行のerrorは残り、
   * 重複解消やindexのずれは再検証で現在の入力に合う内訳へ更新される。
   */
  private fun refreshHeaderValidation() {
    _uiState.update { state ->
      val current = state.validation
      val headerErrorShown = current.headerErrors.isNotEmpty() ||
        current.all.any { it in WebhookSettingsValidator.HEADER_ERRORS }
      if (!headerErrorShown) return@update state

      val headerErrors = WebhookSettingsValidator.validateHeaders(state.form.headers)
      state.copy(
        validation = current.copy(
          all = current.all.filterNot { it in WebhookSettingsValidator.HEADER_ERRORS } +
            WebhookSettingsValidator.HEADER_ERRORS.filter { kind -> headerErrors.values.any { kind in it } },
          headerErrors = headerErrors,
        ),
      )
    }
  }
}

data class WebhookSettingsUiState(
  val loadState: WebhookSettingsLoadState = WebhookSettingsLoadState.LOADING,
  val form: WebhookFormState = WebhookFormState(),
  val validation: WebhookValidationState = WebhookValidationState(),
)

enum class WebhookSettingsLoadState {
  LOADING,
  UNAVAILABLE,
  READY,
}

/** SUCCEEDEDは、設定の保存に加えてCustom Webhookの有効化まで成功した場合のみ。 */
enum class WebhookSaveResult {
  SUCCEEDED,
  FAILED,
  ACTIVATION_FAILED,
}

data class WebhookFormState(
  val url: String = "",
  val headers: List<WebhookHeader> = emptyList(),
  // 新規設定は初期templateから始める。保存済み設定を開いたときは[toFormState]で上書きする。
  val bodyTemplate: String = WebhookBodyTemplateRenderer.DEFAULT_TEMPLATE,
)

/**
 * 直近の保存で出たvalidation結果。[all] は保存可否判定にも使う全error、[headerErrors] はどのheader行の
 * 問題かのindex別内訳。利用者が該当欄を編集したら、その欄由来のerrorだけ消して残さない。
 */
data class WebhookValidationState(
  val all: List<WebhookSettingsValidator.ValidationError> = emptyList(),
  val headerErrors: Map<Int, List<WebhookSettingsValidator.ValidationError>> = emptyMap(),
) {
  val isEmpty: Boolean get() = all.isEmpty()
}

private fun WebhookSettingsState.toLoadState(): WebhookSettingsLoadState = when (this) {
  WebhookSettingsState.Loading -> WebhookSettingsLoadState.LOADING
  WebhookSettingsState.Unavailable -> WebhookSettingsLoadState.UNAVAILABLE
  WebhookSettingsState.NotConfigured, is WebhookSettingsState.Configured -> WebhookSettingsLoadState.READY
}

private fun WebhookSettings.toFormState() =
  WebhookFormState(url = url, headers = headers, bodyTemplate = bodyTemplate)
