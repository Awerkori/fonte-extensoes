package eu.kanade.tachiyomi.extension.pt.littletyrant

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class LittleTyrantTest {

    @Test
    fun test1_archiveCardParsing_withNewX7qEntry() {
        val html = """
            <div class="col-6 col-md-2 archive-item-card">
                <div class="manga-hover-card" id="x7q-entry-1485">
                    <a href="https://tiraninha.world/manga/limite-absoluto/" title="Limite absoluto">
                        <div class="poster-wrapper">
                            <img src="https://tiraninha.world/wp-content/uploads/2024/05/capa.webp" />
                        </div>
                    </a>
                    <div class="card-info">
                        <div class="x7q-title">
                            <a href="https://tiraninha.world/manga/limite-absoluto/">Limite absoluto</a>
                        </div>
                    </div>
                </div>
            </div>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val card = doc.selectFirst(".card-hunters, .manga-hover-card, [id*=-entry-]")
        assertNotNull(card)

        val id = card!!.attr("data-post-id").takeIf(String::isNotBlank)
            ?: card.id().substringAfter("-entry-", "").takeIf(String::isNotBlank)
        assertEquals("1485", id)

        val title = card.selectFirst(".x7q-title a, .card-title a, .post-title a, h4 a, h3")?.text()
        assertEquals("Limite absoluto", title)

        val url = card.selectFirst(".x7q-title a, .card-title a, .post-title a, a[href*='/manga/']")?.attr("href")
        assertEquals("https://tiraninha.world/manga/limite-absoluto/", url)
    }

    @Test
    fun test2_archiveCardParsing_withLegacyMangaEntry() {
        val html = """
            <div class="col-6 col-md-2" id="manga-entry-999">
                <div class="card-title">
                    <a href="https://tiraninha.world/manga/obra-legada/">Obra Legada</a>
                </div>
                <h3>Obra Legada</h3>
            </div>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val card = doc.selectFirst(".card-hunters, .manga-hover-card, [id*=-entry-]")
        assertNotNull(card)

        val id = card!!.id().substringAfter("-entry-", "")
        assertEquals("999", id)

        val title = card.selectFirst(".x7q-title a, .card-title a, .post-title a, h4 a, h3, a[title]")?.text()
        assertEquals("Obra Legada", title)
    }

    @Test
    fun test3_detailsParsing_withNewX7qLayout() {
        val html = """
            <div class="post-617 wp-manga">
                <div class="metadata-table">
                    <div class="x7q-row">
                        <span class="attr-label">AUTOR</span>
                        <span class="attr-value">Moon Si Hyun</span>
                    </div>
                    <div class="x7q-row">
                        <span class="attr-label">ARTISTA</span>
                        <span class="attr-value">HD·ZD</span>
                    </div>
                    <div class="x7q-row">
                        <span class="attr-label">STATUS</span>
                        <span class="attr-value">On-Going</span>
                    </div>
                </div>
                <div class="x7q-summary">
                    <div class="summary-text-scroll">
                        Depois de uma vida trágica, ela renasceu.
                    </div>
                </div>
                <div class="genres-content">
                    <a href="https://tiraninha.world/manga-genre/fantasia/">Fantasia</a>
                    <a href="https://tiraninha.world/manga-genre/romance/">Romance</a>
                </div>
            </div>
        """.trimIndent()

        val doc = Jsoup.parse(html)

        val author = doc.select(".x7q-row:has(.attr-label:contains(AUTOR)) .attr-value").text()
        val artist = doc.select(".x7q-row:has(.attr-label:contains(ARTISTA)) .attr-value").text()
        val status = doc.select(".x7q-row:has(.attr-label:contains(STATUS)) .attr-value").text()
        val desc = doc.selectFirst(".x7q-summary .summary-text-scroll")?.text()
        val genres = doc.select(".genres-content a").map { it.text() }

        assertEquals("Moon Si Hyun", author)
        assertEquals("HD·ZD", artist)
        assertEquals("On-Going", status)
        assertEquals("Depois de uma vida trágica, ela renasceu.", desc)
        assertEquals(listOf("Fantasia", "Romance"), genres)
    }

    @Test
    fun test4_chaptersParsing_withNewAndLegacySelectors() {
        val html = """
            <ul>
                <li class="wp-manga-chapter">
                    <a href="https://tiraninha.world/manga/test/capitulo-28/">
                        <div class="chapter-info-container">
                            <span class="x7q-chapter-name">Capítulo 28</span>
                            <span class="x7q-chapter-date"><i>janeiro 29, 2026</i></span>
                        </div>
                    </a>
                </li>
                <li class="wp-manga-chapter">
                    <a href="https://tiraninha.world/manga/test/capitulo-27/">
                        <span class="chapter-name-label">Capítulo 27</span>
                        <span class="chapter-pub-date"><i>janeiro 28, 2026</i></span>
                    </a>
                </li>
            </ul>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val items = doc.select("li.wp-manga-chapter")
        assertEquals(2, items.size)

        val name1 = items[0].selectFirst(".x7q-chapter-name, .chapter-name-label")?.text()
        val date1 = items[0].selectFirst(".x7q-chapter-date, .chapter-pub-date")?.text()
        assertEquals("Capítulo 28", name1)
        assertEquals("janeiro 29, 2026", date1)

        val name2 = items[1].selectFirst(".x7q-chapter-name, .chapter-name-label")?.text()
        val date2 = items[1].selectFirst(".x7q-chapter-date, .chapter-pub-date")?.text()
        assertEquals("Capítulo 27", name2)
        assertEquals("janeiro 28, 2026", date2)
    }

    @Test
    fun test5_readerScriptExtractionAndGatekeeperUrlBuilding() {
        val script = """
            var _proxyUrls = ["%2Fwp-content%2Fthemes%2Fmadara2%2Fimage-loader.php%3Fpath%3D1234"];
            var _themePath = "https:\/\/tiraninha.world\/wp-content\/themes\/madara2";
            var _browserNonce = "520da62c680e805951b5be9b5d2e47bc61b66e22e215a627";
        """.trimIndent()

        val nonce = LittleTyrant.extractNonce(script)
        val themePath = LittleTyrant.extractThemePath(script)
        val proxyUrls = LittleTyrant.extractProxyUrls(script)

        assertEquals("520da62c680e805951b5be9b5d2e47bc61b66e22e215a627", nonce)
        assertEquals("https://tiraninha.world/wp-content/themes/madara2", themePath)
        assertNotNull(proxyUrls)

        val url = LittleTyrant.buildGatekeeperUrl("https://tiraninha.world", themePath!!, nonce, 1790656000000L)
        assertEquals(
            "https://tiraninha.world/wp-content/themes/madara2/gatekeeper.php?t=1790656000000&browser_nonce=520da62c680e805951b5be9b5d2e47bc61b66e22e215a627",
            url.toString(),
        )

        val tokenJson = """{"token":"1790656393.527fc28039da2cebe23c9329aba1248db743352a606f20da221255555bb519a9"}"""
        val parsedToken = LittleTyrant.parseTokenFromBody(tokenJson)
        assertEquals("1790656393.527fc28039da2cebe23c9329aba1248db743352a606f20da221255555bb519a9", parsedToken)

        val pages = LittleTyrant.buildPageListFromProxy(proxyUrls!!, themePath, parsedToken, nonce, 1790656000000L)
        assertEquals(1, pages.size)
        assertTrue(pages[0].imageUrl!!.startsWith("https://tiraninha.world/wp-content/themes/madara2/image-loader.php?path=1234&t_force=1790656000000#"))
        assertTrue(pages[0].imageUrl!!.contains(parsedToken))
        assertTrue(pages[0].imageUrl!!.contains(nonce))
    }

    @Test
    fun test6_imageDecoderInterceptor_cookiePreservationAndKeyDerivation() {
        val token = "1790656393.527fc28039da2cebe23c9329aba1248db743352a606f20da221255555bb519a9"
        val nonce = "520da62c680e805951b5be9b5d2e47bc61b66e22e215a627"

        val key = ImageDecoderInterceptor.getKey(token)
        assertEquals("c28039da2cebe23c", key)

        val existingCookie = "cf_clearance=abc123xyz"
        val cookieHeader = ImageDecoderInterceptor.buildCookieHeader(existingCookie, token, nonce)
        assertEquals("cf_clearance=abc123xyz; lt_browser_nonce=$nonce; lt_sec_val=$token", cookieHeader)

        val fragment = """{"token":"$token","nonce":"$nonce"}"""
        val parsed = ImageDecoderInterceptor.parseTokenAndNonce(fragment)
        assertNotNull(parsed)
        assertEquals(token, parsed!!.first)
        assertEquals(nonce, parsed.second)
    }

    @Test
    fun test7_imageDecoderInterceptor_xorDecodingValidWebP() {
        val token = "1790656393.527fc28039da2cebe23c9329aba1248db743352a606f20da221255555bb519a9"
        val key = ImageDecoderInterceptor.getKey(token)

        // WebP header: "RIFF" (4 bytes) + size (4 bytes) + "WEBP" (4 bytes)
        val originalBytes = byteArrayOf(
            'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
            0x20, 0x00, 0x00, 0x00,
            'W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte(),
            0x01, 0x02, 0x03, 0x04,
        )

        // Mask bytes by XORing with key
        val maskedBytes = originalBytes.copyOf()
        for (i in maskedBytes.indices) {
            maskedBytes[i] = (maskedBytes[i].toInt() xor key[i % key.length].code).toByte()
        }

        // Decrypt
        val decrypted = ImageDecoderInterceptor.decode(maskedBytes, key)
        assertTrue(originalBytes.contentEquals(decrypted))

        val mediaType = ImageDecoderInterceptor.detectMediaType(decrypted)
        assertEquals("image/webp", mediaType?.toString())
    }

    @Test
    fun test8_legacyBase64PagesDecode() {
        val rawUrl = "https://tiraninha.world/wp-content/uploads/ch1/01.jpg"
        val encoded = Base64.getEncoder().encodeToString(rawUrl.toByteArray(StandardCharsets.UTF_8))
        val decoded = LittleTyrant.decodePageUrl(encoded)
        assertEquals(rawUrl, decoded)
    }

    @Test
    fun test9_gatekeeperHeadersWithChapterReferer() {
        val nonce = "520da62c680e805951b5be9b5d2e47bc61b66e22e215a627"
        val chapterUrl = "https://tiraninha.world/manga/limite-absoluto/capitulo-1/"
        val headers = LittleTyrant.buildGatekeeperHeaders(okhttp3.Headers.Builder(), nonce, chapterUrl)
        assertEquals("tiraninha-web", headers["X-Reader-Sec"])
        assertEquals("cors", headers["Sec-Fetch-Mode"])
        assertEquals("empty", headers["Sec-Fetch-Dest"])
        assertEquals("same-origin", headers["Sec-Fetch-Site"])
        assertEquals(chapterUrl, headers["Referer"])
        assertEquals("lt_browser_nonce=$nonce", headers["Cookie"])
    }

    @Test
    fun test10_parseReadingNavHtml() {
        val html = """
            <label>
                <select class="c-selectpicker selectpicker_chapter selectpicker single-chapter-select" for="volume-id-0">
                    <option class="short" data-limit="40" value="" data-redirect="https://tiraninha.world/manga/conforme-o-seu-coracao-guiar/capitulo-66/">Capítulo 66</option>
                    <option class="short" data-limit="40" value="" data-redirect="https://tiraninha.world/manga/conforme-o-seu-coracao-guiar/capitulo-65/">Capítulo 65</option>
                    <option class="short" data-limit="40" value="capitulo-64" data-redirect="">Capítulo 64 - Bônus</option>
                    <option value="">-- Selecione o capítulo --</option>
                </select>
            </label>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val entries = LittleTyrant.parseReadingNavEntries(doc, "/manga/conforme-o-seu-coracao-guiar/")

        assertEquals(3, entries.size)
        assertEquals("/manga/conforme-o-seu-coracao-guiar/capitulo-66/", entries[0].url)
        assertEquals("Capítulo 66", entries[0].name)
        assertEquals(66f, entries[0].chapterNumber, 0.01f)

        assertEquals("/manga/conforme-o-seu-coracao-guiar/capitulo-65/", entries[1].url)
        assertEquals("Capítulo 65", entries[1].name)
        assertEquals(65f, entries[1].chapterNumber, 0.01f)

        assertEquals("/manga/conforme-o-seu-coracao-guiar/capitulo-64/", entries[2].url)
        assertEquals("Capítulo 64 - Bônus", entries[2].name)
        assertEquals(64f, entries[2].chapterNumber, 0.01f)
    }

    @Test
    fun test11_parseChapterNumber() {
        assertEquals(66f, LittleTyrant.parseChapterNumber("Capítulo 66"), 0.01f)
        assertEquals(131f, LittleTyrant.parseChapterNumber("Capítulo 131 - Fim da Segunda Temporada"), 0.01f)
        assertEquals(12.5f, LittleTyrant.parseChapterNumber("Cap. 12.5"), 0.01f)
        assertEquals(3.1f, LittleTyrant.parseChapterNumber("Capitulo 3,1"), 0.01f)
        assertEquals(-1f, LittleTyrant.parseChapterNumber("Prólogo"), 0.01f)
    }

    @Test
    fun test12_mangaIdExtraction() {
        val html1 = """<link rel='shortlink' href='https://tiraninha.world/?p=5276' />"""
        val doc1 = Jsoup.parse(html1)
        val shortlinkId = doc1.selectFirst("link[rel=shortlink]")?.attr("href")?.substringAfter("?p=")?.substringBefore('&')
        assertEquals("5276", shortlinkId)

        val html2 = """<body class="wp-singular postid-7451 wp-manga-page"></body>"""
        val doc2 = Jsoup.parse(html2)
        val postid = doc2.selectFirst("body")?.classNames()?.firstOrNull { it.startsWith("postid-") }?.substringAfter("postid-")
        assertEquals("7451", postid)

        val html3 = """<button id="btn-load-more-chapters" data-manga-id="2715"></button>"""
        val doc3 = Jsoup.parse(html3)
        val dataMangaId = doc3.selectFirst("[data-manga-id]")?.attr("data-manga-id")
        assertEquals("2715", dataMangaId)

        val html4 = """var manga = {"ajax_url":"https://tiraninha.world/wp-admin/admin-ajax.php","manga_id":"9876"};"""
        val regex = """(?:["']manga_id["']|manga_id)\s*:\s*["']?(\d+)["']?""".toRegex()
        val extractedMangaId = regex.find(html4)?.groupValues?.get(1)
        assertEquals("9876", extractedMangaId)
    }

    @Test
    fun test13_dynamicNonceGenerationWhenScriptUsesCryptoRandomValues() {
        val dynamicScript = """
            var _themePath = "https:\/\/tiraninha.world\/wp-content\/themes\/madara2".replace(/^https?:\/\/[^\/]+/, '');
            var _proxyUrls = ["\/wp-content\/themes\/madara2\/image-loader.php?path=1234"];
            var _nonceBytes = new Uint8Array(24);
            window.crypto.getRandomValues(_nonceBytes);
            var _browserNonce = Array.from(_nonceBytes, function(byte) {
                return byte.toString(16).padStart(2, '0');
            }).join('');
        """.trimIndent()

        val nonce = LittleTyrant.extractNonce(dynamicScript)
        assertNotNull(nonce)
        assertEquals(48, nonce.length)
        assertTrue(nonce.all { it in "0123456789abcdef" })

        val headers = LittleTyrant.buildGatekeeperHeaders(
            okhttp3.Headers.Builder(),
            nonce,
            "https://tiraninha.world/manga/test/cap-1/",
        )
        assertEquals("lt_browser_nonce=$nonce", headers["Cookie"])
        assertEquals("tiraninha-web", headers["X-Reader-Sec"])
    }

    @Test
    fun test14_cookieParsingAndMerging() {
        val existing = "cf_clearance=cf123; lt_sec_val=old_val; other=xyz"
        val parsed = ImageDecoderInterceptor.parseCookies(existing)
        assertEquals("cf123", parsed["cf_clearance"])
        assertEquals("old_val", parsed["lt_sec_val"])
        assertEquals("xyz", parsed["other"])

        val token = "new_token"
        val nonce = "new_nonce"
        val merged = ImageDecoderInterceptor.buildCookieHeader(existing, token, nonce)
        assertEquals("cf_clearance=cf123; lt_sec_val=new_token; other=xyz; lt_browser_nonce=new_nonce", merged)
    }
}
