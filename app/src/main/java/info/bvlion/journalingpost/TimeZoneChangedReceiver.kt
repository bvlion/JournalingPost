package info.bvlion.journalingpost

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 端末timezone変更(`ACTION_TIMEZONE_CHANGED`)を受けて、自動解析(Issue #59)の予約を現在の
 * timezoneでの指定時刻へ計算し直す。ウィジェットからの記録だけでMainActivityを開かない利用でも、
 * 旧timezoneの予約が残らないようにする。
 *
 * `ACTION_TIMEZONE_CHANGED` はimplicit broadcastの制限対象外で、manifest登録の受信でも届く。
 * 受信できなかった場合でも、ずれた予約で起きたWorkerは解析せず現在timezoneで次回を予約し直す。
 */
class TimeZoneChangedReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != Intent.ACTION_TIMEZONE_CHANGED) return
    val pendingResult = goAsync()
    val scheduler = (context.applicationContext as JournalingPostApplication).container.autoAnalysisScheduler
    CoroutineScope(Dispatchers.Default).launch {
      try {
        scheduler.reschedule()
      } finally {
        pendingResult.finish()
      }
    }
  }
}
