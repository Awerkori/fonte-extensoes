package eu.kanade.tachiyomi.extension.pt.neotoons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import okhttp3.Headers

class NeoToonsTest {

    @Test
    fun testIsTextNovel() {
        // Raw scraper dump with 0 chapters
        val rawDump = MangaDto(
            title = "14.4KNOVELAçãoAventuraOmniscient",
            slug = "14-4k-novel",
            _count = MangaCountDto(chapters = 0),
        )
        assertTrue(rawDump.isTextNovel())

        // Works with "Novel" or "(Novel)" or "(LN)" in title MUST NOT be flagged as text novels
        val comic1 = MangaDto(
            title = "The Novel's Extra",
            slug = "the-novels-extra",
            genres = listOf(
                MangaGenreRelationDto(GenreDto(name = "Novel", slug = "novel")),
                MangaGenreRelationDto(GenreDto(name = "Ação", slug = "acao")),
            ),
            _count = MangaCountDto(chapters = 177),
        )
        assertFalse(comic1.isTextNovel())

        val comic2 = MangaDto(
            title = "Entrei em uma novel da internet",
            slug = "entrei-em-uma-novel-da-internet",
            genres = listOf(
                MangaGenreRelationDto(GenreDto(name = "Novel", slug = "novel")),
                MangaGenreRelationDto(GenreDto(name = "Comédia", slug = "comedia")),
            ),
            _count = MangaCountDto(chapters = 223),
        )
        assertFalse(comic2.isTextNovel())

        val comic3 = MangaDto(
            title = "O Mundo Se Esvaiu Em uma Quarta-Feira (Novel)",
            slug = "o-mundo-se-esvaiu-em-uma-quartafeira-novel",
            _count = MangaCountDto(chapters = 24),
        )
        assertFalse(comic3.isTextNovel())

        val comic4 = MangaDto(
            title = "86: Eighty Six (Novel)",
            slug = "86-eighty-six-novel",
            _count = MangaCountDto(chapters = 1),
        )
        assertFalse(comic4.isTextNovel())

        val comic5 = MangaDto(
            title = "The Water Magician (LN)",
            slug = "the-water-magician-ln",
            _count = MangaCountDto(chapters = 1),
        )
        assertFalse(comic5.isTextNovel())

        val comic6 = MangaDto(
            title = "Solo Leveling",
            slug = "solo-leveling",
            genres = listOf(MangaGenreRelationDto(GenreDto(name = "Ação", slug = "acao"))),
            _count = MangaCountDto(chapters = 200),
        )
        assertFalse(comic6.isTextNovel())

        val comic7 = MangaDto(
            title = "The Boxer",
            slug = "the-boxer",
            genres = listOf(MangaGenreRelationDto(GenreDto(name = "Esportes", slug = "esportes"))),
            _count = MangaCountDto(chapters = 123),
        )
        assertFalse(comic7.isTextNovel())
    }

    @Test
    fun testChapterReadDto() {
        val pageDtoList = listOf(
            ChapterPageDto(id = "p0", pageNumber = 0, imageUrl = "/banners/neotoons-chapter-intro.jpg"),
            ChapterPageDto(id = "p1", pageNumber = 1, imageUrl = "https://cdn.neotoons.com.br/obras/the-boxer/ch-123/p-001.webp?token=abc&expires=123"),
            ChapterPageDto(id = "p2", pageNumber = 2, imageUrl = "https://cdn.neotoons.com.br/obras/the-boxer/ch-123/p-002.webp?token=def&expires=123"),
        )
        val readResponse = ChapterReadResponseDto(
            id = "ch-123",
            chapterNumber = "123",
            pages = pageDtoList,
        )

        val filteredPages = readResponse.pages
            .filter { it.imageUrl.isNotBlank() && !it.imageUrl.contains("/banners/") }
            .sortedBy { it.pageNumber }

        assertEquals(2, filteredPages.size)
        assertEquals(1, filteredPages[0].pageNumber)
        assertEquals(2, filteredPages[1].pageNumber)
        assertTrue(filteredPages[0].imageUrl.contains("p-001.webp"))
        assertTrue(filteredPages[1].imageUrl.contains("p-002.webp"))
    }

