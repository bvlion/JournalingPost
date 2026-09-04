package info.bvlion.journalingpost.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

private const val FEEDBACK_URL = "https://contact.ambitious-i.net/usukou"

private const val PRIVACY_POLICY_URL = "https://journaling.ambitious-i.net/privacy-policy"
private const val PLAY_STORE_PACKAGE_NAME = "com.android.vending"

fun openStoreListingForReview(context: Context) {
  val appId = context.packageName
  val playStore = Intent(Intent.ACTION_VIEW, "market://details?id=$appId".toUri())
    .setPackage(PLAY_STORE_PACKAGE_NAME)
  val web = Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$appId".toUri())
  if (!context.startActivitySafely(playStore)) context.startActivitySafely(web)
}

fun openFeedbackForm(context: Context) {
  context.startActivitySafely(Intent(Intent.ACTION_VIEW, FEEDBACK_URL.toUri()))
}

fun openPrivacyPolicy(context: Context) {
  context.startActivitySafely(Intent(Intent.ACTION_VIEW, PRIVACY_POLICY_URL.toUri()))
}

/** 外部導線はいずれも記録の継続に必須ではないため、開けなかった場合は通知しない。 */
private fun Context.startActivitySafely(intent: Intent): Boolean = try {
  startActivity(intent)
  true
} catch (e: ActivityNotFoundException) {
  false
}
