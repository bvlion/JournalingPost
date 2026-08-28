package info.bvlion.journalingpost.poster

import info.bvlion.journalingpost.BuildConfig

/**
 * local.properties未設定やCI環境では、BuildConfigの各フィールドが空文字列または
 * "null"という文字列値になる。この状態のままWebhookへネットワーク送信しないための
 * 判定にのみ使う。
 */
object WebhookConfig {
  val isConfigured: Boolean
    get() = isWebhookConfigValid(
      BuildConfig.POST_URL,
      BuildConfig.TEAM_ID,
      BuildConfig.TOKEN,
      BuildConfig.CHANNEL,
      BuildConfig.USER,
    )
}

internal fun isWebhookConfigValid(vararg values: String): Boolean =
  values.all { it.isNotBlank() && it != "null" }
