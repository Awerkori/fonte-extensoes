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
private class GateRequest(val returnTo: String)

@Serializable
private class GateResponse(val data: GateData)

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

internal suspend fun unlockReader(client: OkHttpClient, chapterUrl: String, headers: Headers, timing: GateTiming): Response {
    val chapter = chapterUrl.toHttpUrl()
    val baseUrl = "${chapter.scheme}://${chapter.host}"
    val gate = client.post("$baseUrl/api/gate/start", headers, GateRequest(chapter.encodedPath).toJsonRequestBody())
        .parseAs<GateResponse>().data
    val issued = TimeSource.Monotonic.markNow()
    // Both current gate variants accept the chapter token at this same-origin callback.
    val callback = gate.callback(chapterUrl)

    return timing.complete(gate.minWaitSeconds, issued::elapsedNow) {
        client.get(callback, headers, ensureSuccess = false)
    }
}
