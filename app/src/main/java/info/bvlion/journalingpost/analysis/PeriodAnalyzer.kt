package info.bvlion.journalingpost.analysis

import info.bvlion.journalingpost.journal.JournalEntry
import java.time.Instant

/**
 * 手動期間解析のうち、外部通信を伴う一連の処理。Custom Webhook設定の取得→Body templateの展開→
 * request送信→HTTP/response検証→解析本文の受信までを行う。対象期間のJournalEntry取得と、
 * 解析結果の[AnalysisResult]保存は呼び出し側の責務とする。どの失敗でもJournalEntryへは触れない。
 */
fun interface PeriodAnalyzer {
  suspend fun analyze(periodStart: Instant, periodEnd: Instant, entries: List<JournalEntry>): PeriodAnalysisOutcome
}

/**
 * 手動期間解析の結果。どの経路でもJournalEntryは変更しない。成功時はAnalysisResultへ保存する値
 * (対象期間・解析日時・本文)を、失敗時は表示用の理由([Failure])を持つ。
 */
sealed interface PeriodAnalysisOutcome {
  /**
   * Custom Webhookでは、[AnalysisResult]の対象期間・解析日時・本文はいずれもresponse
   * (Hosted契約の `analysis`)から作る。requestで渡したInstantや受信時刻は使わない。
   */
  data class Success(
    val periodStart: Instant,
    val periodEnd: Instant,
    val analyzedAt: Instant,
    val body: String,
  ) : PeriodAnalysisOutcome

  enum class Failure : PeriodAnalysisOutcome {
    /** Custom Webhookが解析先として有効でない(未選択、または利用可能な設定がない)。 */
    WEBHOOK_UNAVAILABLE,

    /** 解析・連携が「使用しない」で、手動解析の対象が無い。 */
    INTEGRATION_UNAVAILABLE,

    /** 対象期間にJournalEntryが1件も無い。HTTP requestは送らない。 */
    NO_ENTRIES,

    /** 端末内のJournalEntry取得に失敗した。 */
    LOCAL_READ,

    /** 送信・受信でネットワークエラーが発生した。 */
    NETWORK,

    /** 解析先がHTTPエラー(2xx以外)を返した。処理前の拒否と分かる恒久的な失敗。 */
    SERVER_ERROR,

    /**
     * 解析先が一時的に応答できない(timeout・503・504・429・処理中など)。同じ意図で
     * しばらくしてから再実行でき、その際は同じIdempotency-Keyを使う。
     */
    TEMPORARILY_UNAVAILABLE,

    /** responseがHostedと同じ想定schemaでなかった、または `analysis.text` が空だった。 */
    INVALID_RESPONSE,
  }
}
