package eu.kanade.tachiyomi.extension.pt.mangaverde

import kotlinx.serialization.json.Json
import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaVerdeTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    @Test
    fun testCleanChapterName_Standard() {
        assertEquals("Cap. 120", cleanChapterName(120.0, "Capítulo 120"))
        assertEquals("Cap. 119", cleanChapterName(119.0, "Capítulo 119"))
        assertEquals("Cap. 10.5", cleanChapterName(10.5, "Capítulo 10.5"))
        assertEquals("Cap. 0", cleanChapterName(0.0, "Capítulo 0"))
        assertEquals("Cap. 184", cleanChapterName(184.0, null))
        assertEquals("Cap. 5", cleanChapterName(5.0, ""))
    }

    @Test
    fun testCleanChapterName_SpecialAndBadges() {
        assertEquals("Prólogo", cleanChapterName(0.01, "Prólogo"))
        assertEquals("Especial", cleanChapterName(null, "Especial"))
        assertEquals("One-shot", cleanChapterName(null, "One-shot"))

        // Site badges stripped
        assertEquals("Cap. 217", cleanChapterName(217.0, "Capítulo 217 \n NOVO"))
        assertEquals("Cap. 190", cleanChapterName(190.0, "Capítulo 190: Capítulo 190"))
        assertEquals("Cap. 50 - O Despertar", cleanChapterName(50.0, "Capítulo 50 - O Despertar"))
        assertEquals("Cap. 3 - nano-machine", cleanChapterName(3.0, "Capitulo 3 - nano-machine"))
    }

    @Test
    fun testCleanCoverUrl() {
        val relUrl = "//picture.mangalivre.org/temp/cover.jpg"
        assertEquals("https://picture.mangalivre.org/temp/cover.jpg", cleanCoverUrl(relUrl))

        val directUrl = "https://uploads.mangadex.org/covers/test.jpg"
        assertEquals(directUrl, cleanCoverUrl(directUrl))

        val ampUrl = "https://picture.mangalivre.org/temp/cover.jpg?w=100&amp;q=75"
        assertEquals("https://picture.mangalivre.org/temp/cover.jpg?w=100&q=75", cleanCoverUrl(ampUrl))

        val spaceUrl = "https://picture.mangalivre.org/temp/cover with spaces.jpg"
        assertEquals("https://picture.mangalivre.org/temp/cover%20with%20spaces.jpg", cleanCoverUrl(spaceUrl))

        assertNull(cleanCoverUrl(null))
        assertNull(cleanCoverUrl(""))
    }

    @Test
    fun testFormatTypeName() {
        assertEquals("Mangá", formatTypeName("MANGA"))
        assertEquals("Manhwa", formatTypeName("MANHWA"))
        assertEquals("Manhua", formatTypeName("MANHUA"))
        assertEquals("Comic / HQ", formatTypeName("COMIC"))
        assertEquals("Outro", formatTypeName("Outro"))
    }

    @Test
    fun testParseIsoDate() {
        val ts1 = parseIsoDate("2026-08-27T16:49:23.537Z")
        assertTrue("Parsed timestamp must be positive", ts1 > 0L)

        val ts2 = parseIsoDate("2026-10-27T12:00:00.000Z")
        assertTrue("Later date must have higher timestamp", ts2 > ts1)

        assertEquals(0L, parseIsoDate(null))
        assertEquals(0L, parseIsoDate(""))
        assertEquals(0L, parseIsoDate("data invalida"))
    }

    @Test
    fun testBuildMangaVerdeImageRequest_MangaDex() {
        val baseUrl = "https://mangaverde.net"
        val mdUrl = "https://cmdxd98sb0x3yprd.mangadex.network/data/b608/1.jpg"
        val baseHeaders = Headers.Builder().set("Origin", baseUrl).build()

        val req = buildMangaVerdeImageRequest(mdUrl, baseUrl, baseHeaders)

        assertEquals(mdUrl, req.url.toString())
        assertEquals("https://mangadex.org/", req.header("Referer"))
        assertNull("Origin must be stripped to prevent CDN issues", req.header("Origin"))
        assertEquals("cross-site", req.header("Sec-Fetch-Site"))
        assertEquals("image", req.header("Sec-Fetch-Dest"))
        assertEquals("no-cors", req.header("Sec-Fetch-Mode"))
    }

    @Test
    fun testBuildMangaVerdeImageRequest_MangaVerde() {
        val baseUrl = "https://mangaverde.net"
        val mvUrl = "https://mangaverde.net/uploads/page1.jpg"

        val req = buildMangaVerdeImageRequest(mvUrl, baseUrl)

        assertEquals(mvUrl, req.url.toString())
        assertEquals("https://mangaverde.net/", req.header("Referer"))
        assertEquals("same-site", req.header("Sec-Fetch-Site"))
    }

    @Test
    fun testBuildMangaVerdeImageRequest_MangaRead() {
        val baseUrl = "https://mangaverde.net"
        val mrUrl = "https://www.mangaread.org/wp-content/uploads/page1.jpeg"

        val req = buildMangaVerdeImageRequest(mrUrl, baseUrl)

        assertEquals(mrUrl, req.url.toString())
        assertEquals("https://www.mangaread.org/", req.header("Referer"))
        assertEquals("cross-site", req.header("Sec-Fetch-Site"))
    }

    @Test
    fun testBuildMangaVerdeImageRequest_OtherCdn() {
        val baseUrl = "https://mangaverde.net"
        val mlUrl = "https://picture.mangalivre.org/chapters/1.jpg"

        val req = buildMangaVerdeImageRequest(mlUrl, baseUrl)

        assertEquals(mlUrl, req.url.toString())
        assertNull("External CDNs must not receive mangaverde Referer", req.header("Referer"))
        assertEquals("cross-site", req.header("Sec-Fetch-Site"))
    }

    @Test
    fun testDtoParsing_MangaListWithRealApiStructures() {
        // Real API structure: genres are relation objects with nested genre, chapters in list have only chapterNumber
        val rawJson = """
            {
                "mangas": [
                    {
                        "id": "b109f403-6df6-4844-94f5-fd92627717c8",
                        "title": "Shijou Saikyou no Deshi Kenichi",
                        "alternativeTitles": "History's Strongest Disciple Kenichi",
                        "slug": "shijou-saikyou-no-deshi-kenichi",
                        "coverUrl": "https://picture.mangalivre.org/temp/cover.jpg",
                        "type": "MANGA",
                        "status": "COMPLETED",
                        "isNsfw": false,
                        "isUncensored": false,
                        "isFeatured": false,
                        "language": "pt-br",
                        "rating": 9.5,
                        "views": 50000,
                        "author": "Matsuena Syun",
                        "artist": "Matsuena Syun",
                        "releaseYear": 2002,
                        "genres": [
                            {
                                "mangaId": "b109f403-6df6-4844-94f5-fd92627717c8",
                                "genreId": "27902604-53ef-46ef-a7b0-926350759eee",
                                "genre": {
                                    "id": "27902604-53ef-46ef-a7b0-926350759eee",
                                    "name": "Drama",
                                    "slug": "drama",
                                    "isNsfw": false
                                }
                            },
                            {
                                "mangaId": "b109f403-6df6-4844-94f5-fd92627717c8",
                                "genreId": "54330c75-02ea-4dab-84c7-9dbfd6a111fe",
                                "genre": {
                                    "id": "54330c75-02ea-4dab-84c7-9dbfd6a111fe",
                                    "name": "Action",
                                    "slug": "action",
                                    "isNsfw": false
                                }
                            }
                        ],
                        "genresList": ["Drama", "Action"],
                        "chapters": [
                            {
                                "chapterNumber": 500
                            }
                        ],
                        "latestChapter": 500
                    }
                ],
                "total": 7462,
                "page": 1,
                "limit": 24,
                "totalPages": 311
            }
        """.trimIndent()

        val listDto = json.decodeFromString<MangaVerdeListDto>(rawJson)
        assertEquals(1, listDto.mangas.size)
        assertEquals(7462, listDto.total)
        assertEquals(1, listDto.page)
        assertEquals(311, listDto.totalPages)

        val manga = listDto.mangas[0]
        assertEquals("Shijou Saikyou no Deshi Kenichi", manga.title)
        assertEquals("shijou-saikyou-no-deshi-kenichi", manga.slug)
        assertEquals(2, manga.genres.size)
        assertEquals("Drama", manga.genres[0].genre?.name)
        assertEquals(1, manga.chapters.size)
        assertNull("Chapter id in list response should be null without causing crash", manga.chapters[0].id)
        assertEquals(500.0, manga.chapters[0].chapterNumber)
    }

    @Test
    fun testDtoParsing_MangaDetailWithChapters() {
        val rawJson = """
            {
                "id": "b109f403-6df6-4844-94f5-fd92627717c8",
                "title": "Shijou Saikyou no Deshi Kenichi",
                "alternativeTitles": "Kenichi",
                "slug": "shijou-saikyou-no-deshi-kenichi",
                "description": "História de Shirahama Kenichi.",
                "coverUrl": "https://picture.mangalivre.org/temp/cover.jpg",
                "type": "MANGA",
                "status": "COMPLETED",
                "isNsfw": false,
                "language": "pt-br",
                "rating": 9.5,
                "author": "Matsuena Syun",
                "artist": "Matsuena Syun",
                "releaseYear": 2002,
                "genres": [
                    {
                        "mangaId": "b109f403-6df6-4844-94f5-fd92627717c8",
                        "genreId": "27902604-53ef-46ef-a7b0-926350759eee",
                        "genre": {
                            "id": "27902604-53ef-46ef-a7b0-926350759eee",
                            "name": "Drama",
                            "slug": "drama",
                            "isNsfw": false
                        }
                    },
                    {
                        "mangaId": "b109f403-6df6-4844-94f5-fd92627717c8",
                        "genreId": "54330c75-02ea-4dab-84c7-9dbfd6a111fe",
                        "genre": {
                            "id": "54330c75-02ea-4dab-84c7-9dbfd6a111fe",
                            "name": "Action",
                            "slug": "action",
                            "isNsfw": false
                        }
                    }
                ],
                "chapters": [
                    {
                        "id": "8d1a8161-9fe5-4dfd-b9ba-70a87c81b47f",
                        "chapterNumber": 500,
                        "chapterTitle": "Capítulo 500",
                        "language": "pt-br",
                        "releaseDate": "2020-08-08T18:26:01.000Z",
                        "views": 394
                    }
                ]
            }
        """.trimIndent()

        val mangaDto = json.decodeFromString<MangaVerdeMangaDto>(rawJson)
        assertEquals("b109f403-6df6-4844-94f5-fd92627717c8", mangaDto.id)
        assertEquals("Shijou Saikyou no Deshi Kenichi", mangaDto.title)
        assertEquals(1, mangaDto.chapters.size)
        assertEquals("8d1a8161-9fe5-4dfd-b9ba-70a87c81b47f", mangaDto.chapters[0].id)

        val ch = mangaDto.chapters[0]
        assertEquals("/api/chapters/8d1a8161-9fe5-4dfd-b9ba-70a87c81b47f", "/api/chapters/${ch.id}")
        assertEquals("Cap. 500", cleanChapterName(ch.chapterNumber, ch.chapterTitle))
        val ts = parseIsoDate(ch.releaseDate)
        assertTrue(ts > 0L)
    }

    @Test
    fun testDtoParsing_ChapterDetail() {
        val rawJson = """
            {
                "id": "82a143ca-a802-4e16-8013-58d64c242203",
                "mangaId": "79b41060-e037-4dae-b48a-1ff34c8c5b25",
                "chapterNumber": 411,
                "chapterTitle": "Capítulo 411",
                "language": "pt-br",
                "pages": [
                    "https://picture.mangalivre.org/chapters/411/1.jpg",
                    "https://picture.mangalivre.org/chapters/411/2.jpg"
                ],
                "releaseDate": "2026-08-27T16:49:23.537Z"
            }
        """.trimIndent()

        val detail = json.decodeFromString<MangaVerdeChapterDetailDto>(rawJson)
        assertEquals("82a143ca-a802-4e16-8013-58d64c242203", detail.id)
        assertEquals(2, detail.pages.size)
        assertEquals("https://picture.mangalivre.org/chapters/411/1.jpg", detail.pages[0])
    }

    @Test
    fun testFilters_NsfwToggle() {
        val filtersWithoutNsfw = MangaVerdeFilters.get(null, showNsfw = false)
        assertFalse(
            "AgeFilter must NOT be present when showNsfw is false",
            filtersWithoutNsfw.any { it is AgeFilter },
        )

        val filtersWithNsfw = MangaVerdeFilters.get(null, showNsfw = true)
        assertTrue(
            "AgeFilter MUST be present when showNsfw is true",
            filtersWithNsfw.any { it is AgeFilter },
        )
    }

    @Test
    fun testFilters_ParseDynamicGenres() {
        val rawJson = """
            [
                {
                    "id": "df9a18ba-7d0c-4fe8-8128-001fdfe95247",
                    "name": "Romance",
                    "slug": "romance",
                    "count": 5917
                },
                {
                    "id": "54330c75-02ea-4dab-84c7-9dbfd6a111fe",
                    "name": "Ação",
                    "slug": "action",
                    "count": 4820
                }
            ]
        """.trimIndent()

        val jsonElement = json.parseToJsonElement(rawJson)
        val filterList = MangaVerdeFilters.get(jsonElement, showNsfw = false)
        val genreFilter = filterList.find { it is GenreFilter } as? GenreFilter
        assertNotNull("GenreFilter must be present", genreFilter)
    }
}
