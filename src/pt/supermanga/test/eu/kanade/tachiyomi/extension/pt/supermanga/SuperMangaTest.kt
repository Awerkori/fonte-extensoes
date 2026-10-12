package eu.kanade.tachiyomi.extension.pt.supermanga

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class SuperMangaTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun testSlugify() {
        assertEquals("one-punch-man", slugify("One Punch-Man"))
        assertEquals("dungeon-eul-geurineun-hwaga", slugify("Dungeon eul Geurineun Hwaga"))
        assertEquals("nao-e-possivel", slugify("Não É Possível!"))
        assertEquals("test-title", slugify("  ---Test... Title--- "))
    }

    @Test
    fun testFormatChapterName() {
        assertEquals("Capítulo 1 — Um Soco", formatChapterName(1.0, "Um Soco"))
        assertEquals("Capítulo 219 — Novo", formatChapterName(219.0, "Novo"))
        assertEquals("Capítulo 8.1 — 200 Yens", formatChapterName(8.1, "200 Yens"))
        assertEquals("Capítulo 166.5 — Extra", formatChapterName(166.5, "Extra"))
        assertEquals("Capítulo 1", formatChapterName(1.0, null))
        assertEquals("Capítulo 1", formatChapterName(1.0, ""))
        assertEquals("Capítulo 1 — A vez do Ikezawa-kun", formatChapterName(1.0, "- A vez do Ikezawa-kun ・"))
    }

    @Test
    fun testFormatChapterNumber() {
        assertEquals("1", formatChapterNumber(1.0))
        assertEquals("219", formatChapterNumber(219.0))
        assertEquals("8.1", formatChapterNumber(8.1))
        assertEquals("166.5", formatChapterNumber(166.5))
    }

    @Test
    fun testParseCatalogJson() {
        val payload = """
            {
              "items": [
                {
                  "id": 9272,
                  "t": "One Punch-Man",
                  "y": 2012,
                  "g": ["Action", "Comedy"],
                  "age": 14,
                  "cover": "https://supermanga.net/files/2KlZ92SOTI2PI_Hz-OEp8w/cover",
                  "desc": "Saitama...",
                  "status": "ongoing",
                  "au": "ONE",
                  "type": "manga"
                }
              ],
              "total": 1
            }
        """.trimIndent()

        val catalog = json.decodeFromString<CatalogResponseDto>(payload)
        assertEquals(1, catalog.items.size)
        val item = catalog.items[0]
        assertEquals(9272L, item.id)
        assertEquals("One Punch-Man", item.t)
        assertEquals("https://supermanga.net/files/2KlZ92SOTI2PI_Hz-OEp8w/cover", item.cover)
        assertEquals("ongoing", item.status)
        assertEquals("ONE", item.au)
    }

    @Test
    fun testParseMangaDetailsJson() {
        val payload = """
            {
              "id": 9272,
              "t": "One Punch-Man",
              "y": 2012,
              "g": ["Action", "Comedy"],
              "cover": "https://supermanga.net/files/cover.jpg",
              "desc": "Sinopse do manga",
              "status": "ongoing",
              "au": "ONE",
              "chapters": [
                {
                  "id": 182163,
                  "number": 1,
                  "title": "Um Soco",
                  "date": "2018-04-30",
                  "language": "pt-br"
                },
                {
                  "id": 182198,
                  "number": 8.1,
                  "title": "200 Yens",
                  "date": "2018-04-30",
                  "language": "pt-br"
                }
              ]
            }
        """.trimIndent()

        val details = json.decodeFromString<MangaDetailsDto>(payload)
        assertEquals(9272L, details.id)
        assertEquals(2, details.chapters.size)

        val ch1 = details.chapters[0]
        assertEquals(1.0, ch1.number, 0.001)
        assertEquals("Um Soco", ch1.title)
        assertEquals("2018-04-30", ch1.date)

        val ch81 = details.chapters[1]
        assertEquals(8.1, ch81.number, 0.001)
        assertEquals("200 Yens", ch81.title)
    }
}
