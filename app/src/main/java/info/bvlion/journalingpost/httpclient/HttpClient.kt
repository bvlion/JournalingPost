package info.bvlion.journalingpost.httpclient

import info.bvlion.journalingpost.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.Locale

val client = HttpClient(CIO) {
  install(ContentNegotiation) {
    json()
  }
}

@Serializable
data class RequestData(
  @SerialName("team_id") val teamId: String = BuildConfig.TEAM_ID,
  val token: String = BuildConfig.TOKEN,
  val event: Events,
)

@Serializable
data class Events(
  val channel: String = BuildConfig.CHANNEL,
  val user: String = BuildConfig.USER,
  val ts: String,
  val text: String,
)

suspend fun sendPostRequest(message: String): HttpResponse {
  return client.post(BuildConfig.POST_URL) {
    contentType(ContentType.Application.Json)
    setBody(RequestData(
      event = Events(
        ts = Instant.now().let {
          String.format(Locale.US, "%.6f", it.epochSecond + it.nano / 1_000_000_000.0)
        },
        text = message
      )
    ))
  }
}