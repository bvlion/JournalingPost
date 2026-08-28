package info.bvlion.journalingpost.webhook

import info.bvlion.journalingpost.BuildConfig

/** LEGACY_*はdebug buildでのみlocal.propertiesの値を持ち、releaseでは常に空文字になる(app/build.gradle.kts参照)。 */
object LegacyWebhookConfigProvider {
  fun get(): LegacyWebhookConfig? = legacyWebhookConfigOrNull(
    postUrl = BuildConfig.LEGACY_POST_URL,
    teamId = BuildConfig.LEGACY_TEAM_ID,
    token = BuildConfig.LEGACY_TOKEN,
    channel = BuildConfig.LEGACY_CHANNEL,
    user = BuildConfig.LEGACY_USER,
  )
}

internal fun legacyWebhookConfigOrNull(
  postUrl: String,
  teamId: String,
  token: String,
  channel: String,
  user: String,
): LegacyWebhookConfig? {
  if (listOf(postUrl, teamId, token, channel, user).any { it.isBlank() }) return null
  return LegacyWebhookConfig(postUrl, teamId, token, channel, user)
}
