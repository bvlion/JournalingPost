package info.bvlion.journalingpost.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private const val FEEDBACK_URL = "https://contact.ambitious-i.net/usukou"

private const val PRIVACY_POLICY_URL = "https://journaling.ambitious-i.net/privacy-policy"
private const val PLAY_STORE_PACKAGE_NAME = "com.android.vending"

fun openStoreListingForReview(context: Context) {
  val appId = context.packageName
  val playStore = Intent(Intent.ACTION_VIEW, "market://details?id=$appId".toUri())
    .setPackage(PLAY_STORE_PACKAGE_NAME)
  val web = Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$appId".toUri())
  try {
    context.startActivity(playStore)
  } catch (e: ActivityNotFoundException) {
    context.openExternalLink(web)
  }
}

fun openFeedbackForm(context: Context, supportId: String? = null, analysisDate: LocalDate? = null) {
  val feedbackUri = FEEDBACK_URL.toUri().buildUpon().apply {
    if (supportId != null && analysisDate != null) {
      appendQueryParameter("support_id", supportId)
      appendQueryParameter("analysis_date", analysisDate.format(DateTimeFormatter.BASIC_ISO_DATE))
    }
  }.build()
  context.openExternalLink(Intent(Intent.ACTION_VIEW, feedbackUri))
}

fun openPrivacyPolicy(context: Context) {
  context.openExternalLink(Intent(Intent.ACTION_VIEW, PRIVACY_POLICY_URL.toUri()))
}

private fun Context.openExternalLink(intent: Intent) = try {
  startActivity(intent)
} catch (e: ActivityNotFoundException) {
  // 記録の継続に必須ではない補助導線のため、対応アプリが無い場合は通知しない。
}
