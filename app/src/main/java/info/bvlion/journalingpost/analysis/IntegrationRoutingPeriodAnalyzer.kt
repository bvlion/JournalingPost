package info.bvlion.journalingpost.analysis

import info.bvlion.journalingpost.journal.JournalEntry
import info.bvlion.journalingpost.settings.AnalysisIntegration
import info.bvlion.journalingpost.settings.AnalysisIntegrationRepository
import java.time.Instant
import java.time.LocalDate
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
  /**
   * 直近の[analyze]の委譲先。[onAnalysisResultPersisted]を「その結果を作ったanalyzer」だけへ送るため
   * に覚える。別の解析先で同じ期間を解析し直しても、他方のretry stateを消さない。
   *
   * [PeriodAnalysisRunner]は analyze → 保存 → [onAnalysisResultPersisted] の順に呼ぶ。この値は
   * その通知先を引き継ぐために使う。
   */
  private var lastDelegate: PeriodAnalyzer? = null

  override suspend fun analyze(
    periodStart: Instant,
    periodEnd: Instant,
    entries: List<JournalEntry>,
    analysisDate: LocalDate?,
  ): PeriodAnalysisOutcome {
    val delegate = when (analysisIntegrationRepository.analysisIntegration.first()) {
      AnalysisIntegration.CUSTOM_WEBHOOK -> webhookAnalyzer
      AnalysisIntegration.HOSTED -> hostedAnalyzer
      AnalysisIntegration.NONE -> null
    }
    lastDelegate = delegate
    return delegate?.analyze(periodStart, periodEnd, entries, analysisDate)
      ?: PeriodAnalysisOutcome.Failure.INTEGRATION_UNAVAILABLE
  }

  override suspend fun onAnalysisResultPersisted(periodStart: Instant, periodEnd: Instant) {
    (lastDelegate as? AnalysisResultPersistenceListener)?.onAnalysisResultPersisted(periodStart, periodEnd)
  }
}
