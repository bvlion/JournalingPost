package info.bvlion.journalingpost.analysis

import info.bvlion.journalingpost.journal.JournalEntry
import info.bvlion.journalingpost.settings.AnalysisIntegration
import info.bvlion.journalingpost.settings.AnalysisIntegrationRepository
import info.bvlion.journalingpost.webhook.WebhookBodyTemplateRenderer
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
 * 送信の可否はここで判断する。開始時点で実効[AnalysisIntegration]がCUSTOM_WEBHOOKであり、対象期間の
 * entryが1件以上あり、かつ保存済みWebhook設定が存在する場合だけrequestを送る。UI側の`canRunAnalysis`は
 * 表示制御にすぎず安全境界にしない(「使用しない」へ変更した直後などUI状態が古くても外部へ送らない)。
 *
 * request bodyは利用者のBody templateを[WebhookBodyTemplateRenderer]で展開したもの。成功はHTTP 2xx
 * かつ responseがHostedと同じ `analysis.text` schemaとしてparseでき、text が非空文字の場合のみ。
 * どの失敗でもJournalEntryへは一切触れず[PeriodAnalysisOutcome.Failure]を返す。1回のanalyze()では
 * 同じsettings snapshotだけを使う。
 */
internal class WebhookPeriodAnalyzer(
  private val httpClient: HttpClient,
  private val analysisIntegrationRepository: AnalysisIntegrationRepository,
  private val webhookSettingsRepository: WebhookSettingsRepository,
) : PeriodAnalyzer {
  // moodのみ/noteのみのentryではnullのフィールドをJSONへ出さない(Hostedのentries[]と同じ形)。
  private val entriesJson = Json { explicitNulls = false }
  private val responseJson = Json { ignoreUnknownKeys = true }

  override suspend fun analyze(
    periodStart: Instant,
    periodEnd: Instant,
    entries: List<JournalEntry>,
  ): PeriodAnalysisOutcome {
    if (analysisIntegrationRepository.analysisIntegration.first() != AnalysisIntegration.CUSTOM_WEBHOOK) {
      return PeriodAnalysisOutcome.Failure.WEBHOOK_UNAVAILABLE
    }
    if (entries.isEmpty()) return PeriodAnalysisOutcome.Failure.NO_ENTRIES

    val settings = (webhookSettingsRepository.settings.first() as? WebhookSettingsState.Configured)?.settings
      ?: return PeriodAnalysisOutcome.Failure.WEBHOOK_UNAVAILABLE

    val body = WebhookBodyTemplateRenderer.render(
      template = settings.bodyTemplate,
      periodStart = periodStart.toString(),
      periodEnd = periodEnd.toString(),
      entriesJson = entriesJson.encodeToString(entries.map { it.toWebhookAnalysisEntry() }),
    )

    val response = try {
      httpClient.post(settings.url) {
        contentType(ContentType.Application.Json)
        headers {
          settings.headers.forEach { header -> append(header.name, header.value) }
        }
        setBody(body)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return PeriodAnalysisOutcome.Failure.NETWORK
    }

    // 2xx以外(3xxを含む)はSERVER_ERROR。
    if (!response.status.isSuccess()) return PeriodAnalysisOutcome.Failure.SERVER_ERROR

    // response本文の受信失敗(接続切断・読み取りtimeout等)はparse不能ではなく受信失敗なのでNETWORK。
    val responseText = try {
      response.bodyAsText()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return PeriodAnalysisOutcome.Failure.NETWORK
    }

    val text = try {
      responseJson.decodeFromString<WebhookAnalysisResponse>(responseText).analysis.text
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return PeriodAnalysisOutcome.Failure.INVALID_RESPONSE
    }
    if (text.isBlank()) return PeriodAnalysisOutcome.Failure.INVALID_RESPONSE

    return PeriodAnalysisOutcome.Success(text)
  }
}
