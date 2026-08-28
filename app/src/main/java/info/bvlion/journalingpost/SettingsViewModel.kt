package info.bvlion.journalingpost

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import info.bvlion.journalingpost.settings.RecordMode
import info.bvlion.journalingpost.settings.RecordModeRepository
import info.bvlion.journalingpost.webhook.WebhookHeader
import info.bvlion.journalingpost.webhook.WebhookSettings
import info.bvlion.journalingpost.webhook.WebhookSettingsRepository
import info.bvlion.journalingpost.webhook.WebhookSettingsState
import info.bvlion.journalingpost.webhook.WebhookSettingsValidator
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsViewModel(
  private val recordModeRepository: RecordModeRepository,
  private val webhookSettingsRepository: WebhookSettingsRepository,
) : ViewModel() {
  val recordMode: StateFlow<RecordMode> = recordModeRepository.recordMode
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecordMode.LOCAL_AND_WEBHOOK)

  private val _saveFailed = MutableStateFlow(false)
  val saveFailed: StateFlow<Boolean> = _saveFailed.asStateFlow()

  // 呼び出し完了時に、より新しい選択が既に行われていないかを確認するための世代番号。
  private val requestGeneration = AtomicInteger(0)

  fun setRecordMode(mode: RecordMode) {
    val generation = requestGeneration.incrementAndGet()
    _saveFailed.value = false
    viewModelScope.launch {
      try {
        recordModeRepository.setRecordMode(mode)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        if (generation == requestGeneration.get()) _saveFailed.value = true
      }
    }
  }

  // Loading/Unavailableと「本当に未設定」(NotConfigured)を区別する。読み込み中・読み込み不能の間は
  // 新規設定フォームを確定表示しない(authoritativeな状態が分かる前に、既存設定を誤って
  // 上書きしかねない編集画面を開かせないため)。
  val webhookSettingsState: StateFlow<WebhookSettingsState> = webhookSettingsRepository.settings
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WebhookSettingsState.Loading)

  val isWebhookConfigured: StateFlow<Boolean> = webhookSettingsState
    .map { it is WebhookSettingsState.Configured }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

  private val _webhookFormState = MutableStateFlow(WebhookFormState())
  val webhookFormState: StateFlow<WebhookFormState> = _webhookFormState.asStateFlow()

  // ユーザーが「表示して編集」を選んだ状態。保存済みsecret(Header value/Body template等)は、
  // これがtrueになるまでフォームへ展開しない。
  private val _webhookFormRevealed = MutableStateFlow(false)

  /**
   * NotConfiguredなら新規入力のため常にフォームを表示し、Configuredならユーザーが明示的に
   * 表示・編集を選ぶまで(_webhookFormRevealed)フォームを表示しない。Loading/Unavailableの間は
   * revealedの値に関わらずフォームを出さない。
   */
  val isWebhookFormVisible: StateFlow<Boolean> = combine(webhookSettingsState, _webhookFormRevealed) { state, revealed ->
    when (state) {
      WebhookSettingsState.Loading, WebhookSettingsState.Unavailable -> false
      WebhookSettingsState.NotConfigured -> true
      is WebhookSettingsState.Configured -> revealed
    }
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

  private val _webhookValidationErrors = MutableStateFlow<List<WebhookSettingsValidator.ValidationError>>(emptyList())
  val webhookValidationErrors: StateFlow<List<WebhookSettingsValidator.ValidationError>> = _webhookValidationErrors.asStateFlow()

  private val _webhookSaveFailed = MutableStateFlow(false)
  val webhookSaveFailed: StateFlow<Boolean> = _webhookSaveFailed.asStateFlow()

  private val webhookRequestGeneration = AtomicInteger(0)

  fun revealWebhookForm() {
    viewModelScope.launch {
      // Loading/Unavailable/NotConfiguredの間にreveal要求が来た場合は何もしない。ここでrevealedを
      // 立てると、実際にはsettingsを読めていないのにisWebhookFormVisibleが空フォームを「既存設定の
      // 編集フォーム」として表示してしまい、保存時に既存URL/Header/Body template/secretを
      // 空の値で上書きし得る。
      val current = webhookSettingsRepository.settings.first()
      if (current is WebhookSettingsState.Configured) {
        _webhookFormState.value = current.settings.toFormState()
        _webhookFormRevealed.value = true
      }
    }
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

  /**
   * 編集でも世代を進めることで、保存開始後・DataStore write完了前に行われた編集内容を、古いsave/deleteの
   * 完了処理がクリアしてしまわないようにする。あわせてrevealedを立てるのは、保存完了で永続状態が
   * Configuredへ変わった際に、未保存の編集内容が入ったフォームが閉じてしまうのを防ぐため
   * (編集できている時点でフォームは表示済みのため、これで新たにsecretが露出することはない)。
   */
  private fun updateWebhookForm(transform: (WebhookFormState) -> WebhookFormState) {
    webhookRequestGeneration.incrementAndGet()
    _webhookFormRevealed.value = true
    _webhookFormState.update(transform)
  }

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
        if (generation == webhookRequestGeneration.get()) {
          _webhookFormRevealed.value = false
          _webhookFormState.value = WebhookFormState()
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        if (generation == webhookRequestGeneration.get()) _webhookSaveFailed.value = true
      }
    }
  }

  fun deleteWebhookSettings() {
    val generation = webhookRequestGeneration.incrementAndGet()
    _webhookValidationErrors.value = emptyList()
    _webhookSaveFailed.value = false
    viewModelScope.launch {
      try {
        webhookSettingsRepository.clear()
        if (generation == webhookRequestGeneration.get()) {
          _webhookFormRevealed.value = false
          _webhookFormState.value = WebhookFormState()
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        if (generation == webhookRequestGeneration.get()) _webhookSaveFailed.value = true
      }
    }
  }
}

data class WebhookFormState(
  val url: String = "",
  val headers: List<WebhookHeader> = emptyList(),
  val bodyTemplate: String = "",
)

private fun WebhookSettings.toFormState() = WebhookFormState(url = url, headers = headers, bodyTemplate = bodyTemplate)
