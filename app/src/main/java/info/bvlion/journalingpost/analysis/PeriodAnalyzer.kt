package info.bvlion.journalingpost.analysis

import java.time.Instant

/**
 * 手動期間解析のうち、外部通信を伴う一連の処理。対象期間のJournalEntry取得→Custom Webhook設定取得
 * →request送信→HTTP/response検証→解析本文の受信までを行う。解析結果の[AnalysisResult]保存は
 * 呼び出し側の責務とする。どの失敗でもJournalEntryへは触れない。
 */
fun interface PeriodAnalyzer {
  suspend fun analyze(periodStart: Instant, periodEnd: Instant): PeriodAnalysisOutcome
}

/** 手動期間解析の結果。失敗してもJournalEntryは変更しないため、成否と表示用の理由のみ持つ。 */
sealed interface PeriodAnalysisOutcome {
  data class Success(val body: String) : PeriodAnalysisOutcome

  enum class Failure : PeriodAnalysisOutcome {
    /** Custom Webhookが解析先として有効でない(未選択、または利用可能な設定がない)。 */
    WEBHOOK_UNAVAILABLE,

    /** 端末内のJournalEntry取得に失敗した。 */
    LOCAL_READ,

    /** 送信・受信でネットワークエラーが発生した。 */
    NETWORK,

    /** WebhookがHTTPエラー(4xx/5xx)を返した。 */
    SERVER_ERROR,

    /** responseがbodyを含む想定schemaでなかった、またはbodyが空だった。 */
    INVALID_RESPONSE,
  }
}
