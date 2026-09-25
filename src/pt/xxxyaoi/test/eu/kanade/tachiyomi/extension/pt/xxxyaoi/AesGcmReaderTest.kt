package eu.kanade.tachiyomi.extension.pt.xxxyaoi

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.BeforeClass
import kotlinx.serialization.json.Json
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class AesGcmReaderTest {
    companion object {
        @BeforeClass @JvmStatic fun setup() { Injekt.addSingleton<Json>(Json) }
    }
    private val pages = listOf("https://cdn.example/001.webp?key=a", "https://cdn.example/002.webp?key=b")

    private fun fixture(
        fields: List<String> = listOf("cipherData", "nonce", "authTag", "chapterToken"),
        payload: String = pages.joinToString("") { "<img src='$it'>" },
    ): String {
        val token = "chapter-token"
        val salt = "reader-salt"
        val iv = ByteArray(12) { (it + 1).toByte() }
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(token.toCharArray(), salt.toByteArray(), 20, 256)).encoded
        val output = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        }.doFinal(payload.toByteArray(Charsets.UTF_8))
        fun attr(name: String, value: String) = "data-${name.replace(Regex("[A-Z]")) { "-${it.value.lowercase()}" }}='$value'"
        return """
            <div class="chapter-box" ${attr(fields[0], Base64.getEncoder().encodeToString(output.copyOfRange(0, output.size - 16)))} ${attr(fields[1], Base64.getEncoder().encodeToString(iv))} ${attr(fields[2], Base64.getEncoder().encodeToString(output.copyOfRange(output.size - 16, output.size)))} ${attr(fields[3], token)}></div>
            <script>
              async function decryptChapter(a, b, c, d) {}
              document.addEventListener("DOMContentLoaded", async function () {
                const box = document.querySelector(".chapter-box");
                const plaintext = await decryptChapter(box.dataset.${fields[0]}, box.dataset.${fields[1]}, box.dataset.${fields[2]}, box.dataset.${fields[3]});
                const key = await crypto.subtle.deriveKey({ name: "PBKDF2", salt: new TextEncoder().encode("$salt"), iterations: 20, hash: "SHA-256" }, x, { name: "AES-GCM", length: 256 }, false, ["decrypt"]);
                await crypto.subtle.decrypt({ name: "AES-GCM", iv: x }, key, x);
              });
            </script>
        """
    }

    @Test fun extractsRenamedFieldsFromDecryptCall() {
        val document = Jsoup.parse(fixture(), "https://3xyaoi.com/bl/work/chapter/")
        assertEquals(pages, AesGcmReader.extract(document))
    }

    @Test fun rejectsTamperedCiphertext() {
        val document = Jsoup.parse(fixture().replace("data-cipher-data='", "data-cipher-data='x"), "https://3xyaoi.com/bl/work/chapter/")
        assertThrows(Reader.PagesNotFound::class.java) { AesGcmReader.extract(document) }
    }

    @Test fun extractsJsonPageList() {
        val payload = pages.joinToString(prefix = "[\"", postfix = "\"]", separator = "\",\"")
        assertEquals(pages, AesGcmReader.extract(Jsoup.parse(fixture(payload = payload), "https://3xyaoi.com/bl/work/chapter/")))
    }

    @Test fun unusedNovelScriptDoesNotOverrideImageReader() {
        val document = Jsoup.parse(fixture(), "https://3xyaoi.com/bl/work/chapter/")
        document.select(".chapter-box").remove()
        document.body().append("<div class='reading-content'>" + pages.joinToString("") { "<img data-xsec='${it.reversed()}' src='data:image/svg+xml,placeholder'>" } + "</div>")
        assertEquals(pages, Reader.extract(document))
    }

}
