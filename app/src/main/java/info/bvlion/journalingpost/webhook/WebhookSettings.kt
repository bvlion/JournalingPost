package info.bvlion.journalingpost.webhook

import kotlinx.serialization.Serializable

@Serializable
data class WebhookSettings(
  val url: String,
  val headers: List<WebhookHeader>,
  /** 送信するrequest bodyのJSON template。placeholderは[WebhookBodyTemplateRenderer]が展開する。 */
  val bodyTemplate: String,
)

@Serializable
data class WebhookHeader(
  val name: String,
  val value: String,
)
