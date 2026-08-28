package info.bvlion.journalingpost.poster

import info.bvlion.journalingpost.webhook.LegacyWebhookConfig
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
  private val fixedNow: Instant = Instant.ofEpochSecond(1_700_000_000L, 123_000_000L)

  private val settings = WebhookSettings(
    url = "https://example.com/webhook",
    headers = listOf(WebhookHeader("Authorization", "Bearer xxxxx")),
    bodyTemplate = """{"text": "{{message}}", "ts": "{{timestamp}}"}""",
  )

  private fun createPoster(
    statusCode: HttpStatusCode,
    repository: WebhookSettingsRepository = FakeWebhookSettingsRepository(settings),
    legacyConfigProvider: () -> LegacyWebhookConfig? = { error("not used in this test") },
    onRequest: (HttpRequestData) -> Unit = {},
  ): WebhookJournalPoster {
    val mockEngine = MockEngine { request ->
      onRequest(request)
      respond(content = "{}", status = statusCode, headers = headersOf("Content-Type", listOf("application/json")))
    }
    return WebhookJournalPoster(HttpClient(mockEngine), repository, now = { fixedNow }, legacyConfigProvider = legacyConfigProvider)
  }

  @Test
  fun `postは設定されたURLへjson content typeでPOSTリクエストを送る`() = runTest {
    var capturedRequest: HttpRequestData? = null
    val poster = createPoster(HttpStatusCode.OK) { capturedRequest = it }

    poster.post("today was good")

    val request = requireNotNull(capturedRequest)
    assertEquals(HttpMethod.Post, request.method)
    assertEquals(settings.url, request.url.toString())
    assertEquals("application/json", (request.body as TextContent).contentType.toString())
  }

  @Test
  fun `postはruntime headerを含めて送信する`() = runTest {
    var capturedRequest: HttpRequestData? = null
    val poster = createPoster(HttpStatusCode.OK) { capturedRequest = it }

    poster.post("today was good")

    val request = requireNotNull(capturedRequest)
    assertEquals("Bearer xxxxx", request.headers["Authorization"])
  }

  @Test
  fun `postはbody templateから生成したJSONを送信する`() = runTest {
    var capturedRequest: HttpRequestData? = null
    val poster = createPoster(HttpStatusCode.OK) { capturedRequest = it }

    poster.post("today was good")

    val body = Json.parseToJsonElement((capturedRequest!!.body as TextContent).text).jsonObject
    assertEquals("today was good", body["text"]?.jsonPrimitive?.content)
    assertEquals("1700000000.123000", body["ts"]?.jsonPrimitive?.content)
  }

  @Test
  fun `postは2xxレスポンスでtrueを返す`() = runTest {
    assertTrue(createPoster(HttpStatusCode.OK).post("today was good"))
  }

  @Test
  fun `postは3xxレスポンスでtrueを返す`() = runTest {
    assertTrue(createPoster(HttpStatusCode.Found).post("today was good"))
  }

  @Test
  fun `postは4xxレスポンスでfalseを返す`() = runTest {
    assertFalse(createPoster(HttpStatusCode.BadRequest).post("today was good"))
  }

  @Test
  fun `postは5xxレスポンスでfalseを返す`() = runTest {
    assertFalse(createPoster(HttpStatusCode.InternalServerError).post("today was good"))
  }

  @Test
  fun `設定未登録ならHTTP requestを開始せずfalseを返す`() = runTest {
    var requestStarted = false
    val poster = createPoster(HttpStatusCode.OK, repository = FakeWebhookSettingsRepository(null)) { requestStarted = true }

    val result = poster.post("today was good")

    assertFalse(result)
    assertFalse(requestStarted)
  }

  @Test
  fun `body templateが不正な場合はHTTP requestを開始せずfalseを返す`() = runTest {
    var requestStarted = false
    val invalidSettings = settings.copy(bodyTemplate = "{not valid json")
    val poster = createPoster(HttpStatusCode.OK, repository = FakeWebhookSettingsRepository(invalidSettings)) { requestStarted = true }

    val result = poster.post("today was good")

    assertFalse(result)
    assertFalse(requestStarted)
  }

  @Test
  fun `1回のpost内では取得したsettings snapshotだけを使い続ける`() = runTest {
    val repository = FakeWebhookSettingsRepository(settings)
    var capturedRequest: HttpRequestData? = null
    val poster = createPoster(HttpStatusCode.OK, repository = repository) { request ->
      capturedRequest = request
      // request送信中に設定が変更されても、既に構築済みのrequestには影響しないことを確認する。
      repository.update(settings.copy(url = "https://changed.example.com/webhook"))
    }

    poster.post("today was good")

    assertEquals(settings.url, requireNotNull(capturedRequest).url.toString())
  }

  @Test
  fun `未設定かつlegacy設定がある場合はpost内でmigrationしてから送信する`() = runTest {
    val repository = FakeWebhookSettingsRepository(initial = null, migrationCompleted = false)
    val legacy = LegacyWebhookConfig(
      postUrl = "https://legacy.example.com/webhook",
      teamId = "T1",
      token = "TOKEN",
      channel = "C1",
      user = "U1",
    )
    var capturedRequest: HttpRequestData? = null
    val poster = createPoster(
      HttpStatusCode.OK,
      repository = repository,
      legacyConfigProvider = { legacy },
    ) { capturedRequest = it }

    val result = poster.post("today was good")

    assertTrue(result)
    assertEquals(legacy.postUrl, requireNotNull(capturedRequest).url.toString())
    assertTrue(repository.isLegacyMigrationCompleted())
  }

  /** migrationCompletedはデフォルトでtrue(migration済み)にし、送信挙動だけを検証するテストをlegacy providerと無関係にする。 */
  private class FakeWebhookSettingsRepository(
    initial: WebhookSettings?,
    private var migrationCompleted: Boolean = true,
  ) : WebhookSettingsRepository {
    private val state = MutableStateFlow(initial.toState())
    override val settings: Flow<WebhookSettingsState> = state

    fun update(value: WebhookSettings?) {
      state.value = value.toState()
    }

    override suspend fun save(settings: WebhookSettings) {
      state.value = WebhookSettingsState.Configured(settings)
    }

    override suspend fun clear() {
      state.value = WebhookSettingsState.NotConfigured
    }

    override suspend fun isLegacyMigrationCompleted(): Boolean = migrationCompleted

    override suspend fun markLegacyMigrationCompleted() {
      migrationCompleted = true
    }

    private fun WebhookSettings?.toState(): WebhookSettingsState =
      this?.let { WebhookSettingsState.Configured(it) } ?: WebhookSettingsState.NotConfigured
  }
}
