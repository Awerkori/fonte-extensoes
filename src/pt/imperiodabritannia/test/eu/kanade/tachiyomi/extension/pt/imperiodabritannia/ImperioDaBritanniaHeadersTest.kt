package eu.kanade.tachiyomi.extension.pt.imperiodabritannia

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImperioDaBritanniaHeadersTest {

    private val baseUrl = "https://imperiodabritannia.net"
    private val apiUrl = "https://api.imperiodabritannia.net/api"

    private fun mockResponse(request: Request, code: Int = 200, body: String = "OK"): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("OK")
            .body(body.toResponseBody())
            .build()

    @Test
    fun interceptor_doesNotAddTokenOrTriggerNetworkForNonApiRequests() {
        var tokenResolutionCalled = false
        val interceptor = ImperioDaBritanniaTokenInterceptor(
            baseUrlProvider = { baseUrl },
            apiUrlProvider = { apiUrl },
            clientProvider = {
                tokenResolutionCalled = true
                OkHttpClient()
            },
        )

        val seenRequests = mutableListOf<Request>()
        val testClient = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .addInterceptor { chain ->
                val req = chain.request()
                seenRequests.add(req)
                mockResponse(req)
            }
            .build()

        // 1. Request to baseUrl (e.g. HTML page)
        testClient.newCall(Request.Builder().url(baseUrl).build()).execute().close()

        // 2. Request to CDN (e.g. image cover)
        testClient.newCall(Request.Builder().url("https://cdn.imperiodabritannia.net/covers/123.jpg").build()).execute().close()

        assertEquals(2, seenRequests.size)
        // Ensure X-API-Token was never attached
        assertNull(seenRequests[0].header("X-API-Token"))
        assertNull(seenRequests[1].header("X-API-Token"))
        // Ensure token resolution was never triggered
        assertEquals(false, tokenResolutionCalled)
    }

    @Test
    fun interceptor_attachesTokenForApiRequests() {
        val interceptor = ImperioDaBritanniaTokenInterceptor(
            baseUrlProvider = { baseUrl },
            apiUrlProvider = { apiUrl },
            clientProvider = { OkHttpClient() },
        ).apply {
            cachedToken = "test-token-123"
        }

        val seenRequests = mutableListOf<Request>()
        val testClient = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .addInterceptor { chain ->
                val req = chain.request()
                seenRequests.add(req)
                mockResponse(req)
            }
            .build()

        testClient.newCall(Request.Builder().url("$apiUrl/obras/top10/views").build()).execute().close()

        assertEquals(1, seenRequests.size)
        assertEquals("test-token-123", seenRequests[0].header("X-API-Token"))
    }

    @Test
    fun getApiToken_fetchesAndParsesToken() {
        val mockTokenClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                if (url.removeSuffix("/") == baseUrl.removeSuffix("/")) {
                    mockResponse(
                        chain.request(),
                        body = "<html><head><link href=\"$baseUrl/env.js\" rel=\"stylesheet\"></head></html>",
                    )
                } else if (url.startsWith("$baseUrl/env.js")) {
                    mockResponse(
                        chain.request(),
                        body = "window.env = { apiToken:`refreshed-token` };",
                    )
                } else {
                    mockResponse(chain.request(), code = 404)
                }
            }
            .build()

        val interceptor = ImperioDaBritanniaTokenInterceptor(
            baseUrlProvider = { baseUrl },
            apiUrlProvider = { apiUrl },
            clientProvider = { mockTokenClient },
        )

        val token = interceptor.getApiToken(forceRefresh = true)
        assertEquals("refreshed-token", token)
    }

    @Test
    fun interceptor_retriesWithRefreshedTokenOn401() {
        val mockTokenClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                if (url.removeSuffix("/") == baseUrl.removeSuffix("/")) {
                    mockResponse(
                        chain.request(),
                        body = "<html><head><link href=\"$baseUrl/env.js\" rel=\"stylesheet\"></head></html>",
                    )
                } else if (url.startsWith("$baseUrl/env.js")) {
                    mockResponse(
                        chain.request(),
                        body = "window.env = { apiToken:`refreshed-token` };",
                    )
                } else {
                    mockResponse(chain.request(), code = 404)
                }
            }
            .build()

        val interceptor = ImperioDaBritanniaTokenInterceptor(
            baseUrlProvider = { baseUrl },
            apiUrlProvider = { apiUrl },
            clientProvider = { mockTokenClient },
        ).apply {
            cachedToken = "old-token"
        }

        val tokensReceived = mutableListOf<String?>()
        val testClient = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .addInterceptor { chain ->
                val req = chain.request()
                val token = req.header("X-API-Token")
                tokensReceived.add(token)
                if (token == "old-token") {
                    mockResponse(req, code = 401, body = "Unauthorized")
                } else {
                    mockResponse(req, code = 200, body = "Success")
                }
            }
            .build()

        val response = testClient.newCall(Request.Builder().url("$apiUrl/obras/1").build()).execute()
        assertEquals(200, response.code)
        response.close()

        assertEquals(listOf("old-token", "refreshed-token"), tokensReceived)
    }
}
