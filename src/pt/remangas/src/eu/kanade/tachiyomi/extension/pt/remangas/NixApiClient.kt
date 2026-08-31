package eu.kanade.tachiyomi.extension.pt.remangas

import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.security.MessageDigest
import java.util.Base64

class NixApiClient(
    private val signerClient: OkHttpClient,
    private val baseUrl: String,
    private val sourceHeaders: Headers,
) : Interceptor {
    private val signerLock = Any()

    @Volatile
    private var cachedSigner: Signer? = null

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.requiresSignature()) return chain.proceed(request)

        val signer = getSigner()
        val response = chain.proceed(request.signedWith(signer))
        if (response.code != 401) return response

        response.close()
        return chain.proceed(request.signedWith(refreshSigner(signer)))
    }

    private fun getSigner(forceRefresh: Boolean = false): Signer = synchronized(signerLock) {
        if (!forceRefresh) cachedSigner?.let { return@synchronized it }

        val url = buildString {
            append(baseUrl)
            append(SIGNER_PATH)
            if (forceRefresh) append("?v=${System.currentTimeMillis()}")
        }
        val request = GET(
            url,
            sourceHeaders.newBuilder()
                .set("Accept", "*/*")
                .set("Referer", "$baseUrl/home")
                .set("Sec-Fetch-Dest", "script")
                .set("Sec-Fetch-Mode", "cors")
                .set("Sec-Fetch-Site", "same-origin")
                .build(),
        )
        val script = signerClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Não foi possível carregar a assinatura pública (${response.code})." }
            response.body.string()
        }
        parseSigner(script).also { cachedSigner = it }
    }

    private fun refreshSigner(staleSigner: Signer): Signer = synchronized(signerLock) {
        cachedSigner?.takeIf { it !== staleSigner } ?: run {
            cachedSigner = null
            getSigner(forceRefresh = true)
        }
    }

    private fun parseSigner(script: String): Signer {
        val rawValues = VALUES_REGEX.find(script)?.groupValues?.get(1)
            ?: error("Formato do signer público não reconhecido.")
        val values = STRING_REGEX.findAll(rawValues).map { it.groupValues[1] }.toList()
        check(values.size >= 5) { "Signer público incompleto." }
        return Signer(
            slot = values.first().reversed(),
            key = values.subList(1, 4).joinToString("") { it.reversed() },
            token = values.drop(4).joinToString("") { it.reversed() },
        )
    }

    private fun Request.requiresSignature(): Boolean = url.host == baseUrl.substringAfter("://") && (
        url.encodedPath == "$API_PATH/comics" ||
            url.encodedPath.startsWith("$API_PATH/comics/") ||
            url.encodedPath == "$API_PATH/chapters" ||
            url.encodedPath.startsWith("$API_PATH/chapters/")
        )

    private fun Request.signedWith(signer: Signer): Request {
        val method = method.uppercase()
        val path = url.encodedPath
        val input = listOf(method, path, SITE_ID, signer.slot, signer.token, signer.key).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val signature = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        return newBuilder()
            .header("Accept", "application/json, text/plain, */*")
            .header("Origin", baseUrl)
            .header("Referer", "$baseUrl/home")
            .header("Sec-Fetch-Dest", "empty")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Site", "same-origin")
            .header("X-Site-ID", SITE_ID)
            .header("X-Web-Slot", signer.slot)
            .header("X-Web-Token", signer.token)
            .header("X-Web-Signature", signature)
            .build()
    }

    private class Signer(
        val slot: String,
        val key: String,
        val token: String,
    )

    companion object {
        private const val API_PATH = "/api/v1"
        private const val SIGNER_PATH = "/_nix/signer.js"
        private const val SITE_ID = "00000000-0000-0000-0000-000000000003"
        private val VALUES_REGEX = Regex("""const z=\[(.*?)]""")
        private val STRING_REGEX = Regex(""""([^"\\]+)"""")
    }
}
