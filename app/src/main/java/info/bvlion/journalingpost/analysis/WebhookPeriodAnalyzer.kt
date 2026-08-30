package info.bvlion.journalingpost.analysis

import info.bvlion.journalingpost.journal.PeriodJournalEntryReader
import info.bvlion.journalingpost.settings.AnalysisIntegration
import info.bvlion.journalingpost.settings.AnalysisIntegrationRepository
import info.bvlion.journalingpost.webhook.WebhookSettingsRepository
import info.bvlion.journalingpost.webhook.WebhookSettingsState
import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 送信の可否はここで判断する。開始時点で実効[AnalysisIntegration]がCUSTOM_WEBHOOKであり、かつ保存済み
 * Webhook設定が存在する場合だけrequestを送る。UI側の`canRunAnalysis`は表示制御にすぎず、それを安全境界に
 * しない(「使用しない」へ変更した直後などUI状態が古くても、JournalEntryを外部へ送らない)。
 *
 * 成功はHTTP 2xx かつ responseがPeriodAnalysisResponseとしてparseでき body が非空文字の場合のみ。
 * 設定取得・対象期間の取得・送受信・response解析のどこで失敗しても、JournalEntryへは一切触れず
 * [PeriodAnalysisOutcome.Failure]を返す。1回のanalyze()では同じsettings snapshotだけを使う。
 */
internal class WebhookPeriodAnalyzer(
  private val httpClient: HttpClient,
  private val analysisIntegrationRepository: AnalysisIntegrationRepository,
  private val webhookSettingsRepository: WebhookSettingsRepository,
  private val periodJournalEntryReader: PeriodJournalEntryReader,
) : PeriodAnalyzer {
  // schemaVersionのような既定値つきフィールドも必ず送るため encodeDefaults を有効にする。
  private val requestJson = Json { encodeDefaults = true }

  // 想定外フィールドを含むresponseでも body だけ取れれば成功として扱う。
  private val responseJson = Json { ignoreUnknownKeys = true }

  override suspend fun analyze(periodStart: Instant, periodEnd: Instant): PeriodAnalysisOutcome {
    if (analysisIntegrationRepository.analysisIntegration.first() != AnalysisIntegration.CUSTOM_WEBHOOK) {
      return PeriodAnalysisOutcome.Failure.WEBHOOK_UNAVAILABLE
    }

    val state = webhookSettingsRepository.settings.first()
    val settings = (state as? WebhookSettingsState.Configured)?.settings
      ?: return PeriodAnalysisOutcome.Failure.WEBHOOK_UNAVAILABLE

    val entries = try {
      periodJournalEntryReader.entriesInPeriod(periodStart, periodEnd)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return PeriodAnalysisOutcome.Failure.LOCAL_READ
    }

    val requestBody = requestJson.encodeToString(
      PeriodAnalysisRequest(
        periodStart = periodStart.toString(),
        periodEnd = periodEnd.toString(),
        entries = entries.map { it.toPeriodAnalysisEntry() },
      ),
    )

    val response = try {
      httpClient.post(settings.url) {
        contentType(ContentType.Application.Json)
        headers {
          settings.headers.forEach { header -> append(header.name, header.value) }
        }
        setBody(requestBody)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return PeriodAnalysisOutcome.Failure.NETWORK
    }

    // 2xx以外(3xxリダイレクト含む)は成功として扱わない。
    if (!response.status.isSuccess()) return PeriodAnalysisOutcome.Failure.SERVER_ERROR

    val body = try {
      responseJson.decodeFromString<PeriodAnalysisResponse>(response.bodyAsText()).body
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return PeriodAnalysisOutcome.Failure.INVALID_RESPONSE
    }
    if (body.isBlank()) return PeriodAnalysisOutcome.Failure.INVALID_RESPONSE

    return PeriodAnalysisOutcome.Success(body)
  }
}
