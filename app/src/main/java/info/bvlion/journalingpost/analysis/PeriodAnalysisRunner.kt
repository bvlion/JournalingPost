package info.bvlion.journalingpost.analysis

import info.bvlion.journalingpost.journal.JournalEntry
import java.time.Instant
import kotlinx.coroutines.CancellationException

/**
 * 対象期間のJournalEntryを解析先へ送り、成功時のみresponseの値で[AnalysisResult]を保存し、
 * 端末保存の確定をretry stateを持つanalyzer(現状Hosted)へ[AnalysisResultPersistenceListener]で
 * 通知するまでの共通処理。手動解析([info.bvlion.journalingpost.AnalysisHistoryViewModel])と
 * 自動解析([AutoAnalyzer])で同じ保存・通知経路を共有し、Idempotency-Keyの解放漏れを防ぐ。
 *
 * 対象期間のJournalEntry取得と、対象期間が空だった場合の扱いは呼び出し側の責務とする。
 * どの失敗でもJournalEntryへは触れない。
 */
internal class PeriodAnalysisRunner(
  private val periodAnalyzer: PeriodAnalyzer,
  private val analysisResultWriter: AnalysisResultWriter,
) {
  suspend fun run(periodStart: Instant, periodEnd: Instant, entries: List<JournalEntry>): Outcome =
    try {
      when (val outcome = periodAnalyzer.analyze(periodStart, periodEnd, entries)) {
        is PeriodAnalysisOutcome.Success -> {
          val savedResultId = analysisResultWriter.save(
            AnalysisResult(
              periodStart = outcome.periodStart,
              periodEnd = outcome.periodEnd,
              analyzedAt = outcome.analyzedAt,
              body = outcome.body,
            ),
          )
          notifyResultPersisted(periodStart, periodEnd)
          Outcome.Saved(savedResultId)
        }

        is PeriodAnalysisOutcome.Failure -> Outcome.Failed(outcome)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // AnalysisResult保存の失敗。JournalEntryには触れていないため、同じ期間を再実行できる。
      Outcome.SaveFailed
    }

  /**
   * 端末保存が確定したことをretry stateを持つanalyzerへ伝える。渡すのは解析先へ送った対象期間で、
   * responseが返した期間ではない。ここでの失敗は保存済みの結果へ影響しないため飲み込む。
   */
  private suspend fun notifyResultPersisted(periodStart: Instant, periodEnd: Instant) {
    try {
      (periodAnalyzer as? AnalysisResultPersistenceListener)
        ?.onAnalysisResultPersisted(periodStart, periodEnd)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // no-op
    }
  }

  sealed interface Outcome {
    /** 解析が成功しAnalysisResultを保存した。[savedResultId]は保存した行のid。 */
    data class Saved(val savedResultId: Long) : Outcome

    /** 解析先が失敗を返した。JournalEntryもAnalysisResultも変更していない。 */
    data class Failed(val failure: PeriodAnalysisOutcome.Failure) : Outcome

    /** 解析自体は成功したが、AnalysisResultの端末保存に失敗した。 */
    data object SaveFailed : Outcome
  }
}
