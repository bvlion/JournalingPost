package info.bvlion.journalingpost

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import info.bvlion.journalingpost.settings.AnalysisIntegration
import info.bvlion.journalingpost.settings.AnalysisIntegrationRepository
import info.bvlion.journalingpost.webhook.WebhookHeader
import info.bvlion.journalingpost.webhook.WebhookSettings
import info.bvlion.journalingpost.webhook.WebhookSettingsOverview
import info.bvlion.journalingpost.webhook.WebhookSettingsRepository
import info.bvlion.journalingpost.webhook.WebhookSettingsState
import info.bvlion.journalingpost.webhook.WebhookSettingsValidator
import info.bvlion.journalingpost.webhook.toOverview
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
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
  private val analysisIntegrationRepository: AnalysisIntegrationRepository,
  private val webhookSettingsRepository: WebhookSettingsRepository,
) : ViewModel() {
  /** 実際に有効になっている解析・連携。CUSTOM_WEBHOOKなら保存済みWebhook設定が存在する。 */
  val analysisIntegration: StateFlow<AnalysisIntegration> = analysisIntegrationRepository.analysisIntegration
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnalysisIntegration.NONE)

  // Webhookが未設定のままCustom Webhookを選んだ状態。まだ有効化はせず、設定フォームへ進むための
  // 画面上の選択としてのみ扱う(設定を保存しないまま画面を離れれば「使用しない」のまま)。
  private val _pendingCustomWebhookSelection = MutableStateFlow(false)

  private val selectedIntegrationFlow: Flow<AnalysisIntegration> = combine(
    analysisIntegrationRepository.analysisIntegration,
    _pendingCustomWebhookSelection,
  ) { integration, pendingCustomWebhook ->
    if (pendingCustomWebhook) AnalysisIntegration.CUSTOM_WEBHOOK else integration
  }

  /** 画面のradioが示す選択。未確定のCustom Webhook選択を含むため、[analysisIntegration]とは一致しないことがある。 */
  val selectedAnalysisIntegration: StateFlow<AnalysisIntegration> = selectedIntegrationFlow
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnalysisIntegration.NONE)

  private val _integrationSaveFailed = MutableStateFlow(false)
  val integrationSaveFailed: StateFlow<Boolean> = _integrationSaveFailed.asStateFlow()

  // 呼び出し完了時に、より新しい選択が既に行われていないかを確認するための世代番号。
  private val integrationRequestGeneration = AtomicInteger(0)

  /**
   * 使用しないを選んだ場合はその時点で永続化する。Custom Webhookは、保存済み設定がある場合だけ
   * この時点で有効化し、未設定なら有効化せずに設定フォームへ進む(有効なのに送信先が無い状態を
   * 通常操作で作らないため)。未設定から選んだ場合は、設定の保存に成功した時点で有効になる。
   */
  fun setAnalysisIntegration(integration: AnalysisIntegration) {
    val generation = integrationRequestGeneration.incrementAndGet()
    _integrationSaveFailed.value = false
    if (integration != AnalysisIntegration.CUSTOM_WEBHOOK) {
      _pendingCustomWebhookSelection.value = false
      // Custom Webhookを使わない選択にした時点で編集を終了し、画面に出ていない編集状態を残さない。
      cancelWebhookEdit()
      viewModelScope.launch { persistAnalysisIntegration(integration, generation) }
      return
    }
    _pendingCustomWebhookSelection.value = true
    viewModelScope.launch {
      val configured = webhookSettingsRepository.settings.first() is WebhookSettingsState.Configured
      if (generation != integrationRequestGeneration.get()) return@launch
      if (!configured) return@launch
      persistAnalysisIntegration(integration, generation)
      // 永続化できたなら選択はそちらで表される。失敗した場合も、暫定の選択を残さず永続値へ戻す。
      if (generation == integrationRequestGeneration.get()) _pendingCustomWebhookSelection.value = false
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

  // Loading/Unavailableと「本当に未設定」(NotConfigured)を区別する。読み込み中・読み込み不能の間は
  // 新規設定フォームを確定表示しない(authoritativeな状態が分かる前に、既存設定を誤って
  // 上書きしかねない編集画面を開かせないため)。
  private val webhookSettingsState: StateFlow<WebhookSettingsState> = webhookSettingsRepository.settings
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WebhookSettingsState.Loading)

  /** 画面へはsecretを含むWebhookSettingsではなく、確認用の要約だけを渡す。 */
  val webhookOverview: StateFlow<WebhookSettingsOverview> = webhookSettingsState
    .map { it.toOverview() }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WebhookSettingsOverview.Loading)

  val isWebhookConfigured: StateFlow<Boolean> = webhookSettingsState
    .map { it is WebhookSettingsState.Configured }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

  private val _webhookFormState = MutableStateFlow(WebhookFormState())
  val webhookFormState: StateFlow<WebhookFormState> = _webhookFormState.asStateFlow()

  // 保存済みsecret(Header value/Body template等)は、EDITINGになるまでフォームへ展開しない。
  private val _webhookFormOrigin = MutableStateFlow(WebhookFormOrigin.CLOSED)

  /**
   * Custom Webhookを選んでいないときは編集UI自体を出さない。選んでいてまだ未設定なら、
   * 新規入力のためのフォームを最初から表示する。保存済み(Configured)の場合は、フォームの内容が
   * その保存済み設定に対応している(EDITING)ときだけ表示する。Loading/Unavailableの間は
   * originに関わらずフォームを出さない。
   *
   * NEWをConfiguredの表示条件に含めないのは、未設定のつもりで入力している最中にlegacy migration等で
   * 保存済み設定が現れた場合に、部分的な入力内容を「その設定の編集フォーム」として扱わないため。
   * そのまま保存すると、見えていない既存のURL/Header/Body template/secretを上書きしてしまう。
   *
   * 解析・連携の選択はstateIn済みのStateFlowではなくrepositoryのflowを使う。初期値を持つStateFlowだと、
   * 実際に保存された選択が届く前の既定値で一瞬フォームを表示してしまう。
   */
  val isWebhookEditing: StateFlow<Boolean> = combine(
    webhookSettingsState,
    _webhookFormOrigin,
    selectedIntegrationFlow,
  ) { state, origin, selected ->
    when {
      selected != AnalysisIntegration.CUSTOM_WEBHOOK -> false
      state is WebhookSettingsState.Configured -> origin == WebhookFormOrigin.EDITING
      state is WebhookSettingsState.NotConfigured -> true
      else -> false
    }
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

  private val _webhookValidationErrors = MutableStateFlow<List<WebhookSettingsValidator.ValidationError>>(emptyList())
  val webhookValidationErrors: StateFlow<List<WebhookSettingsValidator.ValidationError>> = _webhookValidationErrors.asStateFlow()

  private val _webhookOperationFailure = MutableStateFlow<WebhookOperationFailure?>(null)
  val webhookOperationFailure: StateFlow<WebhookOperationFailure?> = _webhookOperationFailure.asStateFlow()

  private val webhookRequestGeneration = AtomicInteger(0)

  /**
   * 設定画面へ入り直したときは、前回の操作結果であるerrorと、前回開いた編集状態を持ち越さない
   * (secretを含み得るフォームは、入るたびに閉じた状態から始める)。画面内での回転等では呼ばれない
   * ため、入力途中の値が消えるのは画面を離れて入り直した場合だけになる。
   */
  fun onSettingsOpened() {
    _integrationSaveFailed.value = false
    // 設定を保存しないまま前回離脱していた場合、Custom Webhookの選択は確定していないため引き継がない。
    _pendingCustomWebhookSelection.value = false
    cancelWebhookEdit()
  }

  /**
   * 編集をやめて確認状態へ戻す。世代を進めるのは、進行中のsave/deleteが後から完了しても
   * この時点のフォーム状態を書き換えないようにするため。
   */
  fun cancelWebhookEdit() {
    webhookRequestGeneration.incrementAndGet()
    _webhookValidationErrors.value = emptyList()
    _webhookOperationFailure.value = null
    _webhookFormOrigin.value = WebhookFormOrigin.CLOSED
    _webhookFormState.value = WebhookFormState()
  }

  fun startWebhookEdit() {
    val generation = webhookRequestGeneration.incrementAndGet()
    // 前回の保存失敗時のerrorは、編集を始め直した時点では過去の結果として扱う。
    _webhookValidationErrors.value = emptyList()
    _webhookOperationFailure.value = null
    viewModelScope.launch {
      // Loading/Unavailableの間に編集要求が来た場合は何もしない。ここで編集状態にすると、実際には
      // settingsを読めていないのにisWebhookEditingが空フォームを「既存設定の編集フォーム」として
      // 表示してしまい、保存時に既存URL/Header/Body template/secretを空の値で上書きし得る。
      // NotConfiguredはCustom Webhook選択時に新規入力フォームが出るため、ここでは何もしない。
      val current = webhookSettingsRepository.settings.first()
      if (generation != webhookRequestGeneration.get()) return@launch
      if (current is WebhookSettingsState.Configured) {
        _webhookFormState.value = current.settings.toFormState()
        _webhookFormOrigin.value = WebhookFormOrigin.EDITING
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
   * 完了処理がクリアしてしまわないようにする。originはCLOSEDからのみNEWへ進め、EDITINGは維持する
   * (保存済み設定を開いて編集している状態を、未設定からの新規入力へ降格させない)。
   */
  private fun updateWebhookForm(transform: (WebhookFormState) -> WebhookFormState) {
    webhookRequestGeneration.incrementAndGet()
    _webhookFormOrigin.compareAndSet(WebhookFormOrigin.CLOSED, WebhookFormOrigin.NEW)
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
    _webhookOperationFailure.value = null
    viewModelScope.launch {
      val saved = try {
        webhookSettingsRepository.save(WebhookSettings(form.url, validation.normalizedHeaders, form.bodyTemplate))
        true
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        if (generation == webhookRequestGeneration.get()) _webhookOperationFailure.value = WebhookOperationFailure.SAVE
        false
      }
      if (!saved) return@launch

      // 送信先が保存できた時点で初めてCustom Webhookを有効にする。順序を逆にすると、保存に失敗した
      // 場合に「Custom Webhookが有効なのに設定がない」状態を作ってしまう。
      persistAnalysisIntegration(AnalysisIntegration.CUSTOM_WEBHOOK, integrationRequestGeneration.incrementAndGet())
      _pendingCustomWebhookSelection.value = false

      if (generation == webhookRequestGeneration.get()) {
        _webhookFormOrigin.value = WebhookFormOrigin.CLOSED
        _webhookFormState.value = WebhookFormState()
      } else {
        // 保存中にユーザーが編集を続けていた場合、永続状態は今保存した自分の設定なので、
        // 続きの編集はその設定に対するEDITINGとして扱う(フォームを閉じて入力を捨てない)。
        _webhookFormOrigin.compareAndSet(WebhookFormOrigin.NEW, WebhookFormOrigin.EDITING)
      }
    }
  }

  /**
   * 「Custom Webhookを使う選択なのに設定がない」状態を残さないため、設定の削除とあわせて解析・連携を
   * NONEへ戻す。順序を固定しているのは、先にclear()して選択の更新が失敗した場合にその不整合が
   * 残るため。逆順であれば、途中で失敗しても「使用しない + 保存済み設定は残る」という有効な状態になる。
   */
  fun deleteWebhookSettings() {
    val generation = webhookRequestGeneration.incrementAndGet()
    // 削除は解析・連携の選択も変えるため、進行中のmode保存の完了処理より新しい要求として扱う。
    integrationRequestGeneration.incrementAndGet()
    _webhookValidationErrors.value = emptyList()
    _webhookOperationFailure.value = null
    _integrationSaveFailed.value = false
    _pendingCustomWebhookSelection.value = false
    viewModelScope.launch {
      try {
        analysisIntegrationRepository.setAnalysisIntegration(AnalysisIntegration.NONE)
        webhookSettingsRepository.clear()
        if (generation == webhookRequestGeneration.get()) {
          _webhookFormOrigin.value = WebhookFormOrigin.CLOSED
          _webhookFormState.value = WebhookFormState()
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        if (generation == webhookRequestGeneration.get()) _webhookOperationFailure.value = WebhookOperationFailure.DELETE
      }
    }
  }
}

/**
 * フォームの内容が何に対応しているか。CLOSEDは編集していない状態、NEWは未設定からの新規入力、
 * EDITINGは保存済み設定に対応した編集。NEWとEDITINGを区別するのは、入力中に保存済み設定が
 * 現れた場合(legacy migration等)に、部分入力を既存設定の編集として扱わないため。
 */
private enum class WebhookFormOrigin {
  CLOSED,
  NEW,
  EDITING,
}

/** 失敗した操作によって表示すべき文言が変わるため、boolean 2つではなくどちらの操作かを持つ。 */
enum class WebhookOperationFailure {
  SAVE,
  DELETE,
}

data class WebhookFormState(
  val url: String = "",
  val headers: List<WebhookHeader> = emptyList(),
  val bodyTemplate: String = "",
)

private fun WebhookSettings.toFormState() = WebhookFormState(url = url, headers = headers, bodyTemplate = bodyTemplate)
