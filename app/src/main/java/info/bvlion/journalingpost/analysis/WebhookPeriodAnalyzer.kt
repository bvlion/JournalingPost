package info.bvlion.journalingpost.analysis

import info.bvlion.journalingpost.journal.PeriodJournalEntryReader
import info.bvlion.journalingpost.webhook.WebhookSettingsRepository
import info.bvlion.journalingpost.webhook.WebhookSettingsState
import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * HTTP status >= 400 はSERVER_ERRORとして扱う(WebhookJournalPoster時代の `< 400` 成功判定を踏襲)。
 * 設定snapshotの取得・対象期間の取得・送受信・response解析のどこで失敗しても、JournalEntryへは
 * 一切触れず[PeriodAnalysisOutcome.Failure]を返す。1回のanalyze()では同じsettings snapshotだけを使う。
 */
internal class WebhookPeriodAnalyzer(
  private val httpClient: HttpClient,
  private val webhookSettingsRepository: WebhookSettingsRepository,
  private val periodJournalEntryReader: PeriodJournalEntryReader,
) : PeriodAnalyzer {
  // schemaVersionのような既定値つきフィールドも必ず送るため encodeDefaults を有効にする。
  private val requestJson = Json { encodeDefaults = true }

  // 想定外フィールドを含むresponseでも body だけ取れれば成功として扱う。
  private val responseJson = Json { ignoreUnknownKeys = true }

  override suspend fun analyze(periodStart: Instant, periodEnd: Instant): PeriodAnalysisOutcome {
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

    if (response.status.value >= 400) return PeriodAnalysisOutcome.Failure.SERVER_ERROR

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
