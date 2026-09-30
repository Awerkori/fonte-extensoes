package eu.kanade.tachiyomi.extension.pt.xxxyaoi

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import java.util.Base64

class VhashReaderTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun setup() {
            Injekt.addSingleton<Json>(Json)
        }

        fun encodeVhash(url: String): String {
            val b64 = Base64.getEncoder().encodeToString(url.toByteArray(Charsets.UTF_8))
            val hex = b64.toByteArray(Charsets.US_ASCII).joinToString("") { "%02x".format(it) }
            val half = hex.length / 2
            return hex.substring(half) + hex.substring(0, half)
        }
    }

    private val sampleUrls = listOf(
        "https://3xyaoi.com/wp-content/uploads/WP-manga/data/manga_660f562759b80/43c424c428a91d2f40aa474bef6c4abe/1.jpg",
        "https://3xyaoi.com/wp-content/uploads/WP-manga/data/manga_660f562759b80/43c424c428a91d2f40aa474bef6c4abe/2.jpg",
    )

    @Test
    fun decodeUrlDecodesReversedHalvesHexAndBase64() {
        val original = "https://3xyaoi.com/wp-content/uploads/chapter/01.jpg"
        val vhash = encodeVhash(original)
        assertEquals(original, VhashReader.decodeUrl(vhash))
    }

    @Test
    fun decodeUrlRejectsInvalidHexOrLength() {
        assertNull(VhashReader.decodeUrl(""))
        assertNull(VhashReader.decodeUrl("123"))
        assertNull(VhashReader.decodeUrl("zzzzzzzz"))
        assertNull(VhashReader.decodeUrl("1234567"))
    }

    @Test
    fun presentReturnsTrueOnlyWhenDecodableImagesExist() {
        val validHtml = "<div class='reading-content'><img data-vhash='${encodeVhash(sampleUrls[0])}'></div>"
        assertTrue(VhashReader.present(Jsoup.parse(validHtml, "https://3xyaoi.com/bl/work/capitulo-1/")))

        val invalidHtml = "<div class='reading-content'><img data-vhash='abcdef'></div>"
        assertFalse(VhashReader.present(Jsoup.parse(invalidHtml, "https://3xyaoi.com/bl/work/capitulo-1/")))

        val emptyHtml = "<div class='reading-content'><img src='/logo.png'></div>"
        assertFalse(VhashReader.present(Jsoup.parse(emptyHtml, "https://3xyaoi.com/bl/work/capitulo-1/")))
    }

    @Test
    fun extractsPagesFromLiveFixture() {
        val html = javaClass.getResource("/live-vhash-reader.html")?.readText() ?: error("Missing fixture live-vhash-reader.html")
        val document = Jsoup.parse(html, "https://3xyaoi.com/bl/dreaming-of-the-dokkaebi/capitulo-78/")
        assertTrue(VhashReader.present(document))
        val pages = VhashReader.extract(document)
        assertEquals(2, pages.size)
        assertEquals(sampleUrls[0], pages[0])
        assertEquals(sampleUrls[1], pages[1])
    }

    @Test
    fun readerLoadDoesNotFetchScriptsWhenVhashPresent() = runBlocking {
        val html = "<div class='reading-content'>" + sampleUrls.joinToString("") {
            "<img class='wp-manga-chapter-img' data-vhash='${encodeVhash(it)}'>"
        } + "</div><script src='/external.js'></script>"
        val document = Jsoup.parse(html, "https://3xyaoi.com/bl/work/capitulo-1/")
        val result = Reader.load(document) {
            error("Reader must not fetch external scripts when data-vhash is present")
        }
        assertEquals(sampleUrls, result)
    }
}
