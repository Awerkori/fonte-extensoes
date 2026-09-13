package eu.kanade.tachiyomi.extension.pt.manganyx

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderOriginTest {
    private fun response(request: Request, type: String = "text/x-component") = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
        .header("Content-Type", type).body("fixture".toResponseBody()).build()

    @Test
    fun realDomainRedirectPreservesPostAndRebasesGateKeyAndReferer() {
        val seen = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor(ReaderOriginInterceptor { "https://old.example" })
            .addInterceptor { chain ->
                val request = chain.request()
                seen += request
                if (request.url.host == "old.example") {
                    response(request.newBuilder().url("https://new.example/manga/one-piece/1193").build())
                        .newBuilder().priorResponse(
                            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(301)
                                .message("Moved").header("Location", "https://new.example/manga/one-piece/1193").build(),
                        ).build()
                } else {
                    response(request)
                }
            }.build()
        client.newCall(Request.Builder().url("https://old.example/manga/one-piece/1193").build()).execute().close()
        client.newCall(
            Request.Builder().url("https://old.example/api/gate/start")
                .header("Referer", "https://old.example/manga/one-piece")
                .post("{}".toRequestBody()).build(),
        ).execute().close()
        client.newCall(Request.Builder().url("https://old.example/api/atfield/key?v=1&e=123").build()).execute().close()
        assertEquals("POST", seen[1].method)
        assertEquals("https://new.example/api/gate/start", seen[1].url.toString())
        assertEquals("https://new.example", seen[1].header("Origin"))
        assertEquals("https://new.example/manga/one-piece", seen[1].header("Referer"))
        assertEquals("https://new.example/api/atfield/key?v=1&e=123", seen[2].url.toString())
    }

    @Test
    fun currentOriginNeedsNoRedirectAndImageRequestsAreUntouched() {
        val seen = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor(ReaderOriginInterceptor { "https://manganyx.org" })
            .addInterceptor { chain ->
                seen += chain.request()
                response(chain.request())
            }.build()
        client.newCall(Request.Builder().url("https://manganyx.org/api/gate/start").post("{}".toRequestBody()).build()).execute().close()
        client.newCall(Request.Builder().url("https://cdn.example/pages/1.webp?x=a%2Fb").build()).execute().close()
        assertEquals("https://manganyx.org", seen[0].header("Origin"))
        assertEquals("https://cdn.example/pages/1.webp?x=a%2Fb", seen[1].url.toString())
        assertNull(seen[1].header("Origin"))
    }

    @Test
    fun loginImageAndInsecureRedirectsCannotChangeGateOrigin() {
        for ((redirect, type) in listOf(
            "https://other.example/login" to "text/html",
            "https://cdn.example/manga/series/1" to "image/webp",
            "http://other.example/manga/series/1" to "text/html",
        )) {
            val seen = mutableListOf<Request>()
            val client = OkHttpClient.Builder().addInterceptor(ReaderOriginInterceptor { "https://reader.example" })
                .addInterceptor { chain ->
                    seen += chain.request()
                    response(chain.request().newBuilder().url(redirect).build(), type)
                }.build()
            client.newCall(Request.Builder().url("https://reader.example/manga/series/1").build()).execute().close()
            client.newCall(Request.Builder().url("https://reader.example/api/gate/start").post("{}".toRequestBody()).build()).execute().close()
            assertEquals("https://reader.example", seen[1].header("Origin"))
            assertEquals("reader.example", seen[1].url.host)
        }
    }
}
