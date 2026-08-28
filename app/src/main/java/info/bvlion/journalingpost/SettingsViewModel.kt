package info.bvlion.journalingpost

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import info.bvlion.journalingpost.settings.RecordMode
import info.bvlion.journalingpost.settings.RecordModeRepository
import info.bvlion.journalingpost.webhook.WebhookHeader
import info.bvlion.journalingpost.webhook.WebhookSettings
import info.bvlion.journalingpost.webhook.WebhookSettingsRepository
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

  val isWebhookConfigured: StateFlow<Boolean> = webhookSettingsRepository.settings
    .map { it != null }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

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

  private val _webhookFormState = MutableStateFlow(WebhookFormState())
  val webhookFormState: StateFlow<WebhookFormState> = _webhookFormState.asStateFlow()

  // ユーザーが「表示して編集」を選んだ状態。保存済みsecret(Header value/Body template等)は、
  // これがtrueになるまでフォームへ展開しない。
  private val _webhookFormRevealed = MutableStateFlow(false)

  /**
   * 未設定の場合は新規入力のため常にフォームを表示し、設定済みの場合はユーザーが明示的に
   * 表示・編集を選ぶまで(_webhookFormRevealed)フォームを表示しない。isWebhookConfiguredが
   * revealedより優先して変化しても、trueになった側でフォームが出続けるようにOR条件にする。
   */
  val isWebhookFormVisible: StateFlow<Boolean> = combine(isWebhookConfigured, _webhookFormRevealed) { configured, revealed ->
    !configured || revealed
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

  private val _webhookValidationErrors = MutableStateFlow<List<WebhookSettingsValidator.ValidationError>>(emptyList())
  val webhookValidationErrors: StateFlow<List<WebhookSettingsValidator.ValidationError>> = _webhookValidationErrors.asStateFlow()

  private val _webhookSaveFailed = MutableStateFlow(false)
  val webhookSaveFailed: StateFlow<Boolean> = _webhookSaveFailed.asStateFlow()

  private val webhookRequestGeneration = AtomicInteger(0)

  init {
    viewModelScope.launch {
      // 未設定の場合だけ、初回の認証済みsnapshotで新規入力フォームを開く。設定済みの場合は
      // revealWebhookForm()を呼ぶまでフォーム内容を読み込まない(secretを画面へ先読み表示しない)。
      if (webhookSettingsRepository.settings.first() == null) {
        _webhookFormRevealed.value = true
      }
    }
  }

  fun revealWebhookForm() {
    viewModelScope.launch {
      val current = webhookSettingsRepository.settings.first()
      _webhookFormState.value = current?.toFormState() ?: WebhookFormState()
      _webhookFormRevealed.value = true
    }
  }

  fun updateWebhookUrl(url: String) {
    _webhookFormState.update { it.copy(url = url) }
  }

  fun addWebhookHeader() {
    _webhookFormState.update { it.copy(headers = it.headers + WebhookHeader(name = "", value = "")) }
  }

  fun removeWebhookHeader(index: Int) {
    _webhookFormState.update { state -> state.copy(headers = state.headers.filterIndexed { i, _ -> i != index }) }
  }

  fun updateWebhookHeaderName(index: Int, name: String) {
    _webhookFormState.update { state ->
      state.copy(headers = state.headers.mapIndexed { i, header -> if (i == index) header.copy(name = name) else header })
    }
  }

  fun updateWebhookHeaderValue(index: Int, value: String) {
    _webhookFormState.update { state ->
      state.copy(headers = state.headers.mapIndexed { i, header -> if (i == index) header.copy(value = value) else header })
    }
  }

  fun updateWebhookBodyTemplate(bodyTemplate: String) {
    _webhookFormState.update { it.copy(bodyTemplate = bodyTemplate) }
  }

  fun saveWebhookSettings() {
    val generation = webhookRequestGeneration.incrementAndGet()
    val form = _webhookFormState.value
    val errors = WebhookSettingsValidator.validate(form.url, form.headers, form.bodyTemplate)
    if (errors.isNotEmpty()) {
      _webhookValidationErrors.value = errors
      return
    }
    _webhookValidationErrors.value = emptyList()
    _webhookSaveFailed.value = false
    viewModelScope.launch {
      try {
        webhookSettingsRepository.save(WebhookSettings(form.url, form.headers, form.bodyTemplate))
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
