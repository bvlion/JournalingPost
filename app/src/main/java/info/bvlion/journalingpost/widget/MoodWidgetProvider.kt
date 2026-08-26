package info.bvlion.journalingpost.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import info.bvlion.journalingpost.R
import info.bvlion.journalingpost.mood.Mood

/** ホーム画面Widget。固定5種の気分ボタンを表示し、タップでMoodEntryActivityを開くだけの薄いreceiver。 */
class MoodWidgetProvider : AppWidgetProvider() {
  override fun onUpdate(
    context: Context,
    appWidgetManager: AppWidgetManager,
    appWidgetIds: IntArray,
  ) {
    appWidgetIds.forEach { appWidgetId ->
      val remoteViews = RemoteViews(context.packageName, R.layout.widget_mood)
      MOOD_BUTTON_IDS.forEach { (mood, viewId) ->
        remoteViews.setOnClickPendingIntent(viewId, moodPendingIntent(context, mood))
      }
      appWidgetManager.updateAppWidget(appWidgetId, remoteViews)
    }
  }

  private companion object {
    val MOOD_BUTTON_IDS = listOf(
      Mood.VERY_SAD to R.id.mood_button_very_sad,
      Mood.SAD to R.id.mood_button_sad,
      Mood.NEUTRAL to R.id.mood_button_neutral,
      Mood.HAPPY to R.id.mood_button_happy,
      Mood.VERY_HAPPY to R.id.mood_button_very_happy,
    )

    fun moodPendingIntent(context: Context, mood: Mood): PendingIntent {
      val intent = Intent(context, MoodEntryActivity::class.java)
        .putExtra(MoodEntryActivity.EXTRA_MOOD, mood.name)
      // requestCodeをmood毎に変えないと、Intentのextra以外(action/data/component)が同一のため
      // 5つのPendingIntentが同一視され、直前にタップした気分のextraで上書きされてしまう。
      return PendingIntent.getActivity(
        context,
        mood.ordinal,
        intent,
        PendingIntent.FLAG_IMMUTABLE,
      )
    }
  }
}
