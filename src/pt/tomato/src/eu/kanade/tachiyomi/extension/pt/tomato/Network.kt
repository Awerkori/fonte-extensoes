package eu.kanade.tachiyomi.extension.pt.tomato

import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

internal const val PROD_API_URL = "https://prod-api.tomatoanimes.com"
internal const val OFFICIAL_CONNECT_TIMEOUT_MS = 8_000
private const val EDGE_API_HOST = "edge.betomato.com"
private const val PROD_API_HOST = "prod-api.tomatoanimes.com"
private const val OFFICIAL_USER_AGENT = "okhttp/4.11.0"
private const val OFFICIAL_ACCEPT = "application/json, text/plain, */*"
private const val OFFICIAL_ACCEPT_ENCODING = "gzip, deflate"
private const val MAX_ERROR_BODY_BYTES = 256L * 1024L
private val NATIVE_USER_AGENT = System.getProperty("http.agent")?.filter { it.code in 0x20..0x7e }?.takeIf(String::isNotBlank) ?: "Dalvik/2.1.0"
internal fun Headers.withBearer(token: String?): Headers = newBuilder().set("Accept", "application/json").apply { token?.trim()?.removePrefix("Bearer ")?.takeIf(String::isNotEmpty)?.let { set("Authorization", "Bearer $it") } }.build()
internal fun Headers.withNativeAuthHeaders(): Headers = newBuilder().set("Accept", "application/json").set("User-Agent", NATIVE_USER_AGENT).removeAll("Authorization").build()
internal fun Request.withOfficialClientHeaders(appVersion: String): Request = if (!usesOfficialClientContract()) this else newBuilder().header("Accept", OFFICIAL_ACCEPT).header("Accept-Encoding", OFFICIAL_ACCEPT_ENCODING).header("User-Agent", OFFICIAL_USER_AGENT).header("request-time", System.currentTimeMillis().toString()).build()
internal fun Request.usesOfficialClientContract(): Boolean = url.host in setOf(PROD_API_HOST, EDGE_API_HOST) && (url.encodedPath == "/v2/content/search" || url.encodedPath == "/v2/manga/feed" || url.encodedPath.startsWith("/manga/") || url.encodedPath == "/checkupdate/" || url.encodedPath == "/tokenlogin/" || url.encodedPath == "/login/" || url.encodedPath == "/register/")
internal fun IOException.isConnectionFailure() = this is UnknownHostException || this is ConnectException || this is SocketTimeoutException
internal fun Response.decodeContentEncoding(): Response {
    val encoding = header("Content-Encoding")?.substringBefore(',')?.trim()?.lowercase()
    val decoded = when (encoding) {
        "gzip" -> GZIPInputStream(body.byteStream()).use { it.readBytes() }
        "deflate" -> InflaterInputStream(body.byteStream()).use { it.readBytes() }
        else -> return this
    }
    return newBuilder().removeHeader("Content-Encoding").removeHeader("Content-Length").body(decoded.toResponseBody(body.contentType())).build()
}
internal fun Response.requireSuccess(): Response {
    if (isSuccessful) return this
    val apiMessage = runCatching { JSONObject(peekBody(MAX_ERROR_BODY_BYTES).string()).optString("message").takeIf(String::isNotBlank) }.getOrNull()
    close()
    throw IOException(
        apiMessage ?: when (code) {
            401, 403 -> "Sua sessão da Tomato expirou. Entre novamente."
            429 -> "Muitas solicitações à Tomato."
            else -> "A Tomato retornou HTTP $code."
        },
    )
}
