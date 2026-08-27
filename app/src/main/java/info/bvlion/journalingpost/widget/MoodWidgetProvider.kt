package info.bvlion.journalingpost.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
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
 */
class MoodWidgetProvider : AppWidgetProvider() {
  override fun onUpdate(
    context: Context,
    appWidgetManager: AppWidgetManager,
    appWidgetIds: IntArray,
  ) {
    appWidgetIds.forEach { appWidgetId ->
      val remoteViews = RemoteViews(context.packageName, R.layout.widget_mood)
      MOOD_VIEW_IDS.forEach { (mood, viewId) ->
        remoteViews.setOnClickPendingIntent(viewId, moodPendingIntent(context, mood))
      }
      appWidgetManager.updateAppWidget(appWidgetId, remoteViews)
    }
  }

  private companion object {
    val MOOD_VIEW_IDS = listOf(
      Mood.VERY_SAD to R.id.mood_text_very_sad,
      Mood.SAD to R.id.mood_text_sad,
      Mood.NEUTRAL to R.id.mood_text_neutral,
      Mood.HAPPY to R.id.mood_text_happy,
      Mood.VERY_HAPPY to R.id.mood_text_very_happy,
    )

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
