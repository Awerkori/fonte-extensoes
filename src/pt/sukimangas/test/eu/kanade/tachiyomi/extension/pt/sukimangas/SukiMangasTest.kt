package eu.kanade.tachiyomi.extension.pt.sukimangas

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SukiMangasTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @Test
    fun testFormatChapterName() {
        assertEquals("Cap. 120", formatChapterName(120.0, "Capítulo 120"))
        assertEquals("Cap. 119", formatChapterName(119.0, "Capítulo 119"))
        assertEquals("Cap. 10.5", formatChapterName(10.5, "Capítulo 10.5"))
        assertEquals("Cap. 7.2", formatChapterName(7.2, "Capítulo 7.2"))
        assertEquals("Cap. 1.1", formatChapterName(1.1, "Capítulo 1.1"))
        assertEquals("Cap. 0", formatChapterName(0.0, "Capítulo 0"))
        assertEquals("Prólogo", formatChapterName(null, "Prólogo"))
        assertEquals("Especial", formatChapterName(null, "Especial"))
        assertEquals("One-shot", formatChapterName(null, "One-shot"))

        // Descriptive
        assertEquals(
            "Cap. 11.2 - Restauração - Pt. 2",
            formatChapterName(11.2, "Restauração - Pt. 2"),
        )
        assertEquals(
            "Cap. 50 - O Despertar",
            formatChapterName(50.0, "▣ Capítulo 50 - O Despertar"),
        )
    }

    @Test
    fun testParseIsoDate() {
        val tsDateOnly = parseIsoDate("2026-10-10")
        assertTrue("Date only should parse to valid positive timestamp", tsDateOnly > 0L)

        val tsMicroseconds = parseIsoDate("2026-10-10T23:54:18.978162")
        assertTrue("Microseconds format should parse to valid positive timestamp", tsMicroseconds > 0L)

        val tsZ = parseIsoDate("2026-10-10T23:54:18Z")
        assertTrue("Z format should parse to valid positive timestamp", tsZ > 0L)

        assertEquals(0L, parseIsoDate(null))
        assertEquals(0L, parseIsoDate(""))
        assertEquals(0L, parseIsoDate("invalid"))
    }

    @Test
    fun testParseStatus() {
        assertEquals(SManga.ONGOING, parseStatus("Em andamento"))
        assertEquals(SManga.ONGOING, parseStatus("Em lançamento"))
        assertEquals(SManga.COMPLETED, parseStatus("Finalizado"))
        assertEquals(SManga.COMPLETED, parseStatus("Completo"))
        assertEquals(SManga.ON_HIATUS, parseStatus("Em Hiatus"))
        assertEquals(SManga.ON_HIATUS, parseStatus("Hiato"))
        assertEquals(SManga.CANCELLED, parseStatus("Cancelado"))
        assertEquals(SManga.UNKNOWN, parseStatus("Desconhecido"))
        assertEquals(SManga.UNKNOWN, parseStatus(null))
    }

    @Test
    fun testNormalizeText() {
        assertEquals("acao", normalizeText("Ação"))
        assertEquals("capitulo", normalizeText("Capítulo"))
        assertEquals("o verao em que hikaru morreu", normalizeText("O verão em que Hikaru morreu"))
        assertEquals("amayo no tsuki", normalizeText("Amayo no Tsuki"))
    }

    @Test
    fun testBuildDescription() {
        val manga = SukiMangasMangaDto(
            id = "amayo-no-tsuki",
            title = "Amayo no Tsuki",
            titleOriginal = "雨夜の月",
            author = "Kuzushiro",
            group = "Petals Entwined",
            year = 2021,
            rating = 4.8,
            synopsis = "Uma linda história sobre a lua na noite chuvosa.",
        )
        val desc = buildDescription(manga)
        assertNotNull(desc)
        assertTrue(desc!!.contains("Uma linda história sobre a lua na noite chuvosa."))
        assertTrue(desc.contains("Título original: 雨夜の月"))
        assertTrue(desc.contains("Scan: Petals Entwined"))
        assertTrue(desc.contains("Ano: 2021"))
        assertTrue(desc.contains("Avaliação: ★ 4.8"))
    }

    @Test
    fun testExtractMangaId() {
        assertEquals("amayo-no-tsuki", extractMangaId("/manga?id=amayo-no-tsuki"))
        assertEquals("amayo-no-tsuki", extractMangaId("manga?id=amayo-no-tsuki&other=1"))
        assertEquals("amayo-no-tsuki", extractMangaId("/manga/amayo-no-tsuki"))
    }

    @Test
    fun testCatalogDtoParsing() {
        val jsonString = """
            {
                "mangas": [
                    {
                        "id": "amayo-no-tsuki",
                        "title": "Amayo no Tsuki",
                        "titleOriginal": "雨夜の月",
                        "cover": "https://pub-693f1895eb6141baac4f5ae8d187e139.r2.dev/covers/cover.jpg",
                        "coverThumb": "https://pub-693f1895eb6141baac4f5ae8d187e139.r2.dev/covers/thumb.jpg",
                        "author": "Kuzushiro",
                        "artist": "Kuzushiro",
                        "group": "Petals Entwined",
                        "status": "Em andamento",
                        "synopsis": "Sinopse aqui",
                        "genres": ["Drama", "Escolar", "Mangá", "Romance", "Seinen", "Slice of Life", "Yuri"],
                        "type": "manga",
                        "views": 378,
                        "viewsWeek": 89,
                        "rating": 4.9,
                        "year": 2021,
                        "chapters": [
                            {
                                "id": "amayo-no-tsuki-cap-1-1",
                                "number": 1.1,
                                "title": "Capítulo 1.1",
                                "releaseDate": "2026-09-24",
                                "createdAt": "2026-09-24T01:43:34.939069",
                                "hasContent": false,
                                "pages": [
                                    "https://pub-693f1895eb6141baac4f5ae8d187e139.r2.dev/pages/1.png"
                                ]
                            }
                        ]
                    }
                ]
            }
        """.trimIndent()

        val catalog = json.decodeFromString<SukiMangasCatalogDto>(jsonString)
        assertEquals(1, catalog.mangas.size)
        val manga = catalog.mangas[0]
        assertEquals("amayo-no-tsuki", manga.id)
        assertEquals("Amayo no Tsuki", manga.title)
        assertEquals("Kuzushiro", manga.author)
        assertEquals(89L, manga.viewsWeek)
        assertEquals(1, manga.chapters.size)
        assertEquals(1.1, manga.chapters[0].number!!, 0.001)
        assertEquals(1, manga.chapters[0].pages.size)
    }

    @Test
    fun testBuildSukiMangasImageRequest_R2Dev() {
        val baseUrl = "https://www.sukimangas.com"
        val r2Url = "https://pub-693f1895eb6141baac4f5ae8d187e139.r2.dev/pages/amayo-no-tsuki/1.png"

        val req = buildSukiMangasImageRequest(r2Url, baseUrl)
        assertEquals(r2Url, req.url.toString())
        assertNull("R2 direct CDN should have Referer stripped", req.header("Referer"))
        assertEquals("cross-site", req.header("Sec-Fetch-Site"))
        assertEquals("image", req.header("Sec-Fetch-Dest"))
        assertEquals("no-cors", req.header("Sec-Fetch-Mode"))
        val accept = req.header("Accept") ?: ""
        assertFalse("Accept header must NOT request image/avif", accept.contains("image/avif"))
        assertTrue("Accept header should request image/webp", accept.contains("image/webp"))
    }
}
