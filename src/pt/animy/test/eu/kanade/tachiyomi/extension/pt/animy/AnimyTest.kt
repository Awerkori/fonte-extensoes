package eu.kanade.tachiyomi.extension.pt.animy

import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimyTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Test
    fun testSlugify() {
        assertEquals(
            "genio-do-teletransporte-da-academia-de-magia",
            slugify("Gênio do Teletransporte da Academia de Magia"),
        )
        assertEquals(
            "a-estrela-das-streams-dos-monstros",
            slugify("A Estrela das Streams dos Monstros"),
        )
        assertEquals(
            "a-returner-s-magic-should-be-special",
            slugify("A Returner's Magic Should Be Special"),
        )
        assertEquals(
            "solo-leveling-ragnarok-season-2",
            slugify("Solo Leveling: Ragnarok (Season 2)"),
        )
    }

    @Test
    fun testFormatChapterNumber() {
        assertEquals("0", 0.0f.formatChapterNumber())
        assertEquals("1", 1.0f.formatChapterNumber())
        assertEquals("1.5", 1.5f.formatChapterNumber())
        assertEquals("100", 100.0f.formatChapterNumber())
        assertEquals("100.25", 100.25f.formatChapterNumber())
    }

    @Test
    fun testFormatChapterName() {
        assertEquals("Capítulo 1", formatChapterName(1.0f, null))
        assertEquals("Capítulo 1", formatChapterName(1.0f, ""))
        assertEquals("Capítulo 1", formatChapterName(1.0f, "Capítulo 1"))
        assertEquals("Capítulo 1", formatChapterName(1.0f, "Capítulo"))
        assertEquals("Capítulo 1", formatChapterName(1.0f, "Cap. 1"))
        assertEquals("Capítulo 1", formatChapterName(1.0f, "#1"))
        assertEquals("Capítulo 1", formatChapterName(1.0f, "1"))
        assertEquals("Capítulo 1 - Início da Jornada", formatChapterName(1.0f, "Início da Jornada"))
        assertEquals("Capítulo 1 - Início da Jornada", formatChapterName(1.0f, "Capítulo 1 - Início da Jornada"))
        assertEquals("Capítulo 1 - Início da Jornada", formatChapterName(1.0f, "Capítulo 1: Início da Jornada"))
        assertEquals("Prólogo", formatChapterName(0.0f, null))
        assertEquals("Prólogo", formatChapterName(0.0f, "Prólogo"))
        assertEquals("Prólogo - O Começo", formatChapterName(0.0f, "O Começo"))
        assertEquals("Capítulo 1.5 - Especial de Verão", formatChapterName(1.5f, "Capítulo 1.5 - Especial de Verão"))
    }

    @Test
    fun testIsNovel() {
        val novelItem = AnimyMangaItemDto(
            id = 20317,
            t = "Filho de Hades: A Linhagem do Monarca da Morte Novel",
            type = "novel",
        )
        assertTrue(novelItem.isNovel())

        val novelWithoutTypeInTitle = AnimyMangaItemDto(
            id = 16991,
            t = "Crônicas de um Deus Esquecido",
            type = "novel",
        )
        assertTrue(novelWithoutTypeInTitle.isNovel())

        val novelWithGenre = AnimyMangaItemDto(
            id = 20311,
            t = "Evolution From the Big Tree",
            type = null,
            g = listOf("Ação", "Novel"),
        )
        assertTrue(novelWithGenre.isNovel())

        val manhwaItem = AnimyMangaItemDto(
            id = 11830,
            t = "Gênio do Teletransporte da Academia de Magia",
            type = "manhwa",
        )
        assertFalse(manhwaItem.isNovel())

        val mangaItem = AnimyMangaItemDto(
            id = 7123,
            t = "Yuusha Party",
            type = "manga",
        )
        assertFalse(mangaItem.isNovel())

        val manhuaItem = AnimyMangaItemDto(
            id = 11050,
            t = "Pico Marcial",
            type = "manhua",
        )
        assertFalse(manhuaItem.isNovel())

        val recentNovel = AnimyRecentChapterDto(
            mangaId = 20317,
            mangaTitle = "Filho de Hades (Novel)",
        )
        assertTrue(recentNovel.isNovel())

        val recentNovelWithSuffix = AnimyRecentChapterDto(
            mangaId = 20318,
            mangaTitle = "Crônicas de um Deus Novel",
        )
        assertTrue(recentNovelWithSuffix.isNovel())

        val recentManga = AnimyRecentChapterDto(
            mangaId = 7123,
            mangaTitle = "Yuusha Party kara Oidasareta",
        )
        assertFalse(recentManga.isNovel())
    }

    @Test
    fun testMangaListDtoDeserialization() {
        val jsonStr = """
            {
              "items": [
                {
                  "id": 11830,
                  "t": "Gênio do Teletransporte",
                  "cover": "https://animy.net/files/cover.jpg",
                  "type": "manhwa",
                  "status": "hiatus",
                  "g": ["Ação", "Fantasia"]
                }
              ],
              "total": 120
            }
        """.trimIndent()

        val parsed = json.decodeFromString<AnimyMangaListDto>(jsonStr)
        assertEquals(120, parsed.total)
        assertEquals(1, parsed.items.size)
        val item = parsed.items.first()
        assertEquals(11830, item.id)
        assertEquals("Gênio do Teletransporte", item.t)
        assertEquals("https://animy.net/files/cover.jpg", item.cover)
        assertEquals("manhwa", item.type)
        assertEquals("hiatus", item.status)
        assertEquals(listOf("Ação", "Fantasia"), item.g)
    }

    @Test
    fun testMangaDetailDtoDeserialization() {
        val jsonStr = """
            {
              "id": 11830,
              "t": "Gênio do Teletransporte",
              "cover": "https://animy.net/files/cover.jpg",
              "desc": "Uma grande história.",
              "au": "Autor Top",
              "type": "manhwa",
              "status": "ongoing",
              "g": ["Ação"],
              "chapters": [
                {
                  "id": 303458,
                  "number": 1.0,
                  "date": "2026-08-09",
                  "title": "O Começo"
                },
                {
                  "id": 303459,
                  "number": 1.5,
                  "date": "2026-08-10"
                }
              ]
            }
        """.trimIndent()

        val parsed = json.decodeFromString<AnimyMangaDetailDto>(jsonStr)
        assertEquals(11830, parsed.id)
        assertEquals("Gênio do Teletransporte", parsed.t)
        assertEquals("Uma grande história.", parsed.desc)
        assertEquals("Autor Top", parsed.au)
        assertEquals(2, parsed.chapters.size)

        val ch1 = parsed.chapters[0]
        assertEquals(1.0f, ch1.number)
        assertEquals("O Começo", ch1.title)
        assertEquals("2026-08-09", ch1.date)

        val ch2 = parsed.chapters[1]
        assertEquals(1.5f, ch2.number)
        assertEquals(null, ch2.title)
    }

    @Test
    fun testRecentChapterDtoDeserialization() {
        val jsonStr = """
            [
              {
                "mangaId": 7123,
                "mangaTitle": "Yuusha Party kara Oidasareta",
                "mangaCover": "https://animy.net/files/7123.jpg",
                "number": 26.0,
                "date": "2026-10-26",
                "language": "pt-BR"
              },
              {
                "mangaId": 8986,
                "mangaTitle": "Bijutsubu Girl",
                "mangaCover": null,
                "number": 96.0,
                "date": null,
                "language": null
              }
            ]
        """.trimIndent()

        val parsed = json.decodeFromString<List<AnimyRecentChapterDto>>(jsonStr)
        assertEquals(2, parsed.size)
        assertEquals(7123, parsed[0].mangaId)
        assertEquals("Yuusha Party kara Oidasareta", parsed[0].mangaTitle)
        assertEquals("https://animy.net/files/7123.jpg", parsed[0].mangaCover)
        assertEquals(26.0f, parsed[0].number)
        assertEquals("2026-10-26", parsed[0].date)
        assertEquals("pt-BR", parsed[0].language)

        assertEquals(8986, parsed[1].mangaId)
        assertEquals(null, parsed[1].mangaCover)
        assertEquals(null, parsed[1].date)
    }

    @Test
    fun testChapterPagesDtoDeserialization() {
        val jsonStr = """
            {
              "id": 303458,
              "number": 1.0,
              "pages": [
                {
                  "number": 1,
                  "imageUrl": "https://animy.net/files/1.avif"
                },
                {
                  "number": 2,
                  "imageUrl": "https://animy.net/files/2.avif"
                }
              ]
            }
        """.trimIndent()

        val parsed = json.decodeFromString<AnimyChapterPagesDto>(jsonStr)
        assertEquals(303458, parsed.id)
        assertEquals(1.0f, parsed.number)
        assertEquals(2, parsed.pages?.size)
        assertEquals("https://animy.net/files/1.avif", parsed.pages?.get(0)?.imageUrl)
        assertEquals("https://animy.net/files/2.avif", parsed.pages?.get(1)?.imageUrl)
    }

    @Test
    fun testChapterPagesDtoWithNullPagesForNovel() {
        val jsonStr = """
            {
              "id": 529358,
              "number": 1.0,
              "pages": null,
              "text": "<p>Texto da novel...</p>"
            }
        """.trimIndent()

        val parsed = json.decodeFromString<AnimyChapterPagesDto>(jsonStr)
        assertEquals(529358, parsed.id)
        assertEquals(1.0f, parsed.number)
        assertTrue(parsed.pages.isNullOrEmpty())
    }

    @Test
    fun testFilters() {
        val filters = AnimyFilters.get()
        assertEquals("popular", filters.valueOrEmpty<SortFilter>())
        assertEquals("", filters.valueOrEmpty<TypeFilter>())
        assertEquals("", filters.valueOrEmpty<StatusFilter>())
        assertEquals("", filters.valueOrEmpty<DemographicFilter>())
        assertEquals("", filters.valueOrEmpty<ContentRatingFilter>())
        assertTrue(filters.selectedGenres().isEmpty())

        // Verify TypeFilter options do NOT contain "novel"
        val typeFilter = filters.find { it is TypeFilter } as TypeFilter
        for (i in 0 until typeFilter.values.size) {
            typeFilter.state = i
            assertFalse(typeFilter.getValue().equals("novel", ignoreCase = true))
        }

        // Check genre selection
        val genreFilter = filters.find { it is GenreFilter } as GenreFilter
        genreFilter.state.first { it.name == "Ação" }.state = true
        genreFilter.state.first { it.name == "Fantasia" }.state = true

        val selected = filters.selectedGenres()
        assertEquals(listOf("Ação", "Fantasia"), selected)
    }
}
