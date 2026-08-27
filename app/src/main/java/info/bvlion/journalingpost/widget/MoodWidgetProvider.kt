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
 * ホーム画面Widget。MOOD_ITEMSに並べたMoodを直接タップできる状態で表示し、
 * タップでMoodEntryActivityを開くだけの薄いreceiver。
 *
 * サイズが小さいうちは絵文字だけのcompactレイアウトを使い、縦方向に十分な高さが
 * 確保できたときだけ、意味が分かるラベル付きレイアウトへ切り替える。
 * どちらのレイアウトもMood毎のセル/行はwidget_mood_item.xml / widget_mood_expanded_item.xml
 * をaddViewで動的に追加しており、MOOD_ITEMSの件数を変えてもXMLの複製は不要。
 *
 * 現在MOOD_ITEMSはIssue #21のWidgetレイアウト評価用に10件（Mood.kt末尾の一時Mood含む）
 * まで増やしている。「気分は10種類」というプロダクト仕様ではなく、Mood数が増えた場合に
 * compact/横方向resize/縦方向expandedがそれぞれどう振る舞うかを実機確認するための仮データ。
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
          SizeF(COMPACT_MIN_WIDTH_DP, COMPACT_MIN_HEIGHT_DP) to buildCompactRemoteViews(context),
          SizeF(COMPACT_MIN_WIDTH_DP, LABELED_MIN_HEIGHT_DP) to buildExpandedRemoteViews(context),
        ),
      )
      appWidgetManager.updateAppWidget(appWidgetId, remoteViews)
    }
  }

  private data class MoodWidgetItem(
    val mood: Mood,
    val emojiRes: Int,
    val labelRes: Int,
    val descriptionRes: Int,
  )

  private companion object {
    // widget_mood_info.xmlのminWidth/minHeightと同じ、2x1相当のcompact表示の最小サイズ。
    const val COMPACT_MIN_WIDTH_DP = 110f
    const val COMPACT_MIN_HEIGHT_DP = 40f

    // Androidのセルサイズ計算式(70dp×セル数-30dp)で3セル分の高さ。
    // compactの横一列だけでは手狭なラベルを、縦3セル以上に広げたときに表示する。
    const val LABELED_MIN_HEIGHT_DP = 180f

    // Issue #21のWidgetレイアウト評価用の一時リスト。既存5Mood(VERY_SAD〜VERY_HAPPY)に
    // 評価用の仮Mood5件を加えて合計10件にしている。「10件が最終Mood数」という意味ではなく、
    // Mood数が増えたときのcompact/横方向resize/縦方向expandedの振る舞いを実機確認するため。
    val MOOD_ITEMS = listOf(
      MoodWidgetItem(Mood.ANGRY, R.string.mood_emoji_angry, R.string.mood_label_angry, R.string.mood_description_angry),
      MoodWidgetItem(Mood.VERY_SAD, R.string.mood_emoji_very_sad, R.string.mood_label_very_sad, R.string.mood_description_very_sad),
      MoodWidgetItem(Mood.ANXIOUS, R.string.mood_emoji_anxious, R.string.mood_label_anxious, R.string.mood_description_anxious),
      MoodWidgetItem(Mood.SAD, R.string.mood_emoji_sad, R.string.mood_label_sad, R.string.mood_description_sad),
      MoodWidgetItem(Mood.TEARFUL, R.string.mood_emoji_tearful, R.string.mood_label_tearful, R.string.mood_description_tearful),
      MoodWidgetItem(Mood.NEUTRAL, R.string.mood_emoji_neutral, R.string.mood_label_neutral, R.string.mood_description_neutral),
      MoodWidgetItem(Mood.CALM, R.string.mood_emoji_calm, R.string.mood_label_calm, R.string.mood_description_calm),
      MoodWidgetItem(Mood.HAPPY, R.string.mood_emoji_happy, R.string.mood_label_happy, R.string.mood_description_happy),
      MoodWidgetItem(Mood.VERY_HAPPY, R.string.mood_emoji_very_happy, R.string.mood_label_very_happy, R.string.mood_description_very_happy),
      MoodWidgetItem(Mood.ELATED, R.string.mood_emoji_elated, R.string.mood_label_elated, R.string.mood_description_elated),
    )

    fun buildCompactRemoteViews(context: Context): RemoteViews {
      val container = RemoteViews(context.packageName, R.layout.widget_mood)
      MOOD_ITEMS.forEach { item ->
        val itemViews = RemoteViews(context.packageName, R.layout.widget_mood_item)
        itemViews.setTextViewText(R.id.mood_item_emoji, context.getString(item.emojiRes))
        itemViews.setContentDescription(R.id.mood_item_emoji, context.getString(item.descriptionRes))
        itemViews.setOnClickPendingIntent(R.id.mood_item_emoji, moodPendingIntent(context, item.mood))
        container.addView(R.id.mood_row, itemViews)
      }
      return container
    }

    fun buildExpandedRemoteViews(context: Context): RemoteViews {
      val container = RemoteViews(context.packageName, R.layout.widget_mood_expanded)
      MOOD_ITEMS.forEach { item ->
        val itemViews = RemoteViews(context.packageName, R.layout.widget_mood_expanded_item)
        itemViews.setTextViewText(R.id.mood_expanded_item_emoji, context.getString(item.emojiRes))
        itemViews.setTextViewText(R.id.mood_expanded_item_label, context.getString(item.labelRes))
        itemViews.setContentDescription(R.id.mood_expanded_item_row, context.getString(item.descriptionRes))
        itemViews.setOnClickPendingIntent(R.id.mood_expanded_item_row, moodPendingIntent(context, item.mood))
        container.addView(R.id.mood_list, itemViews)
      }
      return container
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
