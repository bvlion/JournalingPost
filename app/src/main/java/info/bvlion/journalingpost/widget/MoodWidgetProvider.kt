package info.bvlion.journalingpost.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * マニフェストに登録するreceiver。実際のUIロジックはMoodWidgetへ委譲する。
 *
 * クラス名・ComponentNameは、Glance移行前(AppWidgetProvider + RemoteViews時代)から
 * 変えずに`MoodWidgetProvider`のまま維持している。既にホーム画面へ配置済みのWidget
 * instanceは`.widget.MoodWidgetProvider`というComponentNameに紐づいているため、
 * ここをリネームするとアプリ更新後に既存Widgetが引き継がれなくなる(消える/更新
 * されなくなる)おそれがある。実装だけをGlanceAppWidgetReceiverへ移行し、
 * ComponentNameの互換性は維持する。
 */
class MoodWidgetProvider : GlanceAppWidgetReceiver() {
  override val glanceAppWidget: GlanceAppWidget = MoodWidget()
}
