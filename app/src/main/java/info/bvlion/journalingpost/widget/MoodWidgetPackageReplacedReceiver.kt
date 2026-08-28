package info.bvlion.journalingpost.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private const val UPDATE_TIMEOUT_MS = 10_000L

/**
 * アプリ上書き更新後に、配置済みMood Widgetを1回だけ明示的に再描画するreceiver。
 *
 * package replacementでは、AppWidgetServiceが配置済みWidgetのRemoteViewsを破棄したうえで
 * hostへprovider変更を通知するため、hostの表示は`initialLayout`
 * (= Glanceのloading placeholder)へ戻る。直後にAPPWIDGET_UPDATEも送られてくるが、Glanceの
 * 実際の描画はbroadcast内では完了せず、WorkManagerのSessionWorkerへ委譲される。そのSession
 * はprocessのメモリ上にしか存在せず、更新直後の作り直されたprocessではWorkManagerのcleanup
 * とも重なるため、このAPPWIDGET_UPDATE由来の描画が成立しないままloading表示が残ることがある。
 * package lifecycleが一通り終わったMY_PACKAGE_REPLACEDの時点で、もう一度描画を要求する。
 *
 * 復旧できなかった場合もretryやcrashにはせず諦めるbest-effort処理として扱う。Widget表示の
 * 復旧失敗をアプリ本体へ波及させたくないため、例外はここで止める。
 */
class MoodWidgetPackageReplacedReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

    val appContext = context.applicationContext
    val pendingResult = goAsync()
    CoroutineScope(Dispatchers.Default).launch {
      try {
        // broadcastの実行時間制限内に必ずfinish()へ到達させるため、待ち時間へ上限を設ける。
        withTimeout(UPDATE_TIMEOUT_MS) {
          MoodWidget().updateAll(appContext)
        }
      } catch (e: CancellationException) {
        // timeout(TimeoutCancellationException)やprocess終了による中断。そのまま諦める。
      } catch (e: Exception) {
        // 再描画できなくてもWidgetは次の更新契機で回復しうるため、握りつぶす。
      } finally {
        pendingResult.finish()
      }
    }
  }
}
