package info.bvlion.journalingpost

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException

/**
 * 自動解析(Issue #59)の1回分をbackgroundで実行するWorker。[AutoAnalysisScheduler]が予約し、
 * WorkManagerがnetwork制約と省電力状態を見て「指定時刻ごろ」に起動する。
 *
 * 依存はDIコンテナ([AppContainer])から取り出す。WorkManagerの既定WorkerFactoryが
 * `(Context, WorkerParameters)` で生成するため、コンストラクタは変えない。
 *
 * 一時的な失敗でも[androidx.work.ListenableWorker.Result.retry]は返さない(#59: 再試行なし、
 * 翌日の実行を待つ)。実行の成否にかかわらず、最後に次回実行を予約し直して日次のchainを継続する。
 */
class AutoAnalysisWorker(
  appContext: Context,
  params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
  override suspend fun doWork(): Result {
    val container = (applicationContext as JournalingPostApplication).container

    try {
      container.autoAnalyzer.runOnce()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // AutoAnalyzer内で失敗は結果値へ畳んでいるが、想定外の例外でもchainを止めないよう握りつぶす。
    }

    try {
      container.autoAnalysisScheduler.reschedule()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // 次回予約に失敗しても、次のアプリ起動時にsyncFromSettingsで拾い直す。
    }

    return Result.success()
  }
}
