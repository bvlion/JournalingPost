package info.bvlion.journalingpost.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/** マニフェストに登録するreceiver。実際のUIロジックはMoodWidgetへ委譲する。 */
class MoodWidgetReceiver : GlanceAppWidgetReceiver() {
  override val glanceAppWidget: GlanceAppWidget = MoodWidget()
}
