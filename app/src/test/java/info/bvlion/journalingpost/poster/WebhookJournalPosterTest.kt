package info.bvlion.journalingpost.poster

import info.bvlion.journalingpost.webhook.WebhookHeader
import info.bvlion.journalingpost.webhook.WebhookSettings
import info.bvlion.journalingpost.webhook.WebhookSettingsRepository
import info.bvlion.journalingpost.webhook.WebhookSettingsState
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.content.TextContent
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebhookJournalPosterTest {
  private val fixedNow = Instant.ofEpochSecond(1_700_000_000L, 123_000_000L)
  private val settings = WebhookSettings(
    url = "https://example.com/webhook",
    headers = listOf(WebhookHeader("Authorization", "Bearer xxxxx")),
    bodyTemplate = """{"text": "{{message}}", "ts": "{{timestamp}}"}""",
  )

  private fun createPoster(
    statusCode: HttpStatusCode,
    repository: WebhookSettingsRepository = FakeWebhookSettingsRepository(settings),
    onRequest: (HttpRequestData) -> Unit = {},
  ): WebhookJournalPoster {
    val engine = MockEngine { request ->
      onRequest(request)
      respond("{}", statusCode, headersOf("Content-Type", listOf("application/json")))
    }
    return WebhookJournalPoster(HttpClient(engine), repository, now = { fixedNow })
  }

  @Test
  fun `設定されたURLへjson content typeでPOSTする`() = runTest {
    var captured: HttpRequestData? = null
    createPoster(HttpStatusCode.OK) { captured = it }.post("today was good")

    val request = requireNotNull(captured)
    assertEquals(HttpMethod.Post, request.method)
    assertEquals(settings.url, request.url.toString())
    assertEquals("application/json", (request.body as TextContent).contentType.toString())
  }

  @Test
  fun `設定されたHeaderを送信する`() = runTest {
    var captured: HttpRequestData? = null
    createPoster(HttpStatusCode.OK) { captured = it }.post("today was good")

    assertEquals("Bearer xxxxx", requireNotNull(captured).headers["Authorization"])
  }

  @Test
  fun `body templateからmessageとtimestampを含むJSONを送信する`() = runTest {
    var captured: HttpRequestData? = null
    createPoster(HttpStatusCode.OK) { captured = it }.post("today was good")

    val body = Json.parseToJsonElement((requireNotNull(captured).body as TextContent).text).jsonObject
    assertEquals("today was good", body["text"]?.jsonPrimitive?.content)
    assertEquals("1700000000.123000", body["ts"]?.jsonPrimitive?.content)
  }

  @Test
  fun `3xxまでは成功として扱う`() = runTest {
    assertTrue(createPoster(HttpStatusCode.Found).post("today was good"))
  }

  @Test
  fun `4xx以降は失敗として扱う`() = runTest {
    assertFalse(createPoster(HttpStatusCode.BadRequest).post("today was good"))
  }

  @Test
  fun `設定未登録ならHTTP requestを開始しない`() = runTest {
    var requestStarted = false
    val poster = createPoster(HttpStatusCode.OK, FakeWebhookSettingsRepository(null)) { requestStarted = true }

    assertFalse(poster.post("today was good"))
    assertFalse(requestStarted)
  }

  @Test
  fun `body templateが不正ならHTTP requestを開始しない`() = runTest {
    var requestStarted = false
    val invalid = settings.copy(bodyTemplate = "{not valid json")
    val poster = createPoster(HttpStatusCode.OK, FakeWebhookSettingsRepository(invalid)) { requestStarted = true }

    assertFalse(poster.post("today was good"))
    assertFalse(requestStarted)
  }

  @Test
  fun `1回のpostでは取得済みsettings snapshotだけを使う`() = runTest {
    val repository = FakeWebhookSettingsRepository(settings)
    var captured: HttpRequestData? = null
    val poster = createPoster(HttpStatusCode.OK, repository) { request ->
      captured = request
      repository.update(settings.copy(url = "https://changed.example.com/webhook"))
    }

    poster.post("today was good")

    assertEquals(settings.url, requireNotNull(captured).url.toString())
  }

  private class FakeWebhookSettingsRepository(initial: WebhookSettings?) : WebhookSettingsRepository {
    private val state = MutableStateFlow(initial.toState())
    override val settings: Flow<WebhookSettingsState> = state

    fun update(value: WebhookSettings?) {
      state.value = value.toState()
    }

    override suspend fun save(settings: WebhookSettings) {
      state.value = WebhookSettingsState.Configured(settings)
    }

    private fun WebhookSettings?.toState(): WebhookSettingsState =
      this?.let { WebhookSettingsState.Configured(it) } ?: WebhookSettingsState.NotConfigured
  }
}
