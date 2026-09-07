package info.bvlion.journalingpost

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import info.bvlion.journalingpost.analysis.AutoAnalysisOutcome
import kotlinx.coroutines.CancellationException

/**
 * 自動解析(Issue #59)の1回分をbackgroundで実行するWorker。[AutoAnalysisScheduler]が予約し、
 * WorkManagerがnetwork制約と省電力状態を見て「指定時刻ごろ」に起動する。
 *
 * 依存はDIコンテナ([AppContainer])から取り出す。WorkManagerの既定WorkerFactoryが
 * `(Context, WorkerParameters)` で生成するため、コンストラクタは変えない。
 *
 * Hostedのretryは2分ごとの別Workとして予約し、通常Workerの10分実行上限を超えて待機しない。
 * retryが不要または上限へ達したら、次回の日次実行を予約し直してchainを継続する。
 */
class AutoAnalysisWorker(
  appContext: Context,
  params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
  override suspend fun doWork(): Result {
    val container = (applicationContext as JournalingPostApplication).container
    val retryNumber = inputData.getInt(AutoAnalysisScheduler.INPUT_HOSTED_RETRY_NUMBER, 0)
    // プロセス終了等で同じWorkが再実行された場合も、初回送信前に保存したpayloadでretryする。
    val isHostedRetry = retryNumber > 0 || runAttemptCount > 0

    val outcome = try {
      container.autoAnalyzer.runOnce(
        isHostedRetry = isHostedRetry,
        hasRetryRemaining = retryNumber < AutoAnalysisScheduler.HOSTED_RETRY_LIMIT,
      )
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // AutoAnalyzer内で失敗は結果値へ畳んでいるが、想定外の例外でもchainを止めないよう握りつぶす。
      null
    }

    try {
      if (outcome == AutoAnalysisOutcome.RETRYABLE_FAILURE) {
        container.autoAnalysisScheduler.scheduleHostedRetry(retryNumber + 1)
      } else {
        container.autoAnalysisScheduler.reschedule()
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // 次回予約に失敗しても、次のアプリ起動時にsyncFromSettingsで拾い直す。
    }

    return Result.success()
  }
}
