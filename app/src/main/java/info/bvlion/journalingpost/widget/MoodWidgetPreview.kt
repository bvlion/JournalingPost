package info.bvlion.journalingpost.widget

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.glance.appwidget.GlanceAppWidgetManager
import info.bvlion.journalingpost.BuildConfig
import kotlinx.coroutines.CancellationException

private const val PREFS_NAME = "mood_widget_preview"
private const val KEY_PREVIEW_REGISTERED_VERSION = "registered_version_code"
private const val KEY_PREVIEW_REFRESH_PENDING = "refresh_pending"
private const val KEY_LAST_REGISTRATION_ATTEMPT_AT = "last_registration_attempt_at"
private const val NO_VERSION_REGISTERED = -1
private const val NO_REGISTRATION_ATTEMPT = -1L
private const val REGISTRATION_RETRY_INTERVAL_MILLIS = 30 * 60 * 1_000L

/**
 * Widget pickerのgenerated preview(Android 15+)を登録する。
 *
 * MoodWidgetはほぼ静的なquick action Widgetのため、公式ガイド
 * (developer.android.com/develop/ui/compose/glance/generated-previews)が
 * quick action Widget向けに示す「アプリ初回起動時に登録する」方針に沿い、
 * 定期実行やWorkManagerは使わない。
 *
 * 登録済みかどうかは最後に登録へ成功した時点の[BuildConfig.VERSION_CODE]で管理する。
 * Mood設定の保存後は再登録要求を残し、次の条件で登録する。これにより、
 * - 同じアプリバージョン内では、Mood設定が変わらない限り成功後1回だけ呼び出す
 * - Mood設定保存後はWidget picker previewにも同じ内容を反映する
 * - 再登録要求は30分ごとに最大1回だけ実行し、platformのrate limitを超えない
 * - アプリを更新するとWidget UIが変わっていてもversionCodeが変わるため、
 *   更新後の初回起動時に自動的に再登録される
 * - rate limit等で失敗した場合は再登録要求を残し、次回起動時に再試行する
 *
 * 専用のPREVIEW_VERSION定数を手動管理する方式は、preview変更時に更新し忘れる要因を増やすため
 * 採用していない。
 *
 * [GlanceAppWidgetManager.setWidgetPreviews]自体はAndroid 15未満では内部で
 * no-opになる(ライブラリ側で保証)が、lintのNewApiチェックはそれを認識しないため、
 * ここでも明示的にAPIレベルを確認してから呼び出す。
 *
 * [GlanceAppWidgetManager.setWidgetPreviews]が例外を投げた場合も、preview登録の
 * 失敗がアプリ起動へ波及しないようcatchする。この場合もversionCodeは保存せず、
 * 次回起動時に同じversionCodeのまま再試行される。
 */
@SuppressLint("NewApi")
suspend fun registerMoodWidgetPreviewOnce(
  context: Context,
  shouldRefresh: Boolean = false,
  preferences: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
  currentTimeMillis: Long = System.currentTimeMillis(),
  platformVersion: Int = Build.VERSION.SDK_INT,
  registerPreviews: suspend () -> Int = {
    GlanceAppWidgetManager(context).setWidgetPreviews(MoodWidgetProvider::class)
  },
) {
  if (platformVersion < Build.VERSION_CODES.VANILLA_ICE_CREAM) return

  if (shouldRefresh) preferences.edit().putBoolean(KEY_PREVIEW_REFRESH_PENDING, true).apply()

  val registeredVersion = preferences.getInt(KEY_PREVIEW_REGISTERED_VERSION, NO_VERSION_REGISTERED)
  val isRefreshPending = preferences.getBoolean(KEY_PREVIEW_REFRESH_PENDING, false)
  if (registeredVersion == BuildConfig.VERSION_CODE && !isRefreshPending) return

  val lastAttemptAt = preferences.getLong(KEY_LAST_REGISTRATION_ATTEMPT_AT, NO_REGISTRATION_ATTEMPT)
  if (
    lastAttemptAt != NO_REGISTRATION_ATTEMPT &&
    currentTimeMillis >= lastAttemptAt &&
    currentTimeMillis - lastAttemptAt < REGISTRATION_RETRY_INTERVAL_MILLIS
  ) {
    return
  }

  preferences.edit().putLong(KEY_LAST_REGISTRATION_ATTEMPT_AT, currentTimeMillis).apply()

  val result = try {
    registerPreviews()
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    return
  }
  if (result == GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_SUCCESS) {
    preferences.edit()
      .putInt(KEY_PREVIEW_REGISTERED_VERSION, BuildConfig.VERSION_CODE)
      .putBoolean(KEY_PREVIEW_REFRESH_PENDING, false)
      .apply()
  }
}
