package info.bvlion.journalingpost.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/** 「フィードバックを送る」の送信先。淡香用として確定済み(#73)。 */
private const val FEEDBACK_URL = "https://contact.ambitious-i.net/usukou"

/** 「プライバシーポリシー」の公開先。 */
private const val PRIVACY_POLICY_URL = "https://journaling.ambitious-i.net/privacy-policy"

/**
 * 淡香のGoogle Playストアページを開く。Play Storeアプリが無い・無効化されている等で market:// を
 * 開けない端末では、同じストアページのWeb版へフォールバックする。
 */
fun openStoreListingForReview(context: Context) {
  val appId = context.packageName
  val playStore = Intent(Intent.ACTION_VIEW, "market://details?id=$appId".toUri())
  val web = Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$appId".toUri())
  if (!context.startActivitySafely(playStore)) context.startActivitySafely(web)
}

fun openFeedbackForm(context: Context) {
  context.startActivitySafely(Intent(Intent.ACTION_VIEW, FEEDBACK_URL.toUri()))
}

fun openPrivacyPolicy(context: Context) {
  context.startActivitySafely(Intent(Intent.ACTION_VIEW, PRIVACY_POLICY_URL.toUri()))
}

/**
 * 対応アプリが無い端末でも落とさない。外部導線はいずれも記録の継続に必須ではないため、
 * 開けなかった場合は通知せず何もしない。
 */
private fun Context.startActivitySafely(intent: Intent): Boolean = try {
  startActivity(intent)
  true
} catch (e: ActivityNotFoundException) {
  false
}
