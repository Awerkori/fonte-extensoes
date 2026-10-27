package eu.kanade.tachiyomi.extension.pt.gomangas

import kotlinx.serialization.json.Json
import okhttp3.Headers
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoMangasTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Test
    fun testFormatChapterName_Standard() {
        assertEquals("Cap. 120", formatChapterName("120", 120.0, "Capítulo 120"))
        assertEquals("Cap. 119", formatChapterName("119", 119.0, "Capítulo 119"))
        assertEquals("Cap. 10.5", formatChapterName("10.5", 10.5, "Capítulo 10.5"))
        assertEquals("Cap. 0", formatChapterName("0", 0.0, "Capítulo 0"))
        assertEquals("Cap. 184", formatChapterName("184", 184.0, null))
        assertEquals("Cap. 5", formatChapterName(null, 5.0, "Capítulo 5"))
        assertEquals("Cap. 7.5", formatChapterName(null, 7.5, null))
    }

    @Test
    fun testFormatChapterName_SpecialAndCustom() {
        assertEquals("Prólogo", formatChapterName("0.01", 0.001, "Prólogo"))
        assertEquals("Especial", formatChapterName(null, null, "Especial"))
        assertEquals("One-shot", formatChapterName(null, null, "One-shot"))

        // Custom chapter titles with number prefix cleaned
        assertEquals("Cap. 50 - O Despertar", formatChapterName("50", 50.0, "Capítulo 50 - O Despertar"))
        assertEquals("Cap. 50 - O Despertar", formatChapterName("50", 50.0, "Cap. 50: O Despertar"))
        assertEquals("Cap. 12 - A Batalha", formatChapterName("12", 12.0, "A Batalha"))
    }

    @Test
    fun testParsePortugueseDate() {
        val ts1 = parsePortugueseDate("09 de out. de 2026")
        assertTrue("Timestamp for '09 de out. de 2026' must be positive", ts1 > 0L)

        val ts2 = parsePortugueseDate("15 de jan. de 2025")
        assertTrue("Timestamp for '15 de jan. de 2025' must be positive", ts2 > 0L)
        assertTrue("2026 date must be after 2025 date", ts1 > ts2)

        assertEquals(0L, parsePortugueseDate(null))
        assertEquals(0L, parsePortugueseDate(""))
        assertEquals(0L, parsePortugueseDate("data invalida"))
    }

    @Test
    fun testIsValidCoverUrl() {
        assertTrue(isValidCoverUrl("https://imagens.gomangas.com/covers/berserk.jpg"))
        assertTrue(isValidCoverUrl("https://imagens.gomangas.com/covers/o-retorno-da-seita-do-monte-hua.webp"))
        assertTrue(isValidCoverUrl("https://imagens.gomangas.com/covers/kagurabachi.png"))

        // Promotional open-graph and cards must be rejected
        assertFalse(isValidCoverUrl("https://gomangas.com/manga/berserk/opengraph-image-vzsj5b?771d2d2587f2d58c"))
        assertFalse(isValidCoverUrl("https://gomangas.com/manga/kagurabachi/opengraph-image"))
        assertFalse(isValidCoverUrl("https://gomangas.com/manga/banner.jpg"))
        assertFalse(isValidCoverUrl("https://gomangas.com/cards/promo.png"))
        assertFalse(isValidCoverUrl("https://gomangas.com/manifest.json"))
        assertFalse(isValidCoverUrl(""))
        assertFalse(isValidCoverUrl(null))
    }

    @Test
    fun testExtractMangaCover_PreserveCatalogCover() {
        val existingThumb = "https://imagens.gomangas.com/covers/kagurabachi.jpg"
        val doc = Jsoup.parse("<html><head><meta property='og:image' content='https://gomangas.com/manga/kagurabachi/opengraph-image'/></head></html>")
        val cover = extractMangaCover(doc, "/manga/kagurabachi", existingThumb, doc.html())

        assertEquals("Must preserve catalog cover over og:image", existingThumb, cover)
    }

    @Test
    fun testExtractMangaCover_AltElementCover() {
        val html = """
            <html>
                <body>
                    <img alt="Capa de Kagurabachi" src="/_next/image?url=https%3A%2F%2Fimagens.gomangas.com%2Fcovers%2Fkagurabachi.jpg&amp;w=3840&amp;q=75"/>
                    <meta property="og:image" content="https://gomangas.com/manga/kagurabachi/opengraph-image-vzsj5b?771d2d2587f2d58c"/>
                </body>
            </html>
        """.trimIndent()
        val doc = Jsoup.parse(html)
        val cover = extractMangaCover(doc, "/manga/kagurabachi", null, html)

        assertEquals("https://imagens.gomangas.com/covers/kagurabachi.jpg", cover)
    }

    @Test
    fun testExtractMangaCover_CanonicalDirectEntry() {
        val html = """
            <html>
                <head>
                    <meta property="og:image" content="https://gomangas.com/manga/berserk/opengraph-image-vzsj5b"/>
                </head>
                <body>
                    <script>self.__next_f.push([1,"\"coverUrl\":\"https://imagens.gomangas.com/covers/berserk.jpg\""])</script>
                </body>
            </html>
        """.trimIndent()
        val doc = Jsoup.parse(html)
        val cover = extractMangaCover(doc, "/manga/berserk", null, html)

        assertEquals("https://imagens.gomangas.com/covers/berserk.jpg", cover)
    }

    @Test
    fun testCleanCoverUrl() {
        val nextJsUrl = "/_next/image?url=https%3A%2F%2Fimagens.gomangas.com%2Fcovers%2Fthe-return-of-the-iron-blood-sword-hound.jpg&w=3840&q=75"
        assertEquals(
            "https://imagens.gomangas.com/covers/the-return-of-the-iron-blood-sword-hound.jpg",
            cleanCoverUrl(nextJsUrl),
        )

        val directUrl = "https://imagens.gomangas.com/covers/one-piece.jpg"
        assertEquals(directUrl, cleanCoverUrl(directUrl))

        val escapedUrl = "https://imagens.gomangas.com/covers/dandadan.jpg\\\\"
        assertEquals("https://imagens.gomangas.com/covers/dandadan.jpg", cleanCoverUrl(escapedUrl))

        val encodedDirect = "https%3A%2F%2Fimagens.gomangas.com%2Fcovers%2Fsolo-leveling.jpg"
        assertEquals("https://imagens.gomangas.com/covers/solo-leveling.jpg", cleanCoverUrl(encodedDirect))
    }

    @Test
    fun testParseChapterNumberFromText() {
        assertEquals(386f, parseChapterNumberFromText("berserk-capitulo-386", "Capítulo 386"))
        assertEquals(0.01f, parseChapterNumberFromText("berserk-capitulo-0-01", "Capítulo 0.01"))
        assertEquals(0.1f, parseChapterNumberFromText("berserk-capitulo-0-1", "Capítulo 0.1"))
        assertEquals(184f, parseChapterNumberFromText("o-retorno-da-seita-do-monte-hua-capitulo-184", "Cap. 184"))
        assertEquals(356.2f, parseChapterNumberFromText("obra-capitulo-356-2", "Cap. 356.2"))
    }

    @Test
    fun testChapterDtoParsing() {
        val rawJson = """
            [
                {"id":40611,"slug":"berserk-capitulo-386","number":386,"numberLabel":"386","title":"Capítulo 386","sourcePublishedAt":null,"createdAt":"2026-10-09T01:15:51.232Z"},
                {"id":42310,"slug":"berserk-capitulo-0-01","number":0.001,"numberLabel":"0.01","title":"Prólogo","sourcePublishedAt":null,"createdAt":"2026-10-09T01:15:52.125Z"}
            ]
        """.trimIndent()

        val chapters = json.decodeFromString<List<GoMangasChapterDto>>(rawJson)
        assertEquals(2, chapters.size)

        assertEquals(40611L, chapters[0].id)
        assertEquals("berserk-capitulo-386", chapters[0].slug)
        assertEquals(386.0, chapters[0].number)
        assertEquals("386", chapters[0].numberLabel)
        assertEquals("Capítulo 386", chapters[0].title)

        assertEquals(42310L, chapters[1].id)
        assertEquals("berserk-capitulo-0-01", chapters[1].slug)
        assertEquals("0.01", chapters[1].numberLabel)
        assertEquals(0.01f, chapters[1].numberLabel?.toFloatOrNull())
    }

    @Test
    fun testPageDtoParsing() {
        val rawJson = """
            [
                {"imageUrl":"https://imagens.gomangas.com/manga/berserk/capitulo-386/page-001.webp"},
                {"imageUrl":"https://imagens.gomangas.com/manga/berserk/capitulo-386/page-002.webp"},
                {"imageUrl":"https://imagens.gomangas.com/covers/berserk.jpg"}
            ]
        """.trimIndent()

        val pages = json.decodeFromString<List<GoMangasPageDto>>(rawJson)
        val validPages = pages.filter { it.imageUrl.isNotBlank() && !it.imageUrl.contains("/covers/") }

        assertEquals(2, validPages.size)
        assertEquals("https://imagens.gomangas.com/manga/berserk/capitulo-386/page-001.webp", validPages[0].imageUrl)
        assertEquals("https://imagens.gomangas.com/manga/berserk/capitulo-386/page-002.webp", validPages[1].imageUrl)
    }

    @Test
    fun testBookSchemaParsing() {
        val rawJson = """
            {"@context":"https://schema.org","@type":"Book","name":"Berserk","bookFormat":"https://schema.org/GraphicNovel","genre":["Ação","Aventura","Drama","Fantasia","Psicológico","Terror"],"author":{"@type":"Person","name":"Kentarou Miura"}}
        """.trimIndent()

        val book = json.decodeFromString<BookSchemaDto>(rawJson)
        assertEquals("Berserk", book.name)
        assertEquals("Kentarou Miura", book.author?.name)
        assertEquals(6, book.genre.size)
        assertTrue(book.genre.contains("Ação"))
    }

    @Test
    fun testParseIsoDate() {
        val ts1 = parseIsoDate("2026-10-09T01:15:51.232Z")
        assertTrue("Parsed timestamp must be positive", ts1 > 0L)

        val ts2 = parseIsoDate("2026-10-23T14:35:41.000Z")
        assertTrue(ts2 > ts1)

        assertEquals(0L, parseIsoDate(null))
        assertEquals(0L, parseIsoDate(""))
        assertEquals(0L, parseIsoDate("invalid-iso-date"))
    }

    @Test
    fun testBuildGoMangasImageRequest_InternalCdn() {
        val baseUrl = "https://gomangas.com"
        val internalUrl = "https://imagens.gomangas.com/manga/berserk/capitulo-386/page-001.webp"
        val baseHeaders = Headers.Builder()
            .set("Origin", baseUrl)
            .set("Referer", "$baseUrl/")
            .build()

        val req = buildGoMangasImageRequest(internalUrl, baseUrl, baseHeaders)

        assertEquals(internalUrl, req.url.toString())
        assertEquals("https://gomangas.com/", req.header("Referer"))
        assertNull("Origin must be stripped to prevent CDN issues", req.header("Origin"))
        assertEquals("same-site", req.header("Sec-Fetch-Site"))
        assertEquals("image", req.header("Sec-Fetch-Dest"))
        assertEquals("no-cors", req.header("Sec-Fetch-Mode"))
        assertTrue(req.header("User-Agent")!!.contains("Mozilla/5.0"))
        assertTrue(req.header("Accept")!!.contains("image/"))
    }

    @Test
    fun testBuildGoMangasImageRequest_ExternalCdn() {
        val baseUrl = "https://gomangas.com"
        val externalUrl = "https://cdn.parceiro.com/manga/page01.jpg"
        val baseHeaders = Headers.Builder()
            .set("Origin", baseUrl)
            .set("Referer", "$baseUrl/")
            .build()

        val req = buildGoMangasImageRequest(externalUrl, baseUrl, baseHeaders)

        assertEquals(externalUrl, req.url.toString())
        assertNull("External CDNs must NOT receive gomangas Referer", req.header("Referer"))
        assertNull("Origin must be stripped for external CDNs", req.header("Origin"))
        assertEquals("cross-site", req.header("Sec-Fetch-Site"))
        assertEquals("image", req.header("Sec-Fetch-Dest"))
        assertEquals("no-cors", req.header("Sec-Fetch-Mode"))
    }

    @Test
    fun testBuildGoMangasImageRequest_WithSpaces() {
        val baseUrl = "https://gomangas.com"
        val urlWithSpaces = "https://imagens.gomangas.com/manga/obra teste/page 01.webp"

        val req = buildGoMangasImageRequest(urlWithSpaces, baseUrl)
        assertTrue(req.url.toString().contains("obra%20teste"))
        assertTrue(req.url.toString().contains("page%2001.webp"))
        assertEquals("https://gomangas.com/", req.header("Referer"))
        assertNull(req.header("Origin"))
    }

    @Test
    fun testFilters() {
        val filters = GoMangasFilters.get()
        assertEquals(4, filters.size)

        val sortFilter = filters[0] as SortFilter
        assertEquals("recentes", sortFilter.selected)
        assertEquals(4, SortFilter.SORT_OPTIONS.size)

        val typeFilter = filters[1] as TypeFilter
        assertEquals("", typeFilter.selected)
        assertEquals(4, TypeFilter.TYPE_OPTIONS.size)

        val statusFilter = filters[2] as StatusFilter
        assertEquals("", statusFilter.selected)
        assertEquals(4, StatusFilter.STATUS_OPTIONS.size)

        val genreFilter = filters[3] as GenreFilter
        assertEquals("", genreFilter.selected)
        assertEquals(28, GenreFilter.GENRE_OPTIONS.size)
    }
}
