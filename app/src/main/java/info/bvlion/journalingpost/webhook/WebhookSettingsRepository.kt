package info.bvlion.journalingpost.webhook

import kotlinx.coroutines.flow.Flow

sealed interface WebhookSettingsState {
  data object Loading : WebhookSettingsState
  data object Unavailable : WebhookSettingsState
  data object NotConfigured : WebhookSettingsState
  data class Configured(val settings: WebhookSettings) : WebhookSettingsState
}

interface WebhookSettingsRepository {
  val settings: Flow<WebhookSettingsState>

  suspend fun save(settings: WebhookSettings)
}
