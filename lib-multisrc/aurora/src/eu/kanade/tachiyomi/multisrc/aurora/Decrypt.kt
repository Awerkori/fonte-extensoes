package eu.kanade.tachiyomi.multisrc.aurora

import okio.ByteString.Companion.decodeBase64
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal suspend fun decodePages(
    values: List<String>,
    chapterUrl: String,
    getKey: suspend (Pair<Int, Long>) -> String,
): List<String> {
    // The site's key cache is indexed by (v, e); never retain it across chapter requests.
    val keys = mutableMapOf<Pair<Int, Long>, String>()
    return values.mapNotNull { encoded ->
        if (encoded.isBlank() || encoded.startsWith("data:", true)) return@mapNotNull null
        val decoded = if ('/' in encoded || encoded.startsWith('"') || '\\' in encoded) {
            encoded
        } else {
            val params = getParams(encoded)
            val key = keys.getOrPut(params) { getKey(params) }
            decrypt(encoded, key).also {
                require(it.startsWith("https://") || it.startsWith("http://")) { "Aurora reader: invalid decrypted URL" }
            }
        }
        normalizePageUrl(decoded, chapterUrl)
    }.distinct()
}

fun decrypt(payload: String, key: String): String {
    val bytes = payloadBytes(payload)

    val baseData = bytes.copyOfRange(5, 13)

    val cipherText = bytes.copyOfRange(13, bytes.size)
    val totalLength = cipherText.size

    val chapterKey = requireNotNull(key.decodeBase64()) { "Aurora reader: invalid key" }.toByteArray()
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(chapterKey, "HmacSHA256"))

    val keystream = ByteArray(totalLength)
    var o = 0
    var i = 0

    while (o < totalLength) {
        val block = ByteArray(baseData.size + 1)
        System.arraycopy(baseData, 0, block, 0, baseData.size)
        block[baseData.size] = (i and 0xFF).toByte()

        val hashBlock = mac.doFinal(block)

        val copyLength = minOf(hashBlock.size, totalLength - o)
        System.arraycopy(hashBlock, 0, keystream, o, copyLength)

        o += copyLength
        i++
    }

    val decryptedBytes = ByteArray(totalLength)
    for (idx in cipherText.indices) {
        decryptedBytes[idx] = (cipherText[idx].toInt() xor (keystream[idx].toInt() and 0xFF)).toByte()
    }

    return String(decryptedBytes, Charsets.UTF_8)
}

fun getParams(value: String): Pair<Int, Long> {
    val byteOffset = 1
    val byteArray = payloadBytes(value)
    val buffer = ByteBuffer.wrap(byteArray)
    buffer.order(ByteOrder.BIG_ENDIAN)
    return (byteArray.first().toInt() and 0xFF) to (buffer.getInt(byteOffset).toLong() and 0xFFFFFFFFL)
}

private fun payloadBytes(value: String): ByteArray {
    val bytes = requireNotNull(value.decodeBase64()) { "Aurora reader: invalid payload" }.toByteArray()
    require(bytes.size > 13) { "Aurora reader: truncated payload" }
    return bytes
}
