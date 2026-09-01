package info.bvlion.journalingpost.analysis

import info.bvlion.journalingpost.journal.JournalEntry
import info.bvlion.journalingpost.settings.AnalysisIntegration
import info.bvlion.journalingpost.settings.AnalysisIntegrationRepository
import java.time.Instant
import kotlinx.coroutines.flow.first

/**
 * 開始時点の実効[AnalysisIntegration]に応じて、Custom WebhookとHostedの[PeriodAnalyzer]へ振り分ける。
 * 「使用しない」では送信先が無いため[PeriodAnalysisOutcome.Failure.INTEGRATION_UNAVAILABLE]を返す。
 * UI側の`canRunAnalysis`は表示制御にすぎず、ここが実際の送信可否の境界になる。
 */
internal class IntegrationRoutingPeriodAnalyzer(
  private val analysisIntegrationRepository: AnalysisIntegrationRepository,
  private val webhookAnalyzer: PeriodAnalyzer,
  private val hostedAnalyzer: PeriodAnalyzer,
) : PeriodAnalyzer, AnalysisResultPersistenceListener {
  override suspend fun analyze(
    periodStart: Instant,
    periodEnd: Instant,
    entries: List<JournalEntry>,
  ): PeriodAnalysisOutcome = when (analysisIntegrationRepository.analysisIntegration.first()) {
    AnalysisIntegration.CUSTOM_WEBHOOK -> webhookAnalyzer.analyze(periodStart, periodEnd, entries)
    AnalysisIntegration.HOSTED -> hostedAnalyzer.analyze(periodStart, periodEnd, entries)
    AnalysisIntegration.NONE -> PeriodAnalysisOutcome.Failure.INTEGRATION_UNAVAILABLE
  }

  override suspend fun onAnalysisResultPersisted(periodStart: Instant, periodEnd: Instant) {
    // retry stateを持つのは現状Hostedだけ。委譲先のうちlistenerを実装するものへ転送する。
    (webhookAnalyzer as? AnalysisResultPersistenceListener)?.onAnalysisResultPersisted(periodStart, periodEnd)
    (hostedAnalyzer as? AnalysisResultPersistenceListener)?.onAnalysisResultPersisted(periodStart, periodEnd)
  }
}
