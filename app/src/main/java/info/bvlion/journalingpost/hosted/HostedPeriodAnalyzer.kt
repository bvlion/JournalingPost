package info.bvlion.journalingpost.hosted

import info.bvlion.journalingpost.analysis.AnalysisResultPersistenceListener
import info.bvlion.journalingpost.analysis.PeriodAnalysisOutcome
import info.bvlion.journalingpost.analysis.PeriodAnalyzer
import info.bvlion.journalingpost.journal.JournalEntry
import info.bvlion.journalingpost.settings.AnalysisIntegration
import info.bvlion.journalingpost.settings.AnalysisIntegrationRepository
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * JournalingPost Hosted解析への手動接続(JournalingPostServer #40)。
 *
 * 開始時点の実効[AnalysisIntegration]がHOSTEDのときだけ動く。匿名installation登録→Bearer API key
 * 取得→`POST /v1/analyses`→200 response bodyから[PeriodAnalysisOutcome.Success]の生成までを行う。
 * 対象期間のJournalEntry取得と[info.bvlion.journalingpost.analysis.AnalysisResult]保存は呼び出し側。
 * どの失敗でもJournalEntryへは触れない(このクラスはJournalEntryのwriterを持たない)。
 *
 * Idempotency-Keyは[HostedIdempotencyKeyStore]が「対象期間 + 送信payloadのfingerprint」ごとに
 * 管理する。network失敗・timeout・一時エラーではkeyを残し、同じpayloadのretryが同じkeyでServerの
 * retry bufferから結果を取り不要なAI再課金を避ける。処理前の拒否と分かる失敗(4xx等)ではkeyを捨てる。
 *
 * 200成功時はkeyを捨てない。AnalysisResultの端末保存が確定する前に捨てると、保存失敗時のretryが
 * bufferを引けず結果を失うためである。保存が確定したら[onAnalysisResultPersisted]でkeyを捨て、
 * 以降の同じ期間・同じpayloadの明示実行は新しいkey(新しい解析)になる。
 */
internal class HostedPeriodAnalyzer(
  private val httpClient: HttpClient,
  private val registrar: HostedInstallationRegistrar,
  private val credentialsRepository: HostedCredentialsRepository,
  private val idempotencyKeyStore: HostedIdempotencyKeyStore,
  private val analysisIntegrationRepository: AnalysisIntegrationRepository,
  private val baseUrl: String,
) : PeriodAnalyzer, AnalysisResultPersistenceListener {
  // moodのみ/noteのみのentryではnullのフィールドをJSONへ出さない(Hostedのentries[]と同じ形)。
  private val requestJson = Json { explicitNulls = false }
  private val responseJson = Json { ignoreUnknownKeys = true }

  override suspend fun analyze(
    periodStart: Instant,
    periodEnd: Instant,
    entries: List<JournalEntry>,
  ): PeriodAnalysisOutcome {
    if (analysisIntegrationRepository.analysisIntegration.first() != AnalysisIntegration.HOSTED) {
      return PeriodAnalysisOutcome.Failure.INTEGRATION_UNAVAILABLE
    }
    if (entries.isEmpty()) return PeriodAnalysisOutcome.Failure.NO_ENTRIES

    val apiKey = try {
      registrar.apiKey()
    } catch (e: CancellationException) {
      throw e
    } catch (e: HostedRegistrationException) {
      return if (e.retryable) {
        PeriodAnalysisOutcome.Failure.TEMPORARILY_UNAVAILABLE
      } else {
        PeriodAnalysisOutcome.Failure.SERVER_ERROR
      }
    }

    val period = HostedAnalysisPeriod(periodStart, periodEnd)
    val body = requestJson.encodeToString(
      HostedAnalysisRequest(
        period = HostedAnalysisRequest.Period(periodStart.toString(), periodEnd.toString()),
        entries = entries.map { it.toHostedAnalysisEntry() },
      ),
    )

    // installation登録の待ち時間中に利用者が「使用しない」等へ変更していないか、JournalEntryを
    // 送る直前に再確認する。opt-out後の外部送信を起こさないため。登録済みAPI keyはそのまま残す
    // (再度Hostedを選べば使える。JournalEntry本文は登録には送っていない)。
    if (analysisIntegrationRepository.analysisIntegration.first() != AnalysisIntegration.HOSTED) {
      return PeriodAnalysisOutcome.Failure.INTEGRATION_UNAVAILABLE
    }

    // 送信bodyのfingerprintをkeyの一部にする。timeout後に記録を編集して同じ日を解析し直すと
    // fingerprintが変わり新しいkeyになるため、Server側の idempotency_key_reuse 衝突を避けられる。
    val idempotencyKey = idempotencyKeyStore.currentKey(period, body.sha256Hex())

    val response = try {
      httpClient.post("$baseUrl/v1/analyses") {
        contentType(ContentType.Application.Json)
        headers {
          append(HttpHeaders.Authorization, "Bearer $apiKey")
          append(IDEMPOTENCY_KEY_HEADER, idempotencyKey)
        }
        setBody(body)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: HttpRequestTimeoutException) {
      // 送信後のtimeout。Serverが処理・課金済みかは端末から確定できない。keyを残して
      // 同じkeyの再送でretry bufferを引ける状態にする。
      return PeriodAnalysisOutcome.Failure.TEMPORARILY_UNAVAILABLE
    } catch (e: Exception) {
      // 送信・接続の失敗。keyを残して同じkeyで再試行できるようにする。
      return PeriodAnalysisOutcome.Failure.NETWORK
    }

    return when (response.status.value) {
      200 -> handleOk(response, period)
      401 -> {
        // API keyが無効・未登録。消して次回のHosted利用時に再登録させる。
        credentialsRepository.clear()
        idempotencyKeyStore.clear(period)
        PeriodAnalysisOutcome.Failure.SERVER_ERROR
      }
      409 -> handleConflict(response, period)
      // 429 rate_limited と 5xx(analysis_unavailable / analysis_timeout / internal_error)は
      // 同じkeyで再送する契約。keyを残す。
      429, in 500..599 -> PeriodAnalysisOutcome.Failure.TEMPORARILY_UNAVAILABLE
      // 400 / 413 / 415 / 422 / 404 等。処理前の拒否と分かる恒久的な失敗。keyを捨てる。
      in 400..499 -> {
        idempotencyKeyStore.clear(period)
        PeriodAnalysisOutcome.Failure.SERVER_ERROR
      }
      // 2xx(200以外)・3xx。成功扱いにしない。
      else -> {
        idempotencyKeyStore.clear(period)
        PeriodAnalysisOutcome.Failure.SERVER_ERROR
      }
    }
  }

  private suspend fun handleOk(response: HttpResponse, period: HostedAnalysisPeriod): PeriodAnalysisOutcome {
    val text = try {
      response.bodyAsText()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // 200は受け取ったが本文の受信に失敗。同じkeyのretryがbufferから結果を引けるようkeyを残す。
      return PeriodAnalysisOutcome.Failure.TEMPORARILY_UNAVAILABLE
    }

    val analysis = try {
      responseJson.decodeFromString<HostedAnalysisResponse>(text).analysis
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return PeriodAnalysisOutcome.Failure.INVALID_RESPONSE
    }
    if (analysis.text.isBlank()) return PeriodAnalysisOutcome.Failure.INVALID_RESPONSE

    val start = analysis.period.start.toHostedResponseInstantOrNull()
      ?: return PeriodAnalysisOutcome.Failure.INVALID_RESPONSE
    val end = analysis.period.end.toHostedResponseInstantOrNull()
      ?: return PeriodAnalysisOutcome.Failure.INVALID_RESPONSE
    val analyzedAt = analysis.analyzedAt.toHostedResponseInstantOrNull()
      ?: return PeriodAnalysisOutcome.Failure.INVALID_RESPONSE

    // 成功してもkeyは消さない。端末保存の確定は呼び出し側だけが知るため、
    // [onAnalysisResultPersisted]まで保持する(保存失敗時のretryをbufferで引けるようにするため)。
    return PeriodAnalysisOutcome.Success(
      periodStart = start,
      periodEnd = end,
      analyzedAt = analyzedAt,
      body = analysis.text,
    )
  }

  /**
   * AnalysisResultの端末保存が確定した。この解析意図は完了とし、以降の同じ期間・同じpayloadの
   * 明示実行が新しいkey(新しい解析)になるようkeyを捨てる。
   */
  override suspend fun onAnalysisResultPersisted(periodStart: Instant, periodEnd: Instant) {
    idempotencyKeyStore.clear(HostedAnalysisPeriod(periodStart, periodEnd))
  }

  private suspend fun handleConflict(response: HttpResponse, period: HostedAnalysisPeriod): PeriodAnalysisOutcome {
    val code = try {
      responseJson.decodeFromString<HostedErrorResponse>(response.bodyAsText()).error?.code
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      null
    }

    // analysis_in_progress: 同じkeyで待ってから再送する。
    if (code == "analysis_in_progress") return PeriodAnalysisOutcome.Failure.TEMPORARILY_UNAVAILABLE

    // idempotency_key_reuse(同じkeyで別内容) / analysis_result_unavailable(結果を返せない)。
    // どちらも新しいkeyでの再解析が必要。keyを捨てて次の実行を新しい解析にする。
    idempotencyKeyStore.clear(period)
    return PeriodAnalysisOutcome.Failure.TEMPORARILY_UNAVAILABLE
  }

  companion object {
    const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"

    // JournalingPostServer #4 の実測(実OpenAI成功応答 最大約4.3秒、Server側504は約50秒)に対する
    // Androidの読み取りtimeout推奨値。
    const val REQUEST_TIMEOUT_MILLIS = 90_000L
  }
}

/** payloadの変化検出用のfingerprint。暗号強度は不要で、内容が変われば値が変わればよい。 */
private fun String.sha256Hex(): String =
  MessageDigest.getInstance("SHA-256").digest(toByteArray()).joinToString("") { "%02x".format(it) }
