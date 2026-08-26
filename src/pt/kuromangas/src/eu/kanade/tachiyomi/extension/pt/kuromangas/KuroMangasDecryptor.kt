package eu.kanade.tachiyomi.extension.pt.kuromangas

import android.util.Base64
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.parseAs
import keiyoushi.utils.readIntBigEndian
import keiyoushi.utils.readIntLittleEndian
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.brotli.Brotli
import okhttp3.zstd.Zstd
import okio.Buffer
import okio.buffer
import java.io.IOException
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneOffset
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

const val HOSTNAME_PART = "kuromangas.com::v2"
const val ANTIBOT = "x9_4v2_b"
const val DEFAULT_ENC_KEY = "i67ato8l6sai74jyIHfE2oMmieshoforanuYTusF4jKdqEwhUEft9dsadcxzde3"
const val NONCE_HEADER = "X-Client-Token"
const val NONCE_PATH = "/api/auth/check"
private const val HMAC_ALGORITHM = "HmacSHA256"
private const val LOGIN_EXPIRED_MESSAGE = "Sessão recusada pelo site. Refaça o login no WebView ou revise email e senha nas configurações."

private val encKeyRegex = Regex("""ENCRYPTION_KEY\s*[:=]\s*["']([^"']+)["']""")

private data class BodyInfo(
    val bodyClass: String,
    val hasVSecure: Boolean,
)

