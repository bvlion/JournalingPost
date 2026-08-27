package info.bvlion.journalingpost.widget

import android.content.Context
import android.os.Build
import androidx.glance.appwidget.GlanceAppWidgetManager
import info.bvlion.journalingpost.BuildConfig
import kotlinx.coroutines.CancellationException

private const val PREFS_NAME = "mood_widget_preview"
private const val KEY_PREVIEW_REGISTERED_VERSION = "registered_version_code"
private const val NO_VERSION_REGISTERED = -1

/**
 * Widget pickerのgenerated preview(Android 15+)を登録する。
 *
 * MoodWidgetはほぼ静的なquick action Widgetのため、公式ガイド
 * (developer.android.com/develop/ui/compose/glance/generated-previews)が
 * quick action Widget向けに示す「アプリ初回起動時に登録する」方針に沿い、
 * 定期実行やWorkManagerは使わない。
 *
 * 登録済みかどうかは単純なBooleanフラグではなく、最後に登録へ成功した時点の
 * [BuildConfig.VERSION_CODE]で管理する。これにより、
 * - 同じアプリバージョン内では成功後1回だけ呼び出す
 * - アプリを更新するとWidget UIが変わっていてもversionCodeが変わるため、
 *   更新後の初回起動時に自動的に再登録される
 * - rate limit等で失敗した場合はversionCodeを保存しないため、次回起動時に
 *   同じversionCodeのまま再試行される
 * という挙動になる。専用のPREVIEW_VERSION定数を手動管理する方式は、preview変更時に
 * 更新し忘れる要因を増やすため採用していない。アプリ更新ごとに1回程度の呼び出しは
 * rate limit(概ね1時間に2回程度)上も十分低頻度。
 *
 * [GlanceAppWidgetManager.setWidgetPreviews]自体はAndroid 15未満では内部で
 * no-opになる(ライブラリ側で保証)が、lintのNewApiチェックはそれを認識しないため、
 * ここでも明示的にAPIレベルを確認してから呼び出す。
 *
 * [GlanceAppWidgetManager.setWidgetPreviews]が例外を投げた場合も、preview登録の
 * 失敗がアプリ起動へ波及しないようcatchする。この場合もversionCodeは保存せず、
 * 次回起動時に同じversionCodeのまま再試行される。
 */
suspend fun registerMoodWidgetPreviewOnce(context: Context) {
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return

  val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  val registeredVersion = prefs.getInt(KEY_PREVIEW_REGISTERED_VERSION, NO_VERSION_REGISTERED)
  if (registeredVersion == BuildConfig.VERSION_CODE) return

  val result = try {
    GlanceAppWidgetManager(context).setWidgetPreviews(MoodWidgetProvider::class)
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    return
  }
  if (result == GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_SUCCESS) {
    prefs.edit().putInt(KEY_PREVIEW_REGISTERED_VERSION, BuildConfig.VERSION_CODE).apply()
  }
}
