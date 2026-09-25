package eu.kanade.tachiyomi.extension.pt.xxxyaoi

import com.sun.net.httpserver.HttpServer
import eu.kanade.tachiyomi.source.model.Page
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

class ImagePipelineTest {
    @Test fun realGetKeepsCookiesRefererAndImageBodyWithoutDocumentHeaders() {
        val received = CopyOnWriteArrayList<Map<String, String>>()
        val paths = CopyOnWriteArrayList<String>()
        val image = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a3ioAAAAASUVORK5CYII=")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            received += exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString() }
            paths += "${exchange.requestMethod} ${exchange.requestURI}"
            exchange.responseHeaders.set("Content-Type", "image/png")
            exchange.sendResponseHeaders(200, image.size.toLong())
            exchange.responseBody.use { it.write(image) }
        }
        server.start()
        val client = OkHttpClient.Builder().cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
            override fun loadForRequest(url: HttpUrl) = listOf(Cookie.Builder().name("test_session").value("fixture").hostOnlyDomain(url.host).build())
        }).build()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val chapter = "$base/bl/work/chapter/"
            for (index in 0..2) {
                val request = buildImageRequest(Page(index, chapter, "$base/$index.png?signature=test%2F$index"), "TestAgent")
                client.newCall(request).execute().use { response ->
                    assertEquals(200, response.code)
                    assertEquals("image/png", response.header("Content-Type"))
                    assertTrue(image.contentEquals(response.body.bytes()))
                }
            }
            assertEquals(3, received.size)
            assertEquals(3, paths.distinct().size)
            assertTrue(paths.all { it.startsWith("GET ") && it.contains("?signature=test%2F") })
            received.forEach { headers ->
                assertEquals("TestAgent", headers["user-agent"])
                assertEquals("image/*", headers["accept"])
                assertEquals(chapter, headers["referer"])
                assertEquals("test_session=fixture", headers["cookie"])
                assertFalse(headers.keys.any { it == "origin" || it.startsWith("sec-fetch-") || it == "upgrade-insecure-requests" })
            }
        } finally {
            server.stop(0)
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
