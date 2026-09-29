package eu.kanade.tachiyomi.extension.pt.portalyaoi

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PortalYaoiTest {

    @Test
    fun test1_decodeContentNode_withDefaultKey() {
        val rawUrl = "https://portalyaoi.com/wp-content/uploads/ch102/01.jpg"
        val enc = "QNBDLdwAWQgACwgUesUVHgVWWEUSDpwBAhADXRhEVo0XfVAHbxFWd4VBFpUWE1hGMc0QAVBU"
        val decrypted = PortalYaoi.decodeContentNode(enc)
        assertEquals(rawUrl, decrypted)
    }

    @Test
    fun test2_decodeContentNode_withExtractedKey() {
        val customKey = "custom_test_key_1234567890abcdef"
        val rawUrl = "https://portalyaoi.com/wp-content/uploads/ch1/sample.png"

        // Encrypt with custom key
        val keyBytes = customKey.toByteArray(Charsets.UTF_8)
        val urlBytes = rawUrl.toByteArray(Charsets.UTF_8)
        val xored = ByteArray(urlBytes.size) { i ->
            (urlBytes[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
        }
        val encoded = Base64.getEncoder().encodeToString(xored).reversed()

        val html = """
            <html>
            <head>
                <script id="lbl-shield-decrypt">
                    var _k = "$customKey";
                </script>
            </head>
            <body></body>
            </html>
        """.trimIndent()
        val doc = Jsoup.parse(html)
        val extractedKey = PortalYaoi.extractKey(doc)
        assertEquals(customKey, extractedKey)

        val decrypted = PortalYaoi.decodeContentNode(encoded, extractedKey)
        assertEquals(rawUrl, decrypted)
    }

    @Test
    fun test3_legacyDataObfDecodesNormally() {
        val rawUrl = "https://portalyaoi.com/wp-content/uploads/ch1/02.jpg"
        val encodedObf = Base64.getEncoder().encodeToString(rawUrl.toByteArray()).reversed()
        val decrypted = PortalYaoi.decodePageUrl(encodedObf)
        assertEquals(rawUrl, decrypted)
    }

    @Test
    fun test4_pageListParse_decodesProtectedChapterImages() {
        val enc1 = "QNBDLdwAWQgACwgUesUVHgVWWEUSDpwBAhADXRhEVo0XfVAHbxFWd4VBFpUWE1hGMc0QAVBU"
        val enc2 = "QNBDLRwAWQgACwgUesUVHgVWWEUSDpwBAhADXRhEVo0XfVAHbxFWd4VBFpUWE1hGMc0QAVBU"

        val html = """
            <html>
            <head>
                <script id="lbl-shield-decrypt">
                    var _k = "8a4346524681d2d9322f02ebb94cf4bd7f4f57f1811d326936efc760cf140a88";
                </script>
            </head>
            <body>
                <div class="reading-content">
                    <div class="page-break">
                        <img class="wp-manga-chapter-img" src="data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7" data-content-node="$enc1" />
                    </div>
                    <div class="page-break">
                        <img class="wp-manga-chapter-img" src="data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7" data-content-node="$enc2" />
                    </div>
                </div>
            </body>
            </html>
        """.trimIndent()
        val doc = Jsoup.parse(html, "https://portalyaoi.com/manga/test/capitulo-102/")
        val pages = PortalYaoi.parsePagesFromDocument(doc)

        assertEquals(2, pages.size)
        assertEquals("https://portalyaoi.com/wp-content/uploads/ch102/01.jpg", pages[0].imageUrl)
        assertEquals("https://portalyaoi.com/wp-content/uploads/ch102/02.jpg", pages[1].imageUrl)
    }

    @Test
    fun test5_extractKey_fallbackWhenMissing() {
        val doc = Jsoup.parse("<html><body><div>No script here</div></body></html>")
        val key = PortalYaoi.extractKey(doc)
        assertEquals(PortalYaoi.DEFAULT_NODE_KEY, key)
    }

    @Test
    fun test6_normalImagesParsedCorrectly() {
        val html = """
            <html>
            <body>
                <div class="reading-content">
                    <div class="page-break">
                        <img class="wp-manga-chapter-img" src="https://portalyaoi.com/wp-content/uploads/ch1/01.jpg" />
                    </div>
                    <div class="page-break">
                        <img class="wp-manga-chapter-img" data-src="https://portalyaoi.com/wp-content/uploads/ch1/02.jpg" />
                    </div>
                </div>
            </body>
            </html>
        """.trimIndent()
        val doc = Jsoup.parse(html, "https://portalyaoi.com/manga/test/capitulo-1/")
        val pages = PortalYaoi.parsePagesFromDocument(doc)

        assertEquals(2, pages.size)
        assertEquals("https://portalyaoi.com/wp-content/uploads/ch1/01.jpg", pages[0].imageUrl)
        assertEquals("https://portalyaoi.com/wp-content/uploads/ch1/02.jpg", pages[1].imageUrl)
    }

    @Test
    fun test7_noRegressionInDetailsAndChapters() {
        val detailsHtml = """
            <div class="post-title">
                <h1>Jinx</h1>
            </div>
            <div class="author-content">
                <a href="/autor/mingwa/">Mingwa</a>
            </div>
            <div class="artist-content">
                <a href="/artista/mingwa/">Mingwa</a>
            </div>
            <div class="description-summary">
                <div class="summary__content">
                    <p>Sinopse da obra Jinx.</p>
                </div>
            </div>
            <div class="genres-content">
                <a href="/manga-genre/yaoi/">Yaoi</a>
                <a href="/manga-genre/drama/">Drama</a>
            </div>
            <div class="summary_image">
                <img src="https://portalyaoi.com/wp-content/uploads/cover.jpg" />
            </div>
            <ul class="main version-chap">
                <li class="wp-manga-chapter    ">
                    <a href="https://portalyaoi.com/manga/jinx/capitulo-102/">Capítulo 102</a>
                    <span class="chapter-release-date"><i>02/06/2026</i></span>
                </li>
                <li class="wp-manga-chapter    ">
                    <a href="https://portalyaoi.com/manga/jinx/capitulo-101/">Capítulo 101</a>
                    <span class="chapter-release-date"><i>22/05/2026</i></span>
                </li>
            </ul>
        """.trimIndent()
        val doc = Jsoup.parse(detailsHtml, "https://portalyaoi.com/manga/jinx/")
        val manga = PortalYaoi.parseDetailsFromDocument(doc)
        assertEquals("Jinx", manga.title)
        assertEquals("Mingwa", manga.author)
        assertEquals("Mingwa", manga.artist)
        assertEquals("Sinopse da obra Jinx.", manga.description)
        assertNotNull(manga.genre)
        assertTrue(manga.genre!!.contains("Yaoi"))
        assertTrue(manga.genre!!.contains("Drama"))
        assertEquals("https://portalyaoi.com/wp-content/uploads/cover.jpg", manga.thumbnail_url)

        val chapters = PortalYaoi.parseChapterListFromDocument(doc)
        assertEquals(2, chapters.size)
        assertEquals("capitulo-102", chapters[0].first)
        assertEquals("Capítulo 102", chapters[0].second)
        assertEquals("capitulo-101", chapters[1].first)
        assertEquals("Capítulo 101", chapters[1].second)
    }
}
