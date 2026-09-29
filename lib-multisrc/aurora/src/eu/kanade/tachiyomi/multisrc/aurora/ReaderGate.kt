package eu.kanade.tachiyomi.multisrc.aurora

import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

@Serializable
internal class GateRequest(val returnTo: String)

@Serializable
internal class GateResponse(val data: GateData)

@Serializable
internal class GateData(val token: String, val returnTo: String, val minWaitSeconds: Int = 0) {
    fun callback(chapterUrl: String): okhttp3.HttpUrl {
        val chapter = chapterUrl.toHttpUrl()
        require(returnTo == chapter.encodedPath && temporaryToken.matches(token)) { "Aurora reader: invalid chapter gate" }
        return chapter.newBuilder().encodedPath("/gate/callback").query(null).fragment(null)
            .addQueryParameter("token", token).build()
    }

    companion object {
        private val temporaryToken = Regex("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
    }
}

internal class GateTiming {
    @Volatile
    private var minimumSeconds = 8

    suspend fun complete(
        serverMinimum: Int,
        elapsed: () -> Duration,
        wait: suspend (Duration) -> Unit = { delay(it) },
        request: suspend () -> Response,
    ): Response {
        // The direct gate reports 8s; the partner variant reports 0 but accepted 10s live.
        val initial = maxOf(minimumSeconds, serverMinimum.takeIf { it > 0 } ?: 10)
        wait((initial.seconds - elapsed()).coerceAtLeast(Duration.ZERO))
        val response = request()
        if (response.request.url.queryParameter("gate_error") != "too_fast") return response

        response.close()
        // Remember the refusal for this source instance instead of probing every chapter.
        minimumSeconds = 15
        wait((maxOf(15, initial + 5).seconds - elapsed()).coerceAtLeast(Duration.ZERO))
        return request()
    }
}

internal fun buildGateStartHeaders(headers: Headers, baseUrl: String, chapterUrl: String): Headers = headers.newBuilder()
    .set("Origin", baseUrl)
    .set("Referer", chapterUrl)
    .set("Sec-Fetch-Mode", "cors")
    .set("Sec-Fetch-Dest", "empty")
    .set("Sec-Fetch-Site", "same-origin")
    .build()

internal fun buildGateCallbackHeaders(headers: Headers, chapterUrl: String): Headers = headers.newBuilder()
    .set("Referer", chapterUrl)
    .set("Sec-Fetch-Mode", "navigate")
    .set("Sec-Fetch-Dest", "document")
    .set("Sec-Fetch-Site", "same-origin")
    .build()

internal suspend fun unlockReader(client: OkHttpClient, chapterUrl: String, headers: Headers, timing: GateTiming): Response {
    val chapter = chapterUrl.toHttpUrl()
    val baseUrl = "${chapter.scheme}://${chapter.host}"

    val gateHeaders = buildGateStartHeaders(headers, baseUrl, chapterUrl)
    val gateResponse = client.post(
        "$baseUrl/api/gate/start",
        gateHeaders,
        GateRequest(chapter.encodedPath).toJsonRequestBody(),
        ensureSuccess = false,
    )
    if (!gateResponse.isSuccessful) {
        val errorBody = gateResponse.body.string()
        if (gateResponse.code == 400 && "gate_disabled" in errorBody) {
            val unlockResponse = client.post(
                "$baseUrl/api/reader/unlock",
                gateHeaders,
                GateRequest(chapter.encodedPath).toJsonRequestBody(),
                ensureSuccess = false,
            )
            unlockResponse.close()
            return client.get(chapterUrl, headers, ensureSuccess = false)
        }
        error("Aurora reader gate start: $chapterUrl status=${gateResponse.code} error=${errorBody.take(100)}")
    }

    val gate = gateResponse.parseAs<GateResponse>().data
    val issued = TimeSource.Monotonic.markNow()
    val callback = gate.callback(chapterUrl)
    val callbackHeaders = buildGateCallbackHeaders(headers, chapterUrl)

    return timing.complete(gate.minWaitSeconds, issued::elapsedNow) {
        client.get(callback, callbackHeaders, ensureSuccess = false)
    }
}