class KuroMangasDecryptor(
    val baseUrl: String,
    val client: OkHttpClient,
    val headers: Headers,
    val relogin: () -> Boolean,
) {
    private var viteApiEncKey: String? = DEFAULT_ENC_KEY

    private var sessionSecret: String? = null

    fun vSecureInterceptor() = Interceptor { chain ->

        fun newRequest(): Request {
            val request = chain.request()
            if (request.url.encodedPath.endsWith(NONCE_PATH)) return request

            val secret = sessionSecret ?: fetchSecret()?.also { sessionSecret = it } ?: return request
            return request.newBuilder()
                .header(NONCE_HEADER, signature(secret, request.method, request.url.encodedPath))
                .build()
        }

        fun execute(request: Request, retried: Boolean): Response {
            val response = chain.proceed(request)

            if (response.code == 401 || response.code == 403) {
                response.close()
                if (retried) throw IOException(LOGIN_EXPIRED_MESSAGE)
                reloadCredentials()
                return execute(newRequest(), true)
            }

            if (isDirectBinaryResponse(request, response)) {
                return response
            }

            fun retryAfterDecryptFailure(cause: Throwable): Response {
                if (retried) throw IOException("Failed to decrypt", cause)
                reloadCredentials()
                return execute(newRequest(), true)
            }

            val responseBuilder = response.newBuilder()
            val contentType = response.body.contentType()
            val bodyBytes = try {
                response.body.bytes()
            } catch (e: Exception) {
                response.close()
                throw IOException("Failed to read response", e)
            }
            response.close()

            val rawBodyInfo = classifyBody(bodyBytes)
            val contentEncoding = response.header("Content-Encoding").orEmpty()
            val canDecompress = rawBodyInfo.bodyClass !in setOf(
                "EMPTY",
                "JSON_ARRAY",
                "JSON_OBJECT_PLAIN",
                "JSON_OBJECT_V_SECURE",
                "HTML",
            )
            val compression = when {
                canDecompress && contentEncoding.contains("br", ignoreCase = true) -> "br"
                canDecompress && contentEncoding.contains("zstd", ignoreCase = true) -> "zstd"
                else -> ""
            }
            val processedBytes = if (compression.isNotEmpty()) {
                try {
                    when (compression) {
                        "br" -> decompressBrotli(bodyBytes)
                        else -> decompressZstd(bodyBytes)
                    }
                } catch (e: Exception) {
                    throw IOException("Failed to decompress response", e)
                }
            } else {
                bodyBytes
            }
            val bodyInfo = classifyBody(processedBytes)
            val dataKey = response.headers["x-kuro-datakey"]

            if (bodyInfo.bodyClass == "JSON_ARRAY" || bodyInfo.bodyClass == "JSON_OBJECT_PLAIN") {
                return rebuildResponse(responseBuilder, processedBytes, contentType, contentEncoding.isNotEmpty())
            }

            if (dataKey == null) {
                return rebuildResponse(responseBuilder, processedBytes, contentType, contentEncoding.isNotEmpty())
            }

            if (bodyInfo.bodyClass != "JSON_OBJECT_V_SECURE") {
                return retryAfterDecryptFailure(IllegalStateException("unexpected response format"))
            }

            val wrapper = try {
                processedBytes.toString(Charsets.UTF_8).parseAs<JsonElement>()
            } catch (e: Exception) {
                return retryAfterDecryptFailure(e)
            }
            val secureElement = (wrapper as? JsonObject)?.get("_v_secure")
                ?: return retryAfterDecryptFailure(IllegalStateException("missing_v_secure"))

            val vSecure = (secureElement as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
                ?: return retryAfterDecryptFailure(IllegalStateException("invalid secure response"))

            val decrypted = try {
                decrypt(vSecure, dataKey)
            } catch (e: Exception) {
                return retryAfterDecryptFailure(e)
            }

            return rebuildResponse(responseBuilder, decrypted.toByteArray(), contentType, contentEncoding.isNotEmpty())
        }

        execute(newRequest(), false)
    }

    /** The site signs every call with `base64url(HMAC-SHA256(secret, "METHOD|path|epoch")).epoch`. */
    private fun signature(secret: String, method: String, path: String): String {
        val timestamp = System.currentTimeMillis() / 1000
        val mac = Mac.getInstance(HMAC_ALGORITHM).apply {
            init(SecretKeySpec(secret.decodeHex(), HMAC_ALGORITHM))
        }
        val digest = mac.doFinal("${method.uppercase()}|$path|$timestamp".toByteArray())
        val encoded = Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        return "$encoded.$timestamp"
    }

    private fun fetchSecret(): String? = runCatching {
        client.newCall(GET(baseUrl + NONCE_PATH, headers)).execute().parseAs<NonceDto>().secret
    }.getOrNull()

    private fun String.decodeHex(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    fun reloadCredentials() {
        // The secret is only issued to a live session, so a rejected cookie requires a fresh login.
        sessionSecret = fetchSecret() ?: if (relogin()) fetchSecret() else null

        val indexJsUrl = client.newCall(GET(baseUrl)).execute()
            .asJsoup()
            .selectFirst("script[src*=index]")
            ?.absUrl("src") ?: return

        val js = client.newCall(GET(indexJsUrl)).execute().body.string()

        encKeyRegex.find(js)?.groupValues?.get(1)?.let { viteApiEncKey = it }
    }

    // index-*.js: Ik2() + Hk2()
    fun decrypt(vSecure: String, dataKey: String): String {
        val password = derivePassword()
        val encrypted = Base64.decode(vSecure, Base64.DEFAULT)
        if (encrypted.size < 16) {
            throw IllegalArgumentException("encrypted payload too short")
        }
        val salt = encrypted.copyOfRange(8, 16)
        val ciphertext = encrypted.copyOfRange(16, encrypted.size)

        val (key, iv) = evpBytesToKey(password.toByteArray(), salt)
        val plaintext = ciphertext.copyOf()
        Rabbit().apply {
            setup(key, iv)
            crypt(plaintext)
        }

        val jsonStr = String(plaintext, Charsets.UTF_8)

        val wrapper = jsonStr.parseAs<JsonElement>()
        val inner = (wrapper as? JsonObject)?.get(dataKey) ?: wrapper
        return inner.toString()
    }

    private fun classifyBody(body: ByteArray): BodyInfo {
        if (body.isEmpty()) return BodyInfo("EMPTY", false)

        val text = body.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        val json = runCatching { text.parseAs<JsonElement>() }.getOrNull()
        if (json is JsonObject) {
            val hasVSecure = "_v_secure" in json
            return BodyInfo(if (hasVSecure) "JSON_OBJECT_V_SECURE" else "JSON_OBJECT_PLAIN", hasVSecure)
        }
        if (json != null && text.startsWith("[")) {
            return BodyInfo("JSON_ARRAY", false)
        }
        if (text.startsWith("<")) return BodyInfo("HTML", false)
        if (body.any { it == 0.toByte() }) return BodyInfo("BINARY", false)
        return BodyInfo("UNKNOWN", false)
    }

    private fun decompressBrotli(body: ByteArray): ByteArray {
        val source = Brotli.decompress(Buffer().write(body)).buffer()
        return source.use { it.readByteArray() }
    }

    private fun decompressZstd(body: ByteArray): ByteArray {
        val source = Zstd.decompress(Buffer().write(body)).buffer()
        return source.use { it.readByteArray() }
    }

    private fun isDirectBinaryResponse(request: Request, response: Response): Boolean {
        if (response.headers["x-kuro-datakey"] != null) return false

        val contentType = response.body.contentType()?.toString()?.lowercase().orEmpty()
        val path = request.url.encodedPath.lowercase()
        val isCover = "/covers/" in path
        return contentType.startsWith("image/") ||
            (isCover && contentType == "application/octet-stream")
    }

    private fun rebuildResponse(
        builder: Response.Builder,
        body: ByteArray,
        contentType: okhttp3.MediaType?,
        removeContentEncoding: Boolean,
    ): Response = builder
        .removeHeader("Content-Length")
        .apply { if (removeContentEncoding) removeHeader("Content-Encoding") }
        .body(body.toResponseBody(contentType))
        .build()

    fun derivePassword(date: String = LocalDate.now(ZoneOffset.UTC).toString()): String {
        val toHash = "$date$HOSTNAME_PART$ANTIBOT"
        val md5Part = MessageDigest.getInstance("MD5")
            .digest(toHash.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .substring(0, 8)
        return viteApiEncKey + md5Part
    }

    fun evpBytesToKey(password: ByteArray, salt: ByteArray, keyLen: Int = 16, ivLen: Int = 8): Pair<ByteArray, ByteArray> {
        val derived = ByteArray(keyLen + ivLen)
        var derivedPos = 0
        var md5Hash = ByteArray(0)
        val md = MessageDigest.getInstance("MD5")
        while (derivedPos < derived.size) {
            md.reset()
            if (md5Hash.isNotEmpty()) md.update(md5Hash)
            md.update(password)
            md.update(salt)
            md5Hash = md.digest()
            val toCopy = minOf(md5Hash.size, derived.size - derivedPos)
            System.arraycopy(md5Hash, 0, derived, derivedPos, toCopy)
            derivedPos += toCopy
        }
        return Pair(derived.copyOfRange(0, keyLen), derived.copyOfRange(keyLen, keyLen + ivLen))
    }
}

// Rabbit Cipher (crypto-js)
class Rabbit {
    val x = IntArray(8)
    val c = IntArray(8)
    var b = 0

    fun setup(key: ByteArray, iv: ByteArray) {
        val kw = IntArray(4)
        for (i in 0 until 4) {
            kw[i] = key.readIntLittleEndian(i * 4)
        }

        x[0] = kw[0]
        x[1] = (kw[3] shl 16) or ((kw[2] ushr 16) and 0xFFFF)
        x[2] = kw[1]
        x[3] = (kw[0] shl 16) or ((kw[3] ushr 16) and 0xFFFF)
        x[4] = kw[2]
        x[5] = (kw[1] shl 16) or ((kw[0] ushr 16) and 0xFFFF)
        x[6] = kw[3]
        x[7] = (kw[2] shl 16) or ((kw[1] ushr 16) and 0xFFFF)

        c[0] = (kw[2] shl 16) or ((kw[2] ushr 16) and 0xFFFF)
        c[1] = (kw[0] and 0xFFFF0000.toInt()) or (kw[1] and 0xFFFF)
        c[2] = (kw[3] shl 16) or ((kw[3] ushr 16) and 0xFFFF)
        c[3] = (kw[1] and 0xFFFF0000.toInt()) or (kw[2] and 0xFFFF)
        c[4] = (kw[0] shl 16) or ((kw[0] ushr 16) and 0xFFFF)
        c[5] = (kw[2] and 0xFFFF0000.toInt()) or (kw[3] and 0xFFFF)
        c[6] = (kw[1] shl 16) or ((kw[1] ushr 16) and 0xFFFF)
        c[7] = (kw[3] and 0xFFFF0000.toInt()) or (kw[0] and 0xFFFF)

        b = 0

        repeat(4) { nextState() }

        for (i in 0 until 8) {
            c[i] = c[i] xor x[(i + 4) and 7]
        }

        if (iv.isNotEmpty()) {
            val iv0 = iv.readIntBigEndian(0)
            val iv1 = iv.readIntBigEndian(4)

            fun swap(w: Int) = ((w and 0xFF) shl 24) or
                ((w and 0xFF00) shl 8) or
                ((w and 0xFF0000) ushr 8) or
                ((w ushr 24) and 0xFF)

            val i0 = swap(iv0)
            val i2 = swap(iv1)
            val i1 = (i0 ushr 16) or (i2 and 0xFFFF0000.toInt())
            val i3 = ((i2 shl 16) or (i0 and 0x0000FFFF))

            c[0] = c[0] xor i0
            c[1] = c[1] xor i1
            c[2] = c[2] xor i2
            c[3] = c[3] xor i3
            c[4] = c[4] xor i0
            c[5] = c[5] xor i1
            c[6] = c[6] xor i2
            c[7] = c[7] xor i3

            repeat(4) { nextState() }
        }
    }

    fun crypt(data: ByteArray) {
        val wordsSize = (data.size + 3) / 4
        val words = IntArray(wordsSize)

        for (i in 0 until wordsSize) {
            var word = 0
            for (j in 0 until 4) {
                val byteIdx = i * 4 + j
                if (byteIdx < data.size) {
                    word = word or ((data[byteIdx].toInt() and 0xFF) shl (j * 8))
                }
            }
            words[i] = word
        }

        var idx = 0
        while (idx < words.size) {
            nextState()

            val (s0, s1, s2, s3) = keystreamBlock()

            if (idx < words.size) words[idx] = words[idx] xor s0
            if (idx + 1 < words.size) words[idx + 1] = words[idx + 1] xor s1
            if (idx + 2 < words.size) words[idx + 2] = words[idx + 2] xor s2
            if (idx + 3 < words.size) words[idx + 3] = words[idx + 3] xor s3

            idx += 4
        }

        for (byteIdx in 0 until data.size) {
            val wordIdx = byteIdx / 4
            val shift = (byteIdx % 4) * 8
            data[byteIdx] = ((words[wordIdx] ushr shift) and 0xFF).toByte()
        }
    }

    fun keystreamBlock(): Quadruple {
        val s0 = (x[0] xor (x[5] ushr 16) xor (x[3] shl 16))
        val s1 = (x[2] xor (x[7] ushr 16) xor (x[5] shl 16))
        val s2 = (x[4] xor (x[1] ushr 16) xor (x[7] shl 16))
        val s3 = (x[6] xor (x[3] ushr 16) xor (x[1] shl 16))
        return Quadruple(s0, s1, s2, s3)
    }

    fun nextState() {
        val cOld = c.copyOf()

        c[0] = c[0] + 0x4D34D34D + b
        c[1] = c[1] + 0xD34D34D3u.toInt() + (if (unsignedLessThan(c[0], cOld[0])) 1 else 0)
        c[2] = c[2] + 0x34D34D34 + (if (unsignedLessThan(c[1], cOld[1])) 1 else 0)
        c[3] = c[3] + 0x4D34D34D + (if (unsignedLessThan(c[2], cOld[2])) 1 else 0)
        c[4] = c[4] + 0xD34D34D3u.toInt() + (if (unsignedLessThan(c[3], cOld[3])) 1 else 0)
        c[5] = c[5] + 0x34D34D34 + (if (unsignedLessThan(c[4], cOld[4])) 1 else 0)
        c[6] = c[6] + 0x4D34D34D + (if (unsignedLessThan(c[5], cOld[5])) 1 else 0)
        c[7] = c[7] + 0xD34D34D3u.toInt() + (if (unsignedLessThan(c[6], cOld[6])) 1 else 0)
        b = if (unsignedLessThan(c[7], cOld[7])) 1 else 0

        val g = IntArray(8)
        for (i in 0 until 8) {
            val gx = x[i] + c[i]
            val ga = gx and 0xFFFF
            val gb = (gx ushr 16) and 0xFFFF
            val gh = ((((ga * ga) ushr 17) + ga * gb) ushr 15) + gb * gb

            val gl = (((gx.toLong() and 0xFFFF0000L)) * gx) + (((gx.toLong() and 0x0000FFFFL)) * gx)
            g[i] = (gh xor (gl and 0xFFFFFFFFL).toInt())
        }

        x[0] = g[0] + ((g[7] shl 16) or (g[7] ushr 16)) + ((g[6] shl 16) or (g[6] ushr 16))
        x[1] = g[1] + ((g[0] shl 8) or (g[0] ushr 24)) + g[7]
        x[2] = g[2] + ((g[1] shl 16) or (g[1] ushr 16)) + ((g[0] shl 16) or (g[0] ushr 16))
        x[3] = g[3] + ((g[2] shl 8) or (g[2] ushr 24)) + g[1]
        x[4] = g[4] + ((g[3] shl 16) or (g[3] ushr 16)) + ((g[2] shl 16) or (g[2] ushr 16))
        x[5] = g[5] + ((g[4] shl 8) or (g[4] ushr 24)) + g[3]
        x[6] = g[6] + ((g[5] shl 16) or (g[5] ushr 16)) + ((g[4] shl 16) or (g[4] ushr 16))
        x[7] = g[7] + ((g[6] shl 8) or (g[6] ushr 24)) + g[5]
    }

    fun unsignedLessThan(a: Int, b: Int) = a.toUInt() < b.toUInt()

    data class Quadruple(val s0: Int, val s1: Int, val s2: Int, val s3: Int)
}
