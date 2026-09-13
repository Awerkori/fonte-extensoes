package eu.kanade.tachiyomi.multisrc.aurora

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class GateTimingTest {
    private fun response(error: String? = null) = Response.Builder()
        .request(Request.Builder().url("https://reader.example/manga/series/1" + (error?.let { "?gate_error=$it" } ?: "")).build())
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body("fixture".toResponseBody())
        .build()

    @Test
    fun declaredEightSecondsIsUsedWithoutGoingBelowValidatedFloor() = runBlocking {
        for (declared in listOf(8, 1)) {
            var elapsed = 2.seconds
            GateTiming().complete(declared, { elapsed }, { elapsed += it }) {
                assertEquals(8.seconds, elapsed)
                response()
            }.close()
        }
    }

    @Test
    fun partnerTimeCountsTowardTenSeconds() = runBlocking {
        var elapsed = 3.seconds
        var calls = 0
        GateTiming().complete(0, { elapsed }, { elapsed += it }) {
            calls++
            assertEquals(10.seconds, elapsed)
            response()
        }.close()
        assertEquals(1, calls)
    }

    @Test
    fun tooFastRecoversOnceAndIsRememberedForNextChapter() = runBlocking {
        val timing = GateTiming()
        var elapsed = Duration.ZERO
        val attempts = mutableListOf<Duration>()
        val result = timing.complete(0, { elapsed }, { elapsed += it }) {
            attempts += elapsed
            response(if (attempts.size == 1) "too_fast" else null)
        }
        result.use { assertEquals(null, it.request.url.queryParameter("gate_error")) }
        assertEquals(listOf(10.seconds, 15.seconds), attempts)

        elapsed = Duration.ZERO
        timing.complete(0, { elapsed }, { elapsed += it }) {
            assertEquals(15.seconds, elapsed)
            response()
        }.close()
    }

    @Test
    fun serverMinimumIsRespectedAndRetriesAreBounded() = runBlocking {
        var elapsed = Duration.ZERO
        val attempts = mutableListOf<Duration>()
        GateTiming().complete(20, { elapsed }, { elapsed += it }) {
            attempts += elapsed
            response("too_fast")
        }.close()
        assertEquals(listOf(20.seconds, 25.seconds), attempts)
    }

    @Test
    fun expiredTokenIsNotRetried() = runBlocking {
        var calls = 0
        GateTiming().complete(0, { 30.seconds }, { assertEquals(Duration.ZERO, it) }) {
            calls++
            response("expired")
        }.close()
        assertEquals(1, calls)
    }

    @Test
    fun cancellingWaitDoesNotSendCallback() {
        var calls = 0
        assertThrows(CancellationException::class.java) {
            runBlocking {
                GateTiming().complete(0, { Duration.ZERO }, { throw CancellationException() }) {
                    calls++
                    response()
                }
            }
        }
        assertEquals(0, calls)
    }
}
