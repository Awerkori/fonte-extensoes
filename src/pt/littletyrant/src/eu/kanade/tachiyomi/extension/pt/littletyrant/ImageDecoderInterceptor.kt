package eu.kanade.tachiyomi.extension.pt.littletyrant

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class ImageDecoderInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // 1. Intercept gatekeeper requests as a network-level safety net to guarantee lt_browser_nonce is present
        if (request.url.encodedPath.contains("gatekeeper.php")) {
            val nonce = request.url.queryParameter("browser_nonce")
            val existingCookie = request.header("Cookie")
            val newCookie = when {
                existingCookie.isNullOrBlank() -> if (!nonce.isNullOrBlank()) "lt_browser_nonce=$nonce" else ""
                !existingCookie.contains("lt_browser_nonce") && !nonce.isNullOrBlank() -> "$existingCookie; lt_browser_nonce=$nonce"
                else -> existingCookie
            }
            val newRequest = request.newBuilder()
                .apply {
                    if (newCookie.isNotBlank()) header("Cookie", newCookie)
                }
                .header("X-Reader-Sec", "tiraninha-web")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Dest", "empty")
                .header("Sec-Fetch-Site", "same-origin")
                .build()

            val response = chain.proceed(newRequest)
            val hasNonce = newRequest.header("Cookie")?.contains("lt_browser_nonce") == true
            Log.d(
                "LittleTyrantLog",
                "GATEKEEPER status=${response.code} host=${request.url.host} contentType=${response.header("Content-Type")} hasNonceCookie=$hasNonce",
            )
            return response
        }

        // 2. Intercept image requests
        val fragment = request.url.fragment
        if (fragment.isNullOrBlank() || !fragment.contains("token")) {
            return chain.proceed(request)
        }

        val tokenAndNonce = parseTokenAndNonce(fragment) ?: return chain.proceed(request)
        val (token, nonce) = tokenAndNonce

        val imageRequest = buildImageRequest(request, token, nonce)
        val response = chain.proceed(imageRequest)

        val hasNonceCookie = imageRequest.header("Cookie")?.contains("lt_browser_nonce") == true
        val hasSecCookie = imageRequest.header("Cookie")?.contains("lt_sec_val") == true

        Log.d(
            "LittleTyrantLog",
            "IMAGE status=${response.code} host=${imageRequest.url.host} contentType=${response.header("Content-Type")} hasNonceCookie=$hasNonceCookie hasSecCookie=$hasSecCookie",
        )

        if (!response.isSuccessful) {
            return response
        }

        val body = response.body.bytes()
        if (body.isEmpty()) {
            return response
        }

        val key = getKey(token)
        val decrypted = decode(body, key)

        val mediaType = detectMediaType(decrypted) ?: response.body.contentType() ?: "image/webp".toMediaType()
        val responseBody = decrypted.toResponseBody(mediaType)

        return response.newBuilder()
            .body(responseBody)
            .build()
    }

    private fun buildImageRequest(request: Request, token: String, nonce: String?): Request {
        val url = request.url.newBuilder()
            .fragment(null)
            .build()

        val existingCookie = request.header("Cookie")
        val newCookieHeader = buildCookieHeader(existingCookie, token, nonce)

        return request.newBuilder()
            .url(url)
            .header("Cookie", newCookieHeader)
            .header("X-Reader-Sec", "tiraninha-web")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Dest", "empty")
            .header("Sec-Fetch-Site", "same-origin")
            .build()
    }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        private val TOKEN_REGEX = """"token"\s*:\s*"([^"]+)"""".toRegex()
        private val NONCE_REGEX = """"nonce"\s*:\s*"([^"]+)"""".toRegex()

        internal fun parseTokenAndNonce(fragment: String): Pair<String, String?>? {
            return runCatching {
                val jsonObj = json.decodeFromString<JsonObject>(fragment)
                val token = jsonObj["token"]?.jsonPrimitive?.content ?: return null
                val nonce = jsonObj["nonce"]?.jsonPrimitive?.contentOrNull
                Pair(token, nonce)
            }.recoverCatching {
                val token = TOKEN_REGEX.find(fragment)?.groupValues?.get(1) ?: return null
                val nonce = NONCE_REGEX.find(fragment)?.groupValues?.get(1)
                Pair(token, nonce)
            }.getOrNull()
        }

        internal fun buildCookieHeader(existingCookie: String?, token: String, nonce: String?): String {
            val parts = mutableListOf<String>()
            if (!existingCookie.isNullOrBlank()) {
                parts.add(existingCookie)
            }
            if (!nonce.isNullOrBlank() && !parts.any { it.contains("lt_browser_nonce") }) {
                parts.add("lt_browser_nonce=$nonce")
            }
            if (!parts.any { it.contains("lt_sec_val") }) {
                parts.add("lt_sec_val=$token")
            }
            return parts.joinToString("; ")
        }

        internal fun getKey(token: String): String = token.split(".").getOrNull(1)?.takeIf { it.length >= 20 }?.substring(4, 20)
            ?: token.split(".").lastOrNull()?.takeIf { it.length >= 20 }?.substring(4, 20)
            ?: ""

        internal fun decode(buf: ByteArray, key: String): ByteArray {
            if (key.isEmpty()) return buf
            val v = buf.copyOf()
            val xLen = minOf(1024, v.size)

            for (i in 0 until xLen) {
                v[i] = (v[i].toInt() xor key[i % key.length].code).toByte()
            }

            return v
        }

        internal fun detectMediaType(data: ByteArray) = when {
            data.size >= 3 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() && data[2] == 0xFF.toByte() ->
                "image/jpeg".toMediaType()
            data.size >= 8 && data[0] == 0x89.toByte() && data[1] == 0x50.toByte() && data[2] == 0x4E.toByte() && data[3] == 0x47.toByte() ->
                "image/png".toMediaType()
            data.size >= 12 && data[0] == 'R'.code.toByte() && data[1] == 'I'.code.toByte() && data[2] == 'F'.code.toByte() && data[3] == 'F'.code.toByte() &&
                data[8] == 'W'.code.toByte() && data[9] == 'E'.code.toByte() && data[10] == 'B'.code.toByte() && data[11] == 'P'.code.toByte() ->
                "image/webp".toMediaType()
            data.size >= 6 && data[0] == 'G'.code.toByte() && data[1] == 'I'.code.toByte() && data[2] == 'F'.code.toByte() ->
                "image/gif".toMediaType()
            else -> null
        }
    }
}
