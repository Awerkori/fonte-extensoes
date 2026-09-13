package eu.kanade.tachiyomi.extension.pt.manganyx

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap

// Keep Aurora's reader intact: NXY requires Origin, and a domain 301 converts its gate POST to GET.
internal class ReaderOriginInterceptor(private val baseUrl: () -> String) : Interceptor {
    private val origins = ConcurrentHashMap<HttpUrl, HttpUrl>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val base = baseUrl().toHttpUrl().origin()
        val target = origins[base] ?: base
        val original = chain.request()
        if (original.url.origin() != base && original.url.origin() != target) return chain.proceed(original)

        val request = original.newBuilder()
            .url(original.url.onOrigin(target))
            .header("Alt-Used", target.host)
        original.header("Referer")?.toHttpUrlOrNull()?.let { referer ->
            if (referer.origin() == base || referer.origin() == target) {
                request.header("Referer", referer.onOrigin(target).toString())
            }
        }
        if (original.method == "POST" && original.url.encodedPath == "/api/gate/start") {
            request.header("Origin", target.toString().removeSuffix("/"))
                .header("Sec-Fetch-Site", "same-origin")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Dest", "empty")
        }
        val response = chain.proceed(request.build())
        val finalUrl = response.request.url
        val contentType = response.header("Content-Type").orEmpty()
        // Adopt only successful document redirects that preserve the resource path, never a CDN/login redirect.
        if (original.method == "GET" && response.isSuccessful &&
            finalUrl.encodedPath == original.url.encodedPath &&
            ("text/html" in contentType || "text/x-component" in contentType) &&
            (target.scheme != "https" || finalUrl.scheme == "https")
        ) {
            origins[base] = finalUrl.origin()
        }
        return response
    }

    private fun HttpUrl.origin(): HttpUrl = newBuilder().encodedPath("/").query(null).fragment(null).build()

    private fun HttpUrl.onOrigin(origin: HttpUrl): HttpUrl = newBuilder()
        .scheme(origin.scheme).host(origin.host).port(origin.port).build()
}
