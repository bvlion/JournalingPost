package info.bvlion.journalingpost.analysis

import info.bvlion.journalingpost.journal.JournalEntry
import info.bvlion.journalingpost.settings.AnalysisIntegration
import java.time.Instant
import java.time.LocalDate

/**
 * 期間解析のうち、外部通信を伴う一連の処理。request送信からresponse検証・解析本文の受信までを行う。
 * 対象期間のJournalEntry取得と、解析結果の[AnalysisResult]保存は呼び出し側の責務とする。どの失敗でも
 * JournalEntryへは触れない。
 */
fun interface PeriodAnalyzer {
  /** [analysisDate]はHostedへ渡す対象日。Custom Webhookでは使用しない。 */
  suspend fun analyze(
    periodStart: Instant,
    periodEnd: Instant,
    entries: List<JournalEntry>,
    analysisDate: LocalDate?,
  ): PeriodAnalysisOutcome
}

/**
 * 期間解析の結果。どの経路でもJournalEntryは変更しない。成功時はAnalysisResultへ保存する値
 * (対象期間・解析日時・本文)を、失敗時は表示用の理由([Failure])を持つ。
 */
sealed interface PeriodAnalysisOutcome {
  /**
   * [AnalysisResult]の対象期間・解析日時・本文はいずれもresponseから作る。[integration]は結果へ
   * 保存せず、解析成功時にHosted固有の再解析防止状態を記録するためだけに使う。
   */
  data class Success(
    val periodStart: Instant,
    val periodEnd: Instant,
    val analyzedAt: Instant,
    val body: String,
    val integration: AnalysisIntegration,
  ) : PeriodAnalysisOutcome

  enum class Failure : PeriodAnalysisOutcome {
    /** Custom Webhookが解析先として有効でない(未選択、または利用可能な設定がない)。 */
    WEBHOOK_UNAVAILABLE,

    /** 解析・連携が「使用しない」で、解析先が無い。 */
    INTEGRATION_UNAVAILABLE,

    /** 対象期間にJournalEntryが1件も無い。HTTP requestは送らない。 */
    NO_ENTRIES,

    /** 端末内のJournalEntry取得に失敗した。 */
    LOCAL_READ,

    /** 送信・受信でネットワークエラーが発生した。 */
    NETWORK,

    /** 解析先がHTTPエラー(2xx以外)を返した。処理前の拒否と分かる恒久的な失敗。 */
    SERVER_ERROR,

    /** Hostedで同じ対象日の解析が成功済み。再実行は失敗とし、自動再試行もしない。 */
    RATE_LIMITED,

    /**
     * 解析先が一時的に応答できない(timeout・503・504・処理中など)。同じ意図で
     * しばらくしてから再実行でき、その際は同じIdempotency-Keyを使う。
     */
    TEMPORARILY_UNAVAILABLE,

    /** responseがHostedと同じ想定schemaでなかった、または `analysis.text` が空だった。 */
    INVALID_RESPONSE,
  }
}
