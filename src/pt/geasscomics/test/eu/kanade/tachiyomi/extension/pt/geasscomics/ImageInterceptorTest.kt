package eu.kanade.tachiyomi.extension.pt.geasscomics

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertSame
import org.junit.Test
import java.lang.reflect.Proxy

class ImageInterceptorTest {
    @Test
    fun unrelatedNormalUnsupportedAndNonImageResponsesAreUntouched() {
        val cases = listOf(
            Triple("https://cdn.geasscomics.xyz/covers/a.webp#geass:v2:240:a060bc9", "image/webp", 200),
            Triple("https://other.example/pages/a.webp#geass:v2:240:a060bc9", "image/webp", 200),
            Triple("https://cdn.geasscomics.xyz/pages/a.webp", "image/webp", 200),
            Triple("https://cdn.geasscomics.xyz/pages/a.webp#geass:v3:240:a060bc9", "image/webp", 200),
            Triple("https://cdn.geasscomics.xyz/pages/a.webp#geass:v2:240:a060bc9", "application/json", 200),
            Triple("https://cdn.geasscomics.xyz/pages/a.webp#geass:v2:240:a060bc9", "image/webp", 404),
        )
        for ((url, type, code) in cases) {
            val request = Request.Builder().url(url).build()
            val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(code).message("test").body("unchanged".toResponseBody(type.toMediaType())).build()
            val chain = Proxy.newProxyInstance(
                Interceptor.Chain::class.java.classLoader,
                arrayOf(Interceptor.Chain::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "request" -> request
                    "proceed" -> response
                    else -> error("Unexpected chain call: ${method.name}")
                }
            } as Interceptor.Chain
            response.use { assertSame(url, response, ImageInterceptor().intercept(chain)) }
        }
    }
}
