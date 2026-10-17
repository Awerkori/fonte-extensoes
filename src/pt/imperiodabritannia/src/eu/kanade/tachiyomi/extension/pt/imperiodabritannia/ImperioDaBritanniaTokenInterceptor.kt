package eu.kanade.tachiyomi.extension.pt.imperiodabritannia

import keiyoushi.utils.asJsoup
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal class ImperioDaBritanniaTokenInterceptor(
    private val baseUrlProvider: () -> String,
    private val apiUrlProvider: () -> String,
    private val clientProvider: () -> OkHttpClient,
) : Interceptor {

    @Volatile
    internal var cachedToken: String? = null

    @Synchronized
    internal fun getApiToken(forceRefresh: Boolean = false): String {
        if (!forceRefresh) {
            cachedToken?.takeIf { it.isNotBlank() }?.let { return it }
        }
        val baseUrl = baseUrlProvider()
        val client = clientProvider()
        val token = runCatching {
            val url = client.newCall(Request.Builder().url(baseUrl).build()).execute().asJsoup()
                .selectFirst("link[href*=env]")
                ?.absUrl("href") ?: return@runCatching ""
            API_TOKEN_REGEX.find(client.newCall(Request.Builder().url(url).build()).execute().body.string())?.groupValues?.last() ?: ""
        }.getOrDefault("")
        if (token.isNotBlank()) {
            cachedToken = token
        }
        return token
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()
        val apiUrl = apiUrlProvider()

        if (request.header("X-API-Token") != null || !url.startsWith(apiUrl)) {
            return chain.proceed(request)
        }

        val token = getApiToken()
        val newRequest = if (token.isNotBlank()) {
            request.newBuilder()
                .header("X-API-Token", token)
                .build()
        } else {
            request
        }

        val response = chain.proceed(newRequest)
        if (response.code in 401..403 && token.isNotBlank()) {
            response.close()
            val refreshedToken = getApiToken(forceRefresh = true)
            if (refreshedToken.isNotBlank() && refreshedToken != token) {
                val retryRequest = request.newBuilder()
                    .header("X-API-Token", refreshedToken)
                    .build()
                return chain.proceed(retryRequest)
            }
        }
        return response
    }

    companion object {
        internal val API_TOKEN_REGEX = """apiToken:.([^`]+)""".toRegex()
    }
}
