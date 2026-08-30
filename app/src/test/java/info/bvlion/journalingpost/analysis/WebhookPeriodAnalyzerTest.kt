package info.bvlion.journalingpost.analysis

import info.bvlion.journalingpost.journal.JournalEntry
import info.bvlion.journalingpost.journal.JournalSource
import info.bvlion.journalingpost.journal.PeriodJournalEntryReader
import info.bvlion.journalingpost.settings.AnalysisIntegration
import info.bvlion.journalingpost.settings.AnalysisIntegrationRepository
import info.bvlion.journalingpost.webhook.WebhookHeader
import info.bvlion.journalingpost.webhook.WebhookSettings
import info.bvlion.journalingpost.webhook.WebhookSettingsRepository
import info.bvlion.journalingpost.webhook.WebhookSettingsState
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebhookPeriodAnalyzerTest {
  private val periodStart = Instant.parse("2026-08-30T00:00:00Z")
  private val periodEnd = Instant.parse("2026-08-31T00:00:00Z")

  @Test
  fun `Webhook未設定ならWEBHOOK_UNAVAILABLEを返し送信しない`() = runTest {
    var requested = false
    val analyzer = analyzer(
      webhookState = WebhookSettingsState.NotConfigured,
      handler = { requested = true; respondJson("""{"body":"x"}""") },
    )

    val outcome = analyzer.analyze(periodStart, periodEnd)

    assertEquals(PeriodAnalysisOutcome.Failure.WEBHOOK_UNAVAILABLE, outcome)
    assertEquals(false, requested)
  }

  @Test
  fun `実効AnalysisIntegrationがCUSTOM_WEBHOOK以外なら設定が残っていても送信しない`() = runTest {
    var requested = false
    val analyzer = analyzer(
      integration = AnalysisIntegration.NONE,
      handler = { requested = true; respondJson("""{"body":"x"}""") },
    )

    val outcome = analyzer.analyze(periodStart, periodEnd)

    assertEquals(PeriodAnalysisOutcome.Failure.WEBHOOK_UNAVAILABLE, outcome)
    assertEquals(false, requested)
  }

  @Test
  fun `成功responseのbodyをSuccessとして返す`() = runTest {
    val analyzer = analyzer(handler = { respondJson("""{"body":"今週は穏やかでした"}""") })

    val outcome = analyzer.analyze(periodStart, periodEnd)

    assertEquals(PeriodAnalysisOutcome.Success("今週は穏やかでした"), outcome)
  }

  @Test
  fun `requestは半開区間のISO-8601とentriesを含む`() = runTest {
    var body: String? = null
    val analyzer = analyzer(
      entries = listOf(
        entry(at = "2026-08-30T01:00:00Z", note = "メモだけ"),
        entry(at = "2026-08-30T09:00:00Z", moodId = "HAPPY", moodEmoji = "🙂", moodLabel = "嬉しい", note = null),
      ),
      handler = { request -> body = String(request.body.toByteArray()); respondJson("""{"body":"ok"}""") },
    )

    analyzer.analyze(periodStart, periodEnd)

    val json = Json.parseToJsonElement(requireNotNull(body)).jsonObject
    assertEquals(1, json.getValue("schemaVersion").jsonPrimitive.int)
    assertEquals("2026-08-30T00:00:00Z", json.getValue("periodStart").jsonPrimitive.content)
    assertEquals("2026-08-31T00:00:00Z", json.getValue("periodEnd").jsonPrimitive.content)
    val entries = json.getValue("entries").jsonArray
    assertEquals(2, entries.size)
    assertTrue(entries[0].jsonObject.getValue("mood") is JsonNull)
    assertEquals("メモだけ", entries[0].jsonObject.getValue("note").jsonPrimitive.content)
    val mood = entries[1].jsonObject.getValue("mood").jsonObject
    assertEquals("HAPPY", mood.getValue("id").jsonPrimitive.content)
    assertEquals("🙂", mood.getValue("emoji").jsonPrimitive.content)
    assertEquals("嬉しい", mood.getValue("label").jsonPrimitive.content)
  }

  @Test
  fun `想定外フィールドを含むresponseでもbodyがあればSuccessになる`() = runTest {
    val analyzer = analyzer(handler = { respondJson("""{"body":"ok","extra":123}""") })

    assertEquals(PeriodAnalysisOutcome.Success("ok"), analyzer.analyze(periodStart, periodEnd))
  }

  @Test
  fun `bodyが空文字ならINVALID_RESPONSE`() = runTest {
    val analyzer = analyzer(handler = { respondJson("""{"body":"  "}""") })

    assertEquals(PeriodAnalysisOutcome.Failure.INVALID_RESPONSE, analyzer.analyze(periodStart, periodEnd))
  }

  @Test
  fun `bodyキーを含まないresponseはINVALID_RESPONSE`() = runTest {
    val analyzer = analyzer(handler = { respondJson("""{"summary":"ok"}""") })

    assertEquals(PeriodAnalysisOutcome.Failure.INVALID_RESPONSE, analyzer.analyze(periodStart, periodEnd))
  }

  @Test
  fun `HTTP 500はSERVER_ERROR`() = runTest {
    val analyzer = analyzer(handler = { respondJson("""{"body":"ignored"}""", HttpStatusCode.InternalServerError) })

    assertEquals(PeriodAnalysisOutcome.Failure.SERVER_ERROR, analyzer.analyze(periodStart, periodEnd))
  }

  @Test
  fun `3xxは成功扱いにせずSERVER_ERROR`() = runTest {
    // Ktor標準設定ではPOSTのリダイレクトは追わないため、3xxがそのままresponseとして届く。
    val analyzer = analyzer(handler = { respondJson("""{"body":"ignored"}""", HttpStatusCode.Found) })

    assertEquals(PeriodAnalysisOutcome.Failure.SERVER_ERROR, analyzer.analyze(periodStart, periodEnd))
  }

  @Test
  fun `2xx境界の299は成功として扱う`() = runTest {
    val status299 = HttpStatusCode(299, "Almost OK")
    val analyzer = analyzer(handler = { respondJson("""{"body":"ok"}""", status299) })

    assertEquals(PeriodAnalysisOutcome.Success("ok"), analyzer.analyze(periodStart, periodEnd))
  }

  @Test
  fun `送信時の例外はNETWORK`() = runTest {
    val analyzer = analyzer(handler = { throw IOException("boom") })

    assertEquals(PeriodAnalysisOutcome.Failure.NETWORK, analyzer.analyze(periodStart, periodEnd))
  }

  @Test
  fun `対象期間の取得に失敗するとLOCAL_READ`() = runTest {
    val analyzer = analyzer(
      entriesReader = { _, _ -> throw RuntimeException("db boom") },
      handler = { respondJson("""{"body":"x"}""") },
    )

    assertEquals(PeriodAnalysisOutcome.Failure.LOCAL_READ, analyzer.analyze(periodStart, periodEnd))
  }

  @Test
  fun `設定済みHeaderが送信requestへ付与される`() = runTest {
    var authorization: String? = null
    val analyzer = analyzer(
      settings = WebhookSettings(
        url = "https://example.com/analyze",
        headers = listOf(WebhookHeader("Authorization", "Bearer secret")),
      ),
      handler = { request ->
        authorization = request.headers[HttpHeaders.Authorization]
        respondJson("""{"body":"ok"}""")
      },
    )

    analyzer.analyze(periodStart, periodEnd)

    assertEquals("Bearer secret", authorization)
  }

  private fun MockRequestHandleScope.respondJson(
    content: String,
    status: HttpStatusCode = HttpStatusCode.OK,
  ): HttpResponseData = respond(
    content = content,
    status = status,
    headers = headersOf(HttpHeaders.ContentType, "application/json"),
  )

  private fun analyzer(
    settings: WebhookSettings = WebhookSettings(url = "https://example.com/analyze", headers = emptyList()),
    webhookState: WebhookSettingsState = WebhookSettingsState.Configured(settings),
    integration: AnalysisIntegration = AnalysisIntegration.CUSTOM_WEBHOOK,
    entries: List<JournalEntry> = emptyList(),
    entriesReader: (suspend (Instant, Instant) -> List<JournalEntry>)? = null,
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
  ): WebhookPeriodAnalyzer {
    val client = HttpClient(MockEngine { request -> handler(request) }) {
      install(ContentNegotiation) { json() }
    }
    val reader = PeriodJournalEntryReader { start, end ->
      entriesReader?.invoke(start, end) ?: entries
    }
    return WebhookPeriodAnalyzer(
      httpClient = client,
      analysisIntegrationRepository = FakeAnalysisIntegrationRepository(integration),
      webhookSettingsRepository = FakeWebhookSettingsRepository(webhookState),
      periodJournalEntryReader = reader,
    )
  }

  private fun entry(
    at: String,
    moodId: String? = null,
    moodEmoji: String? = null,
    moodLabel: String? = null,
    note: String? = null,
  ) = JournalEntry(
    timestamp = Instant.parse(at),
    moodId = moodId,
    moodEmoji = moodEmoji,
    moodLabel = moodLabel,
    note = note,
    source = JournalSource.APP,
  )

  private class FakeWebhookSettingsRepository(state: WebhookSettingsState) : WebhookSettingsRepository {
    override val settings: Flow<WebhookSettingsState> = MutableStateFlow(state)

    override suspend fun save(settings: WebhookSettings) = error("not used in this test")
  }

  private class FakeAnalysisIntegrationRepository(integration: AnalysisIntegration) : AnalysisIntegrationRepository {
    override val analysisIntegration: Flow<AnalysisIntegration> = MutableStateFlow(integration)

    override suspend fun setAnalysisIntegration(integration: AnalysisIntegration) = error("not used in this test")
  }
}