    @Test
    fun testParseIsoDate() {
        val iso1 = "2026-01-19T18:43:37.000Z"
        val parsed1 = parseIsoDate(iso1)
        assertTrue("Parsed timestamp must be positive", parsed1 > 0L)

        val iso2 = "2026-09-27T00:48:31.361Z"
        val parsed2 = parseIsoDate(iso2)
        assertTrue("Parsed timestamp must be positive", parsed2 > 0L)
        assertTrue(parsed2 > parsed1)

        assertEquals(0L, parseIsoDate(null))
        assertEquals(0L, parseIsoDate(""))
        assertEquals(0L, parseIsoDate("   "))
        assertEquals(0L, parseIsoDate("invalid-date"))
    }

    @Test
    fun testChapterNumberParsing() {
        assertEquals(123f, "123".trim().toFloatOrNull())
        assertEquals(392.5f, "392.5".trim().toFloatOrNull())
        assertEquals(0f, "0".trim().toFloatOrNull())
        assertEquals(1f, " 1 ".trim().toFloatOrNull())
        assertEquals(null, "prologo".toFloatOrNull())
    }

    @Test
    fun testFilters() {
        val filters = NeoToonsFilters.get()
        assertEquals(4, filters.size)

        val sortFilter = filters[0] as SortFilter
        assertEquals("newest", sortFilter.selected)
        assertEquals(5, SortFilter.SORT_OPTIONS.size)

        val statusFilter = filters[1] as StatusFilter
        assertEquals("ALL", statusFilter.selected)
        assertEquals(4, StatusFilter.STATUS_OPTIONS.size)

        val demographicFilter = filters[2] as DemographicFilter
        assertEquals("ALL", demographicFilter.selected)
        assertEquals(6, DemographicFilter.DEMOGRAPHIC_OPTIONS.size)

        val genreFilter = filters[3] as GenreFilter
        assertEquals("", genreFilter.selected)
        assertTrue(GenreFilter.GENRE_OPTIONS.size > 20)
    }

    @Test
    fun testBuildNeoToonsImageRequest_InternalCdn() {
        val baseUrl = "https://neotoons.com.br"
        val internalUrl = "https://cdn.neotoons.com.br/obras/the-boxer/ch-123/p-001.webp?token=abc&expires=123"
        val baseHeaders = Headers.Builder()
            .set("Origin", baseUrl)
            .set("Referer", "$baseUrl/")
            .build()

        val req = buildNeoToonsImageRequest(internalUrl, baseUrl, baseHeaders)

        assertEquals(internalUrl, req.url.toString())
        assertEquals("https://neotoons.com.br/", req.header("Referer"))
        assertNull("Origin must be stripped to prevent CDN 403", req.header("Origin"))
        assertEquals("same-site", req.header("Sec-Fetch-Site"))
        assertEquals("image", req.header("Sec-Fetch-Dest"))
        assertEquals("no-cors", req.header("Sec-Fetch-Mode"))
        assertTrue(req.header("User-Agent")!!.contains("Mozilla/5.0"))
        assertTrue(req.header("Accept")!!.contains("image/"))
    }

    @Test
    fun testBuildNeoToonsImageRequest_ExternalCdn() {
        val baseUrl = "https://neotoons.com.br"
        val externalUrl = "https://cdn.rebmanga.com/cdn/MyfamilyPortuguese/001.jpeg"
        val baseHeaders = Headers.Builder()
            .set("Origin", baseUrl)
            .set("Referer", "$baseUrl/")
            .build()

        val req = buildNeoToonsImageRequest(externalUrl, baseUrl, baseHeaders)

        assertEquals(externalUrl, req.url.toString())
        assertNull("External CDNs must NOT receive neotoons Referer (prevents 403 hotlink block)", req.header("Referer"))
        assertNull("Origin must be stripped for external CDNs", req.header("Origin"))
        assertEquals("cross-site", req.header("Sec-Fetch-Site"))
        assertEquals("image", req.header("Sec-Fetch-Dest"))
        assertEquals("no-cors", req.header("Sec-Fetch-Mode"))
        assertTrue(req.header("User-Agent")!!.contains("Mozilla/5.0"))
    }

    @Test
    fun testBuildNeoToonsImageRequest_WithSpaces() {
        val baseUrl = "https://neotoons.com.br"
        val urlWithSpaces = "https://cdn.rebmanga.com/cdn/MyfamilyPortuguese/Capitulo 1/001.jpeg"

        val req = buildNeoToonsImageRequest(urlWithSpaces, baseUrl)
        assertTrue(req.url.toString().contains("Capitulo%201"))
        assertNull(req.header("Referer"))
        assertNull(req.header("Origin"))
    }
}
