package info.bvlion.journalingpost.poster

import info.bvlion.journalingpost.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.content.TextContent
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import java.time.Instant
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

  private fun createPoster(
    statusCode: HttpStatusCode,
    onRequest: (HttpRequestData) -> Unit = {},
  ): WebhookJournalPoster {
    val mockEngine = MockEngine { request ->
      onRequest(request)
      respond(
        content = "{}",
        status = statusCode,
        headers = headersOf("Content-Type", listOf("application/json")),
      )
    }
    val httpClient = HttpClient(mockEngine) {
      install(ContentNegotiation) { json() }
    }
    return WebhookJournalPoster(httpClient, now = { fixedNow })
  }

  @Test
  fun `postは設定されたURLへjson content typeでPOSTリクエストを送る`() = runTest {
    var capturedRequest: HttpRequestData? = null
    val poster = createPoster(HttpStatusCode.OK) { capturedRequest = it }

    poster.post("today was good")

    val request = requireNotNull(capturedRequest)
    assertEquals(HttpMethod.Post, request.method)
    // local.propertiesが無い環境ではBuildConfig.POST_URLが文字列"null"になり、Ktorが相対URLとして
    // 正規化するため、送信先の実URLも同じ正規化を経たものと比較する（生文字列同士の比較にしない）。
    assertEquals(Url(BuildConfig.POST_URL).toString(), request.url.toString())
    assertEquals(ContentType.Application.Json, (request.body as TextContent).contentType.withoutParameters())
  }

  @Test
  fun `postは期待するpayloadフィールドを送信する`() = runTest {
    var capturedRequest: HttpRequestData? = null
    val poster = createPoster(HttpStatusCode.OK) { capturedRequest = it }

    poster.post("today was good")

    val body = Json.parseToJsonElement((capturedRequest!!.body as TextContent).text).jsonObject
    assertEquals(BuildConfig.TEAM_ID, body["team_id"]?.jsonPrimitive?.content)
    assertEquals(BuildConfig.TOKEN, body["token"]?.jsonPrimitive?.content)
    val event = body["event"]!!.jsonObject
    assertEquals(BuildConfig.CHANNEL, event["channel"]?.jsonPrimitive?.content)
    assertEquals(BuildConfig.USER, event["user"]?.jsonPrimitive?.content)
    assertEquals("today was good", event["text"]?.jsonPrimitive?.content)
    assertEquals("1700000000.123000", event["ts"]?.jsonPrimitive?.content)
  }

  @Test
  fun `postは2xxレスポンスでtrueを返す`() = runTest {
    val poster = createPoster(HttpStatusCode.OK)

    assertTrue(poster.post("today was good"))
  }

  @Test
  fun `postは3xxレスポンスでtrueを返す`() = runTest {
    val poster = createPoster(HttpStatusCode.Found)

    assertTrue(poster.post("today was good"))
  }

  @Test
  fun `postは4xxレスポンスでfalseを返す`() = runTest {
    val poster = createPoster(HttpStatusCode.BadRequest)

    assertFalse(poster.post("today was good"))
  }

  @Test
  fun `postは5xxレスポンスでfalseを返す`() = runTest {
    val poster = createPoster(HttpStatusCode.InternalServerError)

    assertFalse(poster.post("today was good"))
  }
}
