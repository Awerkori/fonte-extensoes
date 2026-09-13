package eu.kanade.tachiyomi.multisrc.aurora

import keiyoushi.utils.toJsonString
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton

class ReaderTest {
    private val chapterUrl = "https://reader.example/manga/series/54.2"
    private val imageUrl = "https://cdn.example/chapter/page?token=a&n=1"
    private val key = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
    private val payload = "AQAAUOQAAQIDBAUGB5yGwMmTg4dR5RnAFpwcv8iftD41ZjPYAiHJOWVBZ_Z0Ouykdb7_C_UhKkC7"

    // Minimal shapes captured from the current reader; URLs and crypto material are synthetic.
    private val flight = """5:["$","$${"L20"}",null,{"pages":[{"id":1,"url":"$payload","width":800,"height":1200,"isDouble":false}],"capId":"54.2"}]
"""

    @Test
    fun currentRsc() {
        val result = extractReader(flight, "text/x-component", chapterUrl)
        assertEquals(listOf(payload), result.urls)
        assertFalse(result.gated)
    }

    @Test
    fun currentHtmlFlightFallback() {
        val html = "<script>self.__next_f.push(${listOf(1.toString(), flight.toJsonString()).joinToString(prefix = "[", postfix = "]")})</script>"
        assertEquals(listOf(payload), extractReader(html, "text/html", chapterUrl).urls)
    }

    @Test
    fun emptyPagesDoNotHideReaderPayload() {
        assertEquals(listOf(payload), extractReader("0:{\"pages\":[]}\n$flight", "text/x-component", chapterUrl).urls)
    }

    @Test
    fun malformedPayloadAndGateAreNotPages() {
        assertTrue(extractReader("5:{\"pages\":[{\"url\":{}}]}\n", "text/x-component", chapterUrl).urls.isEmpty())
        val gate = extractReader("1:I[5441,[],\"ReadingGateScreen\"]\n5:{\"returnTo\":\"/manga/series/54.2\"}\n", "text/x-component", chapterUrl)
        assertTrue(gate.gated)
        assertTrue(gate.urls.isEmpty())
    }

    @Test
    fun escapedRelativeAndExtensionlessUrls() {
        assertEquals(imageUrl, normalizePageUrl("https:\\/\\/cdn.example/chapter/page?token=a\\u0026n=1", chapterUrl))
        assertEquals(imageUrl, normalizePageUrl("//cdn.example/chapter/page?token=a&amp;n=1", chapterUrl))
        assertEquals("https://reader.example/pages/1?token=x", normalizePageUrl("/pages/1?token=x", chapterUrl))
        assertEquals("https://reader.example/manga/series/page", normalizePageUrl("page", chapterUrl))
        assertNull(normalizePageUrl("data:image/png;base64,AA", chapterUrl))
        assertNull(normalizePageUrl("/manga/series/cover/image.webp", chapterUrl))
        assertNull(normalizePageUrl("/loading.gif", chapterUrl))
        assertNull(normalizePageUrl("", chapterUrl))
    }

    @Test
    fun knownCryptoVectorAndOrderedDeduplication() = runBlocking {
        var calls = 0
        val urls = decodePages(listOf(payload, "/pages/2", payload, ""), chapterUrl) {
            calls++
            assertEquals(1 to 20708L, it)
            key
        }
        assertEquals(listOf(imageUrl, "https://reader.example/pages/2"), urls)
        assertEquals(1, calls)
        assertEquals(imageUrl, decrypt(payload, key))
    }

    @Test
    fun keysAreScopedByParametersAndChapter() = runBlocking {
        val other = "Av____8AAQIDBAUGB_Y-7qLHJAOdCveehZmix0x5nhv3hdGaVxSOfxJw8UTRN2DPz5-hvC9rlBQs"
        val calls = mutableListOf<Pair<Int, Long>>()
        val getKey: suspend (Pair<Int, Long>) -> String = {
            calls += it
            if (it.first == 1) key else "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI="
        }
        assertEquals(listOf(imageUrl), decodePages(listOf(payload, other), chapterUrl, getKey))
        decodePages(listOf(payload), chapterUrl, getKey)
        assertEquals(listOf(1 to 20708L, 2 to 4294967295L, 1 to 20708L), calls)
    }

    @Test
    fun invalidCipherFailsWithoutPoisoningNextChapter() {
        assertThrows(IllegalArgumentException::class.java) { getParams("invalid!") }
        assertThrows(IllegalArgumentException::class.java) { getParams("AQ==") }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { decodePages(listOf(payload), chapterUrl) { "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=" } }
        }
        assertEquals(listOf(imageUrl), runBlocking { decodePages(listOf(payload), chapterUrl) { key } })
    }

    @Test
    fun chapterGateOnlyUsesTheSourceOriginAndTemporaryToken() {
        val token = "00000000-0000-4000-8000-000000000000"
        val gate = GateData(token, "/manga/series/54.2")
        assertEquals("https://reader.example/gate/callback?token=$token", gate.callback(chapterUrl).toString())
        assertThrows(IllegalArgumentException::class.java) { gate.callback("https://reader.example/manga/other/1") }
        assertThrows(IllegalArgumentException::class.java) {
            GateData("eyJ.header.signature", "/manga/series/54.2").callback(chapterUrl)
        }
    }

    @Test
    fun zeroPagesDiagnosticContainsNoToken() {
        val error = assertThrows(IllegalStateException::class.java) {
            readerFailure("Fixture", "$chapterUrl?token=secret", 200, linkedMapOf("rsc" to 0, "html" to 0))
        }
        assertEquals("Aurora reader: Fixture $chapterUrl status=200 rsc=0, html=0", error.message)
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun setup() {
            Injekt.addSingleton(Json { ignoreUnknownKeys = true })
        }
    }
}
