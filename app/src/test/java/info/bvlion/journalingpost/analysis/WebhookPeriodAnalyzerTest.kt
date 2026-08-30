package info.bvlion.journalingpost.analysis

import info.bvlion.journalingpost.journal.JournalEntry
import info.bvlion.journalingpost.journal.JournalSource
import info.bvlion.journalingpost.settings.AnalysisIntegration
import info.bvlion.journalingpost.settings.AnalysisIntegrationRepository
import info.bvlion.journalingpost.webhook.WebhookBodyTemplateRenderer
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
import io.ktor.utils.io.ByteChannel
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebhookPeriodAnalyzerTest {
  private val periodStart = Instant.parse("2026-08-30T00:00:00Z")
  private val periodEnd = Instant.parse("2026-08-31T00:00:00Z")
  private val oneEntry = listOf(entry(at = "2026-08-30T01:00:00Z", note = "メモ"))

  @Test
  fun `Webhook未設定ならWEBHOOK_UNAVAILABLEを返し送信しない`() = runTest {
    var requested = false
    val analyzer = analyzer(
      webhookState = WebhookSettingsState.NotConfigured,
      handler = { requested = true; respondText() },
    )

    assertEquals(PeriodAnalysisOutcome.Failure.WEBHOOK_UNAVAILABLE, analyzer.analyze(periodStart, periodEnd, oneEntry))
    assertFalse(requested)
  }

  @Test
  fun `実効AnalysisIntegrationがCUSTOM_WEBHOOK以外なら設定が残っていても送信しない`() = runTest {
    var requested = false
    val analyzer = analyzer(integration = AnalysisIntegration.NONE, handler = { requested = true; respondText() })

    assertEquals(PeriodAnalysisOutcome.Failure.WEBHOOK_UNAVAILABLE, analyzer.analyze(periodStart, periodEnd, oneEntry))
    assertFalse(requested)
  }

  @Test
  fun `対象entryが0件ならNO_ENTRIESを返し送信しない`() = runTest {
    var requested = false
    val analyzer = analyzer(handler = { requested = true; respondText() })

    assertEquals(PeriodAnalysisOutcome.Failure.NO_ENTRIES, analyzer.analyze(periodStart, periodEnd, emptyList()))
    assertFalse(requested)
  }

  @Test
  fun `Hosted契約のresponseからperiod・analyzedAt・textでSuccessを作る`() = runTest {
    val analyzer = analyzer(
      handler = {
        respondJson(
          hostedSuccessBody(
            start = "2026-08-29T00:00:00Z",
            end = "2026-08-29T09:00:00Z",
            analyzedAt = "2026-08-29T09:00:05Z",
            text = "今週は穏やかでした",
          ),
        )
      },
    )

    assertEquals(
      PeriodAnalysisOutcome.Success(
        periodStart = Instant.parse("2026-08-29T00:00:00Z"),
        periodEnd = Instant.parse("2026-08-29T09:00:00Z"),
        analyzedAt = Instant.parse("2026-08-29T09:00:05Z"),
        body = "今週は穏やかでした",
      ),
      analyzer.analyze(periodStart, periodEnd, oneEntry),
    )
  }

  @Test
  fun `analysis_textだけの不完全なresponseはINVALID_RESPONSE`() = runTest {
    val analyzer = analyzer(handler = { respondJson("""{"analysis":{"text":"ok"}}""") })

    assertEquals(
      PeriodAnalysisOutcome.Failure.INVALID_RESPONSE,
      analyzer.analyze(periodStart, periodEnd, oneEntry),
    )
  }

  @Test
  fun `period・analyzedAtがRFC3339でないとINVALID_RESPONSE`() = runTest {
    val analyzer = analyzer(handler = { respondJson(hostedSuccessBody(analyzedAt = "not a timestamp")) })

    assertEquals(
      PeriodAnalysisOutcome.Failure.INVALID_RESPONSE,
      analyzer.analyze(periodStart, periodEnd, oneEntry),
    )
  }

  @Test
  fun `request bodyは初期templateを展開したものになる`() = runTest {
    var body: String? = null
    val entries = listOf(
      entry(at = "2026-08-30T01:00:00Z", note = "メモだけ"),
      entry(at = "2026-08-30T09:00:00Z", moodId = "HAPPY", moodEmoji = "🙂", moodLabel = "嬉しい", note = null),
    )
    val analyzer = analyzer(handler = { request -> body = String(request.body.toByteArray()); respondText("ok") })

    analyzer.analyze(periodStart, periodEnd, entries)

    val json = Json.parseToJsonElement(requireNotNull(body)).jsonObject
    val period = json.getValue("period").jsonObject
    assertEquals("2026-08-30T00:00:00Z", period.getValue("start").jsonPrimitive.content)
    assertEquals("2026-08-31T00:00:00Z", period.getValue("end").jsonPrimitive.content)
    val jsonEntries = json.getValue("entries").jsonArray
    assertEquals(2, jsonEntries.size)
    // moodなし・noteあり
    assertEquals("2026-08-30T01:00:00Z", jsonEntries[0].jsonObject.getValue("recordedAt").jsonPrimitive.content)
    assertTrue(jsonEntries[0].jsonObject["mood"].let { it == null || it is JsonNull })
    assertEquals("メモだけ", jsonEntries[0].jsonObject.getValue("note").jsonPrimitive.content)
    // moodあり・noteなし。moodIdやAndroid内部IDは送らない
    val mood = jsonEntries[1].jsonObject.getValue("mood").jsonObject
    assertEquals("🙂", mood.getValue("emoji").jsonPrimitive.content)
    assertEquals("嬉しい", mood.getValue("label").jsonPrimitive.content)
    assertFalse(mood.containsKey("id"))
    assertTrue(jsonEntries[1].jsonObject["note"].let { it == null || it is JsonNull })
  }

  @Test
  fun `利用者が編集したtemplateがそのまま使われる`() = runTest {
    var body: String? = null
    val analyzer = analyzer(
      settings = settings(bodyTemplate = """{"start":"{{periodStart}}","items":{{entries}},"tag":"x"}"""),
      handler = { request -> body = String(request.body.toByteArray()); respondText("ok") },
    )

    analyzer.analyze(periodStart, periodEnd, oneEntry)

    val json = Json.parseToJsonElement(requireNotNull(body)).jsonObject
    assertEquals("2026-08-30T00:00:00Z", json.getValue("start").jsonPrimitive.content)
    assertEquals("x", json.getValue("tag").jsonPrimitive.content)
    assertEquals(1, json.getValue("items").jsonArray.size)
  }

  @Test
  fun `Hosted契約どおり未知フィールドは無視してSuccess`() = runTest {
    val analyzer = analyzer(
      handler = {
        respondJson(
          """
          {
            "analysis": {
              "period": { "start": "2026-08-29T00:00:00Z", "end": "2026-08-29T09:00:00Z", "extra": 1 },
              "analyzedAt": "2026-08-29T09:00:05Z",
              "entryCount": 3,
              "model": "example/analysis-model",
              "text": "ok",
              "future": "field"
            },
            "meta": true
          }
          """.trimIndent(),
        )
      },
    )

    val outcome = analyzer.analyze(periodStart, periodEnd, oneEntry)
    assertTrue(outcome is PeriodAnalysisOutcome.Success)
    assertEquals("ok", (outcome as PeriodAnalysisOutcome.Success).body)
  }

  @Test
  fun `analysis_textが空文字ならINVALID_RESPONSE`() = runTest {
    val analyzer = analyzer(handler = { respondJson(hostedSuccessBody(text = "  ")) })

    assertEquals(
      PeriodAnalysisOutcome.Failure.INVALID_RESPONSE,
      analyzer.analyze(periodStart, periodEnd, oneEntry),
    )
  }

  @Test
  fun `analysisキーを含まないresponseはINVALID_RESPONSE`() = runTest {
    val analyzer = analyzer(handler = { respondJson("""{"text":"ok"}""") })

    assertEquals(
      PeriodAnalysisOutcome.Failure.INVALID_RESPONSE,
      analyzer.analyze(periodStart, periodEnd, oneEntry),
    )
  }

  @Test
  fun `HTTP 500はSERVER_ERROR`() = runTest {
    val analyzer = analyzer(handler = { respondJson("""{"analysis":{"text":"x"}}""", HttpStatusCode.InternalServerError) })

    assertEquals(PeriodAnalysisOutcome.Failure.SERVER_ERROR, analyzer.analyze(periodStart, periodEnd, oneEntry))
  }

  @Test
  fun `3xxは成功扱いにせずSERVER_ERROR`() = runTest {
    val analyzer = analyzer(handler = { respondJson("""{"analysis":{"text":"x"}}""", HttpStatusCode.Found) })

    assertEquals(PeriodAnalysisOutcome.Failure.SERVER_ERROR, analyzer.analyze(periodStart, periodEnd, oneEntry))
  }

  @Test
  fun `送信時の例外はNETWORK`() = runTest {
    val analyzer = analyzer(handler = { throw IOException("boom") })

    assertEquals(PeriodAnalysisOutcome.Failure.NETWORK, analyzer.analyze(periodStart, periodEnd, oneEntry))
  }

  @Test
  fun `response本文の受信失敗はINVALID_RESPONSEではなくNETWORK`() = runTest {
    val analyzer = analyzer(
      handler = {
        respond(
          content = ByteChannel(autoFlush = true).apply { cancel(IOException("connection reset")) },
          status = HttpStatusCode.OK,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
      },
    )

    assertEquals(PeriodAnalysisOutcome.Failure.NETWORK, analyzer.analyze(periodStart, periodEnd, oneEntry))
  }

  @Test
  fun `設定済みHeaderが送信requestへ付与される`() = runTest {
    var authorization: String? = null
    val analyzer = analyzer(
      settings = settings(headers = listOf(WebhookHeader("Authorization", "Bearer secret"))),
      handler = { request ->
        authorization = request.headers[HttpHeaders.Authorization]
        respondText("ok")
      },
    )

    analyzer.analyze(periodStart, periodEnd, oneEntry)

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

  private fun MockRequestHandleScope.respondText(text: String = "ok"): HttpResponseData =
    respondJson(hostedSuccessBody(text = text))

  private fun hostedSuccessBody(
    start: String = "2026-08-29T00:00:00Z",
    end: String = "2026-08-29T09:00:00Z",
    analyzedAt: String = "2026-08-29T09:00:05Z",
    text: String = "ok",
  ): String = """
    {
      "analysis": {
        "period": { "start": "$start", "end": "$end" },
        "analyzedAt": "$analyzedAt",
        "entryCount": 3,
        "model": "example/analysis-model",
        "text": "$text"
      }
    }
  """.trimIndent()

  private fun settings(
    bodyTemplate: String = WebhookBodyTemplateRenderer.DEFAULT_TEMPLATE,
    headers: List<WebhookHeader> = emptyList(),
  ) = WebhookSettings(url = "https://example.com/analyze", headers = headers, bodyTemplate = bodyTemplate)

  private fun analyzer(
    settings: WebhookSettings = settings(),
    webhookState: WebhookSettingsState = WebhookSettingsState.Configured(settings),
    integration: AnalysisIntegration = AnalysisIntegration.CUSTOM_WEBHOOK,
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
  ): WebhookPeriodAnalyzer {
    val client = HttpClient(MockEngine { request -> handler(request) }) {
      install(ContentNegotiation) { json() }
    }
    return WebhookPeriodAnalyzer(
      httpClient = client,
      analysisIntegrationRepository = FakeAnalysisIntegrationRepository(integration),
      webhookSettingsRepository = FakeWebhookSettingsRepository(webhookState),
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
