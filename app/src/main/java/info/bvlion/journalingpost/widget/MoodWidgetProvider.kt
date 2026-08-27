package info.bvlion.journalingpost.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.util.SizeF
import android.widget.RemoteViews
import info.bvlion.journalingpost.R
import info.bvlion.journalingpost.mood.Mood

/**
 * ホーム画面Widget。#2で使っていた5種の気分を直接タップできる状態で表示し、
 * タップでMoodEntryActivityを開くだけの薄いreceiver。
 *
 * 5種はMoodを5段階に固定する仕様ではなく、現時点で直接残したい気分の初期候補。
 * 将来ユーザーが表示するMood/絵文字を選べる余地を残すため、Widget側の表示リストは
 * このcompanion objectのみで完結する最小構成にしている。
 *
 * サイズが小さいうちは絵文字だけのcompactレイアウトを使い、縦方向に十分な高さが
 * 確保できたときだけ、意味が分かるラベル付きレイアウトへ切り替える。
 * どちらのRemoteViewsもMood毎に同じview idを持つため、クリック設定は共通化している。
 */
class MoodWidgetProvider : AppWidgetProvider() {
  override fun onUpdate(
    context: Context,
    appWidgetManager: AppWidgetManager,
    appWidgetIds: IntArray,
  ) {
    appWidgetIds.forEach { appWidgetId ->
      val remoteViews = RemoteViews(
        mapOf(
          SizeF(COMPACT_MIN_WIDTH_DP, COMPACT_MIN_HEIGHT_DP) to
            buildMoodRemoteViews(context, R.layout.widget_mood),
          SizeF(COMPACT_MIN_WIDTH_DP, LABELED_MIN_HEIGHT_DP) to
            buildMoodRemoteViews(context, R.layout.widget_mood_expanded),
        ),
      )
      appWidgetManager.updateAppWidget(appWidgetId, remoteViews)
    }
  }

  private companion object {
    // widget_mood_info.xmlのminWidth/minHeightと同じ、2x1相当のcompact表示の最小サイズ。
    const val COMPACT_MIN_WIDTH_DP = 110f
    const val COMPACT_MIN_HEIGHT_DP = 40f

    // Androidのセルサイズ計算式(70dp×セル数-30dp)で3セル分の高さ。
    // compactの5列だけでは1行あたり手狭なラベルを、縦3セル以上に広げたときに表示する。
    const val LABELED_MIN_HEIGHT_DP = 180f

    val MOOD_VIEW_IDS = listOf(
      Mood.VERY_SAD to R.id.mood_target_very_sad,
      Mood.SAD to R.id.mood_target_sad,
      Mood.NEUTRAL to R.id.mood_target_neutral,
      Mood.HAPPY to R.id.mood_target_happy,
      Mood.VERY_HAPPY to R.id.mood_target_very_happy,
    )

    fun buildMoodRemoteViews(context: Context, layoutRes: Int): RemoteViews {
      val remoteViews = RemoteViews(context.packageName, layoutRes)
      MOOD_VIEW_IDS.forEach { (mood, viewId) ->
        remoteViews.setOnClickPendingIntent(viewId, moodPendingIntent(context, mood))
      }
      return remoteViews
    }

    fun moodPendingIntent(context: Context, mood: Mood): PendingIntent {
      val intent = Intent(context, MoodEntryActivity::class.java)
        .putExtra(MoodEntryActivity.EXTRA_MOOD, mood.name)
      // requestCodeをmood毎に変えないと、Intentのextra以外(action/data/component)が同一のため
      // 複数のPendingIntentが同一視され、直前にタップした気分のextraで上書きされてしまう。
      return PendingIntent.getActivity(
        context,
        mood.ordinal,
        intent,
        PendingIntent.FLAG_IMMUTABLE,
      )
    }
  }
}
