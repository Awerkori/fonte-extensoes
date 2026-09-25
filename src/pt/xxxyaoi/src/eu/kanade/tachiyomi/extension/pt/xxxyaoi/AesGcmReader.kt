package eu.kanade.tachiyomi.extension.pt.xxxyaoi

import org.jsoup.nodes.Document
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Reader used by recent 3xYaoi chapters. The site decrypts an HTML fragment in a
 * closed Shadow DOM with WebCrypto; class and data-attribute names are not stable.
 */
internal object AesGcmReader {
    private val selector = Regex("""document\.querySelector\s*\(\s*["']([^"']+)["']\s*\)""")
    private val dataset = Regex("""\bdataset\.([A-Za-z][A-Za-z0-9_]*)""")
    private val decryptCall = Regex(
        """(?s)\b[A-Za-z_$][A-Za-z0-9_$]*\s*\(\s*\w+\.dataset\.([A-Za-z][A-Za-z0-9_]*)\s*,\s*\w+\.dataset\.([A-Za-z][A-Za-z0-9_]*)\s*,\s*\w+\.dataset\.([A-Za-z][A-Za-z0-9_]*)\s*,\s*\w+\.dataset\.([A-Za-z][A-Za-z0-9_]*)\s*\)""",
    )
    private val salt = Regex("""salt\s*:\s*new\s+TextEncoder\s*\(\s*\)\.encode\s*\(\s*["']([^"']+)["']\s*\)""")
    private val iterations = Regex("""iterations\s*:\s*(\d+)""")

    private fun scripts(document: Document) = document.select("script:not([src])").map { it.data() }.filter {
        it.contains("crypto.subtle.deriveKey") && it.contains("crypto.subtle.decrypt") &&
            it.contains("PBKDF2") && it.contains("AES-GCM")
    }.filter { source ->
        // A shared novel script is not a chapter reader unless its container exists.
        selector.findAll(source).any { match ->
            runCatching { document.selectFirst(match.groupValues[1]) != null }.getOrDefault(false)
        }
    }

    fun present(document: Document) = scripts(document).isNotEmpty()

    fun extract(document: Document): List<String> {
        val source = scripts(document).distinct().singleOrNull()
            ?: throw Reader.PagesNotFound("aes-reader-missing-or-ambiguous")
        val target = selector.findAll(source).map { it.groupValues[1] }.distinct().singleOrNull()
            ?: throw Reader.PagesNotFound("aes-container-missing-or-ambiguous")
        val container = runCatching { document.select(target).singleOrNull() }.getOrNull()
            ?: throw Reader.PagesNotFound("aes-container-missing")
        val fields = decryptCall.findAll(source).map { match -> match.groupValues.drop(1) }.distinct().singleOrNull()
            ?: dataset.findAll(source).map { it.groupValues[1] }.distinct().toList().takeIf { it.size == 4 }
            ?: throw Reader.PagesNotFound("aes-fields-missing-or-ambiguous")
        val names = fields.map(::dataName)
        if (names.any { !container.hasAttr(it) }) throw Reader.PagesNotFound("aes-fields-missing")
        val saltValue = salt.find(source)?.groupValues?.get(1) ?: throw Reader.PagesNotFound("aes-salt-missing")
        val rounds = iterations.find(source)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..1_000_000 }
            ?: throw Reader.PagesNotFound("aes-iterations-invalid")

        val ciphertext = decode(container.attr(names[0])) ?: throw Reader.PagesNotFound("aes-ciphertext-invalid")
        val iv = decode(container.attr(names[1]))?.takeIf { it.size == 12 } ?: throw Reader.PagesNotFound("aes-iv-invalid")
        val tag = decode(container.attr(names[2]))?.takeIf { it.size == 16 } ?: throw Reader.PagesNotFound("aes-tag-invalid")
        val html = decrypt(ciphertext, iv, tag, container.attr(names[3]), saltValue, rounds)
            ?: throw Reader.PagesNotFound("aes-payload-invalid")
        return Reader.decryptedPages(html, document)
    }

    private fun decrypt(ciphertext: ByteArray, iv: ByteArray, tag: ByteArray, token: String, salt: String, rounds: Int): String? = runCatching {
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(token.toCharArray(), salt.toByteArray(Charsets.UTF_8), rounds, 256)).encoded
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        }.doFinal(ciphertext + tag).toString(Charsets.UTF_8)
    }.getOrNull()

    private fun decode(value: String): ByteArray? = runCatching {
        Base64.getDecoder().decode(value.filterNot(Char::isWhitespace))
    }.getOrNull()

    private fun dataName(name: String): String = "data-" + name.replace(Regex("[A-Z]")) { "-${it.value.lowercase()}" }
}
