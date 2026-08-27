package info.bvlion.journalingpost.widget

import android.content.Context
import android.os.Build
import androidx.glance.appwidget.GlanceAppWidgetManager

private const val PREFS_NAME = "mood_widget_preview"
private const val KEY_PREVIEW_REGISTERED = "registered"

/**
 * Widget pickerのgenerated preview(Android 15+)を登録する。
 *
 * MoodWidgetはほぼ静的なquick action Widgetのため、公式ガイド
 * (developer.android.com/develop/ui/compose/glance/generated-previews)が
 * quick action Widget向けに示す「アプリ初回起動時に登録する」方針に沿い、
 * 初回成功時のみSharedPreferencesへ記録して以降は呼び出さない
 * (定期実行やWorkManagerは使わない)。
 *
 * [GlanceAppWidgetManager.setWidgetPreviews]自体はAndroid 15未満では内部で
 * no-opになる(ライブラリ側で保証)が、lintのNewApiチェックはそれを認識しないため、
 * ここでも明示的にAPIレベルを確認してから呼び出す。setWidgetPreviewsには
 * rate limit(概ね1時間に2回程度)があるため、成功するまでは次回起動時に
 * 再試行し、成功後は再登録しない。
 */
suspend fun registerMoodWidgetPreviewOnce(context: Context) {
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return

  val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  if (prefs.getBoolean(KEY_PREVIEW_REGISTERED, false)) return

  val result = GlanceAppWidgetManager(context).setWidgetPreviews(MoodWidgetProvider::class)
  if (result == GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_SUCCESS) {
    prefs.edit().putBoolean(KEY_PREVIEW_REGISTERED, true).apply()
  }
}
