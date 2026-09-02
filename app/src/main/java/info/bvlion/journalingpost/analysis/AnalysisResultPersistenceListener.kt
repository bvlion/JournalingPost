package info.bvlion.journalingpost.analysis

import java.time.Instant

/**
 * AnalysisResultの端末保存が確定した時点を知る必要がある[PeriodAnalyzer]実装が実装する。
 *
 * Hostedは、Serverのretry buffer取得に使うIdempotency-Keyの「解析意図」の終わりをこの時点に固定する。
 * Hosted responseを受け取っても端末保存が確定するまではkeyを保持し、保存が失敗すれば同じpayloadの
 * retryがbufferから同じ結果を取得できる。保存まで成功したら、以降の明示実行は新しいkeyになる。
 *
 * Custom Webhookはこの種のretry stateを持たないため実装しない。[AnalysisHistoryViewModel]は
 * `analyzer as? AnalysisResultPersistenceListener` でのみ関与し、共通の解析フローへHosted固有の
 * 概念を持ち込まない。
 */
fun interface AnalysisResultPersistenceListener {
  suspend fun onAnalysisResultPersisted(periodStart: Instant, periodEnd: Instant)
}
