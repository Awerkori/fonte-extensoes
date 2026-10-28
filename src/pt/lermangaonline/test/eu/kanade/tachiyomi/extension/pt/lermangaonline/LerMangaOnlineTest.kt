package eu.kanade.tachiyomi.extension.pt.lermangaonline

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LerMangaOnlineTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @Test
    fun testCleanMangaTitle() {
        assertEquals("O Chef Apocalíptico", cleanMangaTitle("Ler O Chef Apocalíptico Online Grátis"))
        assertEquals("Boruto: Naruto Next Generations", cleanMangaTitle("Leia Boruto: Naruto Next Generations Online Grátis"))
        assertEquals("Berserk", cleanMangaTitle("Berserk"))
        assertEquals("Berserk", cleanMangaTitle("Ler Berserk Online Gratis"))
        assertEquals("Trapped in the Academy’s Eroge", cleanMangaTitle("Trapped in the Academy’s Eroge"))
        assertEquals("Son of Nam", cleanMangaTitle("Ler Son of Nam Online"))
    }

    @Test
    fun testCleanChapterName() {
        assertEquals("Cap. 120", cleanChapterName(120.0, "Capítulo 120"))
        assertEquals("Cap. 119", cleanChapterName(119.0, "Capítulo 119"))
        assertEquals("Cap. 10.5", cleanChapterName(10.5, "Capítulo 10.5"))
        assertEquals("Cap. 0", cleanChapterName(0.0, "Capítulo 0"))
        assertEquals("Prólogo", cleanChapterName(null, "Prólogo"))
        assertEquals("Especial", cleanChapterName(null, "Especial"))
        assertEquals("One-shot", cleanChapterName(null, "One-shot"))

        // Noise & badges
        assertEquals("Cap. 50 - O Despertar", cleanChapterName(50.0, "▣ Capítulo 50 - O Despertar"))
        assertEquals("Cap. 217", cleanChapterName(217.0, "Capítulo 217 \n NOVO"))
        assertEquals("Cap. 56", cleanChapterName(56.0, "Capítulo 56"))
    }

    @Test
    fun testExtractCoverUrl_FallbackJson() {
        val baseUrl = "https://lermangaonline.com.br"
        val html = """
            <img src="https://img.lermangaonline.com.br/i/abc123xyz"
                 data-mlo-cover-urls="[&quot;https:\/\/mangalivre.blog\/wp-content\/uploads\/2026\/10\/cover-001.webp&quot;]" />
        """.trimIndent()
        val img = Jsoup.parseBodyFragment(html).selectFirst("img")

        val cover = extractCoverUrl(img, baseUrl)
        assertEquals("https://mangalivre.blog/wp-content/uploads/2026/10/cover-001.webp", cover)
    }

    @Test
    fun testExtractCoverUrl_SrcDirect() {
        val baseUrl = "https://lermangaonline.com.br"
        val html = """<img src="https://img.lermangaonline.com.br/i/abc123xyz" />"""
        val img = Jsoup.parseBodyFragment(html).selectFirst("img")

        val cover = extractCoverUrl(img, baseUrl)
        assertEquals("https://img.lermangaonline.com.br/i/abc123xyz", cover)
    }

    @Test
    fun testParseStatus() {
        assertEquals(SManga.ONGOING, parseStatus("Em Andamento"))
        assertEquals(SManga.ONGOING, parseStatus("Em Lançamento"))
        assertEquals(SManga.COMPLETED, parseStatus("Completo"))
        assertEquals(SManga.COMPLETED, parseStatus("Finalizado"))
        assertEquals(SManga.ON_HIATUS, parseStatus("Hiato"))
        assertEquals(SManga.ON_HIATUS, parseStatus("Em Hiato"))
        assertEquals(SManga.CANCELLED, parseStatus("Cancelado"))
        assertEquals(SManga.UNKNOWN, parseStatus("Desconhecido"))
        assertEquals(SManga.UNKNOWN, parseStatus(null))
    }

    @Test
    fun testParseIsoDate() {
        val ts = parseIsoDate("2026-10-27T21:59:45+00:00")
        assertTrue("Timestamp must be positive", ts > 0L)

        val ts2 = parseIsoDate("2026-10-27T22:00:00Z")
        assertTrue("Later date must have higher timestamp", ts2 > ts)

        assertEquals(0L, parseIsoDate(null))
        assertEquals(0L, parseIsoDate(""))
        assertEquals(0L, parseIsoDate("invalido"))
    }

    @Test
    fun testCleanUrls() {
        val baseUrl = "https://lermangaonline.com.br"
        assertEquals("/manga/berserk/", cleanMangaUrl("https://lermangaonline.com.br/manga/berserk/", baseUrl))
        assertEquals("/manga/berserk/", cleanMangaUrl("/manga/berserk", baseUrl))
        assertEquals(
            "/capitulo/berserk-capitulo-1/",
            cleanChapterUrl("https://lermangaonline.com.br/capitulo/berserk-capitulo-1/", baseUrl),
        )
    }

    @Test
    fun testReaderDtoParsing() {
        val rawJson = """
            {
                "pages": [
                    "https://img.lermangaonline.com.br/i/page1",
                    "https://img.lermangaonline.com.br/i/page2"
                ],
                "originals": [
                    "https://pub-fd34549394dd487c833b561317c11eed.r2.dev/manga/589810/capitulo-56/page-001.webp",
                    "https://pub-fd34549394dd487c833b561317c11eed.r2.dev/manga/589810/capitulo-56/page-002.webp"
                ],
                "title": "O Chef Apocalíptico",
                "number": "56"
            }
        """.trimIndent()

        val dto = json.decodeFromString<LerMangaOnlineReaderDto>(rawJson)
        assertEquals(2, dto.pages.size)
        assertEquals(2, dto.originals.size)
        assertEquals("O Chef Apocalíptico", dto.title)
        assertEquals("56", dto.number)
        assertEquals(
            "https://pub-fd34549394dd487c833b561317c11eed.r2.dev/manga/589810/capitulo-56/page-001.webp",
            dto.originals[0],
        )
    }

    @Test
    fun testChapterBatchAndConfigDto() {
        val configJson = """
            {"ajaxUrl":"https://lermangaonline.com.br/wp-admin/admin-ajax.php","mangaId":"45312","page":"1","perPage":"25","total":"405"}
        """.trimIndent()
        val config = json.decodeFromString<LerMangaOnlineChapterConfigDto>(configJson)
        assertEquals("https://lermangaonline.com.br/wp-admin/admin-ajax.php", config.ajaxUrl)
        assertEquals("45312", config.mangaId)
        assertEquals("405", config.total)
        assertEquals("25", config.perPage)

        val batchJson = """
            {"success":true,"data":{"page":2,"hasMore":true,"html":"<li data-chapter-number=\"361\">...</li>"}}
        """.trimIndent()
        val batch = json.decodeFromString<LerMangaOnlineChapterBatchDto>(batchJson)
        assertTrue(batch.success)
        assertEquals(2, batch.data?.page)
        assertTrue(batch.data?.hasMore == true)
        assertTrue(batch.data?.html?.contains("data-chapter-number") == true)
    }

    @Test
    fun testBuildLerMangaOnlineImageRequest_R2Dev() {
        val baseUrl = "https://lermangaonline.com.br"
        val r2Url = "https://pub-fd34549394dd487c833b561317c11eed.r2.dev/manga/589810/capitulo-56/page-001.webp"

        val req = buildLerMangaOnlineImageRequest(r2Url, baseUrl)
        assertEquals(r2Url, req.url.toString())
        assertNull("R2 direct CDN should have Referer stripped", req.header("Referer"))
        assertEquals("cross-site", req.header("Sec-Fetch-Site"))
        assertEquals("image", req.header("Sec-Fetch-Dest"))
        assertEquals("no-cors", req.header("Sec-Fetch-Mode"))
        val accept = req.header("Accept") ?: ""
        assertFalse("Accept header must NOT request image/avif to prevent decoder initialization errors", accept.contains("image/avif"))
        assertTrue("Accept header should request image/webp", accept.contains("image/webp"))
    }

    @Test
    fun testBuildLerMangaOnlineImageRequest_SameSite() {
        val baseUrl = "https://lermangaonline.com.br"
        val sameSiteUrl = "https://lermangaonline.com.br/uploads/page1.webp"

        val req = buildLerMangaOnlineImageRequest(sameSiteUrl, baseUrl)
        assertEquals(sameSiteUrl, req.url.toString())
        assertEquals("https://lermangaonline.com.br/", req.header("Referer"))
        assertEquals("same-site", req.header("Sec-Fetch-Site"))
    }

    @Test
    fun testFiltersList() {
        val filters = LerMangaOnlineFilters.get(null)
        val typeFilter = filters.find { it is TypeFilter } as? TypeFilter
        val genreFilter = filters.find { it is GenreFilter } as? GenreFilter

        assertNotNull("TypeFilter must be present", typeFilter)
        assertNotNull("GenreFilter must be present", genreFilter)
    }
}
