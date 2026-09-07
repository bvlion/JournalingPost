package info.bvlion.journalingpost.hosted

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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostedInstallationRegistrarTest {
  private val baseUrl = "https://hosted.example.test"

  @Test
  fun `保存済みAPI keyがあれば登録せずそれを返す`() = runTest {
    var requested = false
    val credentials = FakeHostedCredentialsRepository(stored = "jpk_stored")
    val registrar = registrar(credentials) { requested = true; respondJson("""{"installation":{"apiKey":"jpk_new"}}""") }

    assertEquals("jpk_stored", registrar.apiKey())
    assertFalse(requested)
  }

  @Test
  fun `未登録なら installations を叩いて発行し保存する`() = runTest {
    var path: String? = null
    var contentType: String? = null
    var body: String? = null
    var receivedRequestHash: String? = null
    val credentials = FakeHostedCredentialsRepository()
    val registrar = registrar(
      credentials = credentials,
      requestIntegrityToken = { requestHash ->
        receivedRequestHash = requestHash
        "integrity-token"
      },
    ) { request ->
      path = request.url.encodedPath
      contentType = request.body.contentType?.toString()
      body = String(request.body.toByteArray())
      respondJson("""{"installation":{"apiKey":"jpk_issued"}}""", HttpStatusCode.Created)
    }

    assertEquals("jpk_issued", registrar.apiKey())
    assertEquals("jpk_issued", credentials.stored)
    assertEquals("/v1/installations", path)
    assertEquals("application/json", contentType)
    val json = Json.parseToJsonElement(requireNotNull(body)).jsonObject
    assertEquals("a".repeat(64), json.getValue("registrationId").jsonPrimitive.content)
    assertEquals("integrity-token", json.getValue("integrityToken").jsonPrimitive.content)
    assertEquals(
      "POST\n/v1/installations\ninfo.bvlion.journalingpost\n${"a".repeat(64)}".sha256Hex(),
      receivedRequestHash,
    )
  }

  @Test
  fun `5xxはretryableな失敗`() = runTest {
    val registrar = registrar(FakeHostedCredentialsRepository()) {
      respondJson("""{}""", HttpStatusCode.ServiceUnavailable)
    }

    val thrown = runCatching { registrar.apiKey() }.exceptionOrNull()
    assertTrue(thrown is HostedRegistrationException && thrown.retryable)
  }

  @Test
  fun `登録を再試行すると新しいregistrationIdとIntegrity tokenを使う`() = runTest {
    var registrationSequence = 0
    val registrationIds = mutableListOf<String>()
    val requestHashes = mutableListOf<String>()
    val client = HttpClient(MockEngine { request ->
      registrationIds += Json.parseToJsonElement(String(request.body.toByteArray()))
        .jsonObject.getValue("registrationId").jsonPrimitive.content
      if (registrationIds.size == 1) {
        respondJson("{}", HttpStatusCode.ServiceUnavailable)
      } else {
        respondJson("""{"installation":{"apiKey":"jpk_issued"}}""", HttpStatusCode.Created)
      }
    }) {
      install(ContentNegotiation) { json() }
    }
    val registrar = HostedInstallationRegistrar(
      httpClient = client,
      credentialsRepository = FakeHostedCredentialsRepository(),
      baseUrl = baseUrl,
      packageName = "info.bvlion.journalingpost",
      requestIntegrityToken = { requestHash -> requestHashes += requestHash; "integrity-token" },
      registrationIdFactory = { (++registrationSequence).toString().padStart(64, 'a') },
    )

    assertTrue(runCatching { registrar.apiKey() }.isFailure)
    assertEquals("jpk_issued", registrar.apiKey())
    assertEquals(2, registrationIds.distinct().size)
    assertEquals(2, requestHashes.distinct().size)
  }

  @Test
  fun `4xxはretryできない失敗`() = runTest {
    val registrar = registrar(FakeHostedCredentialsRepository()) {
      respondJson("""{}""", HttpStatusCode.BadRequest)
    }

    val thrown = runCatching { registrar.apiKey() }.exceptionOrNull()
    assertTrue(thrown is HostedRegistrationException && !thrown.retryable)
  }

  @Test
  fun `送信失敗はretryableな失敗`() = runTest {
    val registrar = registrar(FakeHostedCredentialsRepository()) { throw IOException("boom") }

    val thrown = runCatching { registrar.apiKey() }.exceptionOrNull()
    assertTrue(thrown is HostedRegistrationException && thrown.retryable)
  }

  @Test
  fun `apiKeyが空のresponseはretryできない失敗`() = runTest {
    val registrar = registrar(FakeHostedCredentialsRepository()) {
      respondJson("""{"installation":{"apiKey":""}}""", HttpStatusCode.Created)
    }

    val thrown = runCatching { registrar.apiKey() }.exceptionOrNull()
    assertTrue(thrown is HostedRegistrationException && !thrown.retryable)
  }

  private fun MockRequestHandleScope.respondJson(
    content: String,
    status: HttpStatusCode = HttpStatusCode.OK,
  ): HttpResponseData = respond(content, status, headersOf(HttpHeaders.ContentType, "application/json"))

  private fun registrar(
    credentials: FakeHostedCredentialsRepository,
    requestIntegrityToken: suspend (String) -> String = { "integrity-token" },
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
  ): HostedInstallationRegistrar {
    val client = HttpClient(MockEngine { request -> handler(request) }) {
      install(ContentNegotiation) { json() }
    }
    return HostedInstallationRegistrar(
      httpClient = client,
      credentialsRepository = credentials,
      baseUrl = baseUrl,
      packageName = "info.bvlion.journalingpost",
      requestIntegrityToken = requestIntegrityToken,
      registrationIdFactory = { "a".repeat(64) },
    )
  }
}

internal class FakeHostedCredentialsRepository(
  var stored: String? = null,
) : HostedCredentialsRepository {
  var cleared = false
    private set

  override suspend fun apiKey(): String? = stored

  override suspend fun store(apiKey: String) {
    stored = apiKey
  }

  override suspend fun clear() {
    stored = null
    cleared = true
  }
}
