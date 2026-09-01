package info.bvlion.journalingpost.hosted

import info.bvlion.journalingpost.analysis.PeriodAnalysisOutcome
import info.bvlion.journalingpost.journal.JournalEntry
import info.bvlion.journalingpost.journal.JournalSource
import info.bvlion.journalingpost.settings.AnalysisIntegration
import info.bvlion.journalingpost.settings.AnalysisIntegrationRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostedPeriodAnalyzerTest {
  private val baseUrl = "https://hosted.example.test"
  private val periodStart = Instant.parse("2026-08-30T00:00:00Z")
  private val periodEnd = Instant.parse("2026-08-31T00:00:00Z")
  private val period = HostedAnalysisPeriod(periodStart, periodEnd)
  private val oneEntry = listOf(entry("2026-08-30T01:00:00Z", note = "メモ"))

  private val successBody = """
    {
      "analysis": {
        "period": { "start": "2026-08-30T00:00:00Z", "end": "2026-08-31T00:00:00Z" },
        "analyzedAt": "2026-08-31T07:30:05Z",
        "entryCount": 1,
        "model": "gpt-5.6-luna",
        "text": "穏やかな一日でした"
      }
    }
  """.trimIndent()

  @Test
  fun `実効AnalysisIntegrationがHOSTED以外なら送信しない`() = runTest {
    var requested = false
    val analyzer = analyzer(integration = AnalysisIntegration.NONE) { requested = true; respondJson(successBody) }

    assertEquals(
      PeriodAnalysisOutcome.Failure.INTEGRATION_UNAVAILABLE,
      analyzer.analyze(periodStart, periodEnd, oneEntry),
    )
    assertFalse(requested)
  }

  @Test
  fun `対象entryが0件ならNO_ENTRIESを返し送信しない`() = runTest {
    var requested = false
    val analyzer = analyzer { requested = true; respondJson(successBody) }

    assertEquals(PeriodAnalysisOutcome.Failure.NO_ENTRIES, analyzer.analyze(periodStart, periodEnd, emptyList()))
    assertFalse(requested)
  }

  @Test
  fun `未登録なら先にinstallation登録し、解析responseからSuccessを作る`() = runTest {
    val paths = mutableListOf<String>()
    val credentials = FakeHostedCredentialsRepository()
    val analyzer = analyzer(credentials = credentials) { request ->
      paths += request.url.encodedPath
      when (request.url.encodedPath) {
        "/v1/installations" -> respondJson("""{"installation":{"apiKey":"jpk_issued"}}""", HttpStatusCode.Created)
        else -> respondJson(successBody)
      }
    }

    val outcome = analyzer.analyze(periodStart, periodEnd, oneEntry)

    assertEquals(
      PeriodAnalysisOutcome.Success(
        periodStart = periodStart,
        periodEnd = periodEnd,
        analyzedAt = Instant.parse("2026-08-31T07:30:05Z"),
        body = "穏やかな一日でした",
      ),
      outcome,
    )
    assertEquals(listOf("/v1/installations", "/v1/analyses"), paths)
    assertEquals("jpk_issued", credentials.stored)
  }

  @Test
  fun `保存済みAPI keyがあれば登録しない`() = runTest {
    val paths = mutableListOf<String>()
    val analyzer = analyzer(credentials = FakeHostedCredentialsRepository(stored = "jpk_stored")) { request ->
      paths += request.url.encodedPath
      respondJson(successBody)
    }

    analyzer.analyze(periodStart, periodEnd, oneEntry)

    assertEquals(listOf("/v1/analyses"), paths)
  }

  @Test
  fun `analyses requestにBearer認証とIdempotency-Keyとperiod_entriesを載せる`() = runTest {
    var authorization: String? = null
    var idempotencyKey: String? = null
    var body: String? = null
    val keyStore = FakeIdempotencyKeyStore(fixedKey = "idem-123")
    val analyzer = analyzer(
      credentials = FakeHostedCredentialsRepository(stored = "jpk_stored"),
      keyStore = keyStore,
    ) { request ->
      authorization = request.headers[HttpHeaders.Authorization]
      idempotencyKey = request.headers["Idempotency-Key"]
      body = String(request.body.toByteArray())
      respondJson(successBody)
    }

    analyzer.analyze(
      periodStart,
      periodEnd,
      listOf(
        entry("2026-08-30T01:00:00Z", moodEmoji = "🙂", moodLabel = "嬉しい"),
        entry("2026-08-30T09:00:00Z", note = "メモだけ"),
      ),
    )

    assertEquals("Bearer jpk_stored", authorization)
    assertEquals("idem-123", idempotencyKey)
    val json = Json.parseToJsonElement(requireNotNull(body)).jsonObject
    assertEquals("2026-08-30T00:00:00Z", json.getValue("period").jsonObject.getValue("start").jsonPrimitive.content)
    assertEquals("2026-08-31T00:00:00Z", json.getValue("period").jsonObject.getValue("end").jsonPrimitive.content)
    val entries = json.getValue("entries").jsonArray
    assertEquals(2, entries.size)
    val mood = entries[0].jsonObject.getValue("mood").jsonObject
    assertEquals("🙂", mood.getValue("emoji").jsonPrimitive.content)
    assertEquals("嬉しい", mood.getValue("label").jsonPrimitive.content)
    assertNull(entries[1].jsonObject["mood"])
    assertEquals("メモだけ", entries[1].jsonObject.getValue("note").jsonPrimitive.content)
  }

  @Test
  fun `送信後timeoutはTEMPORARILY_UNAVAILABLEでkeyを残す`() = runTest {
    val keyStore = FakeIdempotencyKeyStore()
    val analyzer = analyzer(
      credentials = FakeHostedCredentialsRepository(stored = "jpk_stored"),
      keyStore = keyStore,
    ) { request -> throw HttpRequestTimeoutException(request) }

    assertEquals(
      PeriodAnalysisOutcome.Failure.TEMPORARILY_UNAVAILABLE,
      analyzer.analyze(periodStart, periodEnd, oneEntry),
    )
    assertFalse(keyStore.wasCleared(period))
  }

  @Test
  fun `送信失敗はNETWORKでkeyを残す`() = runTest {
    val keyStore = FakeIdempotencyKeyStore()
    val analyzer = analyzer(
      credentials = FakeHostedCredentialsRepository(stored = "jpk_stored"),
      keyStore = keyStore,
    ) { throw IOException("boom") }

    assertEquals(PeriodAnalysisOutcome.Failure.NETWORK, analyzer.analyze(periodStart, periodEnd, oneEntry))
    assertFalse(keyStore.wasCleared(period))
  }

  @Test
  fun `429と5xxはTEMPORARILY_UNAVAILABLEでkeyを残す`() = runTest {
    listOf(HttpStatusCode.TooManyRequests, HttpStatusCode(500, "x"), HttpStatusCode.ServiceUnavailable, HttpStatusCode.GatewayTimeout).forEach { status ->
      val keyStore = FakeIdempotencyKeyStore()
      val analyzer = analyzer(
        credentials = FakeHostedCredentialsRepository(stored = "jpk_stored"),
        keyStore = keyStore,
      ) { respondJson("""{"error":{"code":"x"}}""", status) }

      assertEquals(
        "status=$status",
        PeriodAnalysisOutcome.Failure.TEMPORARILY_UNAVAILABLE,
        analyzer.analyze(periodStart, periodEnd, oneEntry),
      )
      assertFalse("status=$status", keyStore.wasCleared(period))
    }
  }

  @Test
  fun `409 analysis_in_progressはkeyを残し、それ以外の409はkeyを捨てる`() = runTest {
    val inProgress = FakeIdempotencyKeyStore()
    assertEquals(
      PeriodAnalysisOutcome.Failure.TEMPORARILY_UNAVAILABLE,
      analyzer(credentials = FakeHostedCredentialsRepository(stored = "k"), keyStore = inProgress) {
        respondJson("""{"error":{"code":"analysis_in_progress"}}""", HttpStatusCode.Conflict)
      }.analyze(periodStart, periodEnd, oneEntry),
    )
    assertFalse(inProgress.wasCleared(period))

    val reuse = FakeIdempotencyKeyStore()
    analyzer(credentials = FakeHostedCredentialsRepository(stored = "k"), keyStore = reuse) {
      respondJson("""{"error":{"code":"idempotency_key_reuse"}}""", HttpStatusCode.Conflict)
    }.analyze(periodStart, periodEnd, oneEntry)
    assertTrue(reuse.wasCleared(period))
  }

  @Test
  fun `422はSERVER_ERRORでkeyを捨てる`() = runTest {
    val keyStore = FakeIdempotencyKeyStore()
    val analyzer = analyzer(credentials = FakeHostedCredentialsRepository(stored = "k"), keyStore = keyStore) {
      respondJson("""{"error":{"code":"validation_error"}}""", HttpStatusCode.UnprocessableEntity)
    }

    assertEquals(PeriodAnalysisOutcome.Failure.SERVER_ERROR, analyzer.analyze(periodStart, periodEnd, oneEntry))
    assertTrue(keyStore.wasCleared(period))
  }

  @Test
  fun `401はAPI keyとkeyを捨てる`() = runTest {
    val credentials = FakeHostedCredentialsRepository(stored = "jpk_stale")
    val keyStore = FakeIdempotencyKeyStore()
    val analyzer = analyzer(credentials = credentials, keyStore = keyStore) {
      respondJson("""{"error":{"code":"unauthorized"}}""", HttpStatusCode.Unauthorized)
    }

    assertEquals(PeriodAnalysisOutcome.Failure.SERVER_ERROR, analyzer.analyze(periodStart, periodEnd, oneEntry))
    assertTrue(credentials.cleared)
    assertTrue(keyStore.wasCleared(period))
  }

  @Test
  fun `200でも壊れたbody_空textはINVALID_RESPONSE`() = runTest {
    listOf("""{"analysis":{"text":"ok"}}""", successBody.replace("穏やかな一日でした", " ")).forEach { body ->
      val analyzer = analyzer(credentials = FakeHostedCredentialsRepository(stored = "k")) { respondJson(body) }
      assertEquals(
        PeriodAnalysisOutcome.Failure.INVALID_RESPONSE,
        analyzer.analyze(periodStart, periodEnd, oneEntry),
      )
    }
  }

  @Test
  fun `成功するとkeyを捨て、次の実行は新しい解析になる`() = runTest {
    val keyStore = FakeIdempotencyKeyStore()
    val analyzer = analyzer(credentials = FakeHostedCredentialsRepository(stored = "k"), keyStore = keyStore) {
      respondJson(successBody)
    }

    analyzer.analyze(periodStart, periodEnd, oneEntry)

    assertTrue(keyStore.wasCleared(period))
  }

  @Test
  fun `登録がretryできない失敗ならSERVER_ERROR`() = runTest {
    val analyzer = analyzer(credentials = FakeHostedCredentialsRepository()) {
      respondJson("""{}""", HttpStatusCode.BadRequest)
    }

    assertEquals(PeriodAnalysisOutcome.Failure.SERVER_ERROR, analyzer.analyze(periodStart, periodEnd, oneEntry))
  }

  @Test
  fun `登録がretryできる失敗ならTEMPORARILY_UNAVAILABLE`() = runTest {
    val analyzer = analyzer(credentials = FakeHostedCredentialsRepository()) {
      respondJson("""{}""", HttpStatusCode.ServiceUnavailable)
    }

    assertEquals(
      PeriodAnalysisOutcome.Failure.TEMPORARILY_UNAVAILABLE,
      analyzer.analyze(periodStart, periodEnd, oneEntry),
    )
  }

  private fun MockRequestHandleScope.respondJson(
    content: String,
    status: HttpStatusCode = HttpStatusCode.OK,
  ): HttpResponseData = respond(content, status, headersOf(HttpHeaders.ContentType, "application/json"))

  private fun entry(
    at: String,
    moodEmoji: String? = null,
    moodLabel: String? = null,
    note: String? = null,
  ) = JournalEntry(
    timestamp = Instant.parse(at),
    moodId = if (moodEmoji != null || moodLabel != null) "MOOD" else null,
    moodEmoji = moodEmoji,
    moodLabel = moodLabel,
    note = note,
    source = JournalSource.APP,
  )

  private fun analyzer(
    integration: AnalysisIntegration = AnalysisIntegration.HOSTED,
    credentials: FakeHostedCredentialsRepository = FakeHostedCredentialsRepository(stored = "jpk_stored"),
    keyStore: FakeIdempotencyKeyStore = FakeIdempotencyKeyStore(),
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
  ): HostedPeriodAnalyzer {
    val client = HttpClient(MockEngine { request -> handler(request) }) {
      install(ContentNegotiation) { json() }
    }
    val integrationRepository = object : AnalysisIntegrationRepository {
      override val analysisIntegration: Flow<AnalysisIntegration> = MutableStateFlow(integration)
      override suspend fun setAnalysisIntegration(integration: AnalysisIntegration) = error("unused")
    }
    return HostedPeriodAnalyzer(
      httpClient = client,
      registrar = HostedInstallationRegistrar(client, credentials, baseUrl),
      credentialsRepository = credentials,
      idempotencyKeyStore = keyStore,
      analysisIntegrationRepository = integrationRepository,
      baseUrl = baseUrl,
    )
  }
}

internal class FakeIdempotencyKeyStore(private val fixedKey: String? = null) : HostedIdempotencyKeyStore {
  private val keys = mutableMapOf<String, String>()
  private val cleared = mutableSetOf<String>()
  private var generation = 0

  override suspend fun currentKey(period: HostedAnalysisPeriod): String =
    keys.getOrPut(period.identity) { fixedKey ?: "key-${++generation}" }

  override suspend fun clear(period: HostedAnalysisPeriod) {
    keys.remove(period.identity)
    cleared += period.identity
  }

  fun wasCleared(period: HostedAnalysisPeriod): Boolean = period.identity in cleared
}
