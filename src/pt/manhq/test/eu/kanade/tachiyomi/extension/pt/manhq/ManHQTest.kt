package eu.kanade.tachiyomi.extension.pt.manhq

import eu.kanade.tachiyomi.source.model.SManga
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManHQTest {

    @Test
    fun testChapterNameStandard() {
        assertEquals("Cap. 120", formatChapterName(120f, "Capítulo 120", false))
        assertEquals("Cap. 119", formatChapterName(119f, "Capítulo 119", false))
        assertEquals("Cap. 0", formatChapterName(0f, "Capítulo 0", false))
    }

    @Test
    fun testChapterNameDecimal() {
        assertEquals("Cap. 10.5", formatChapterName(10.5f, "Capítulo 10.5", false))
        assertEquals("Cap. 108.1", formatChapterName(108.1f, "Capítulo 108.1", false))
        assertEquals("Cap. 0.5", formatChapterName(0.5f, "Capítulo 0.5", false))
    }

    @Test
    fun testChapterNameSpecialAndPrologue() {
        assertEquals("Prólogo", formatChapterName(0f, "Prólogo", false))
        assertEquals("Prólogo", formatChapterName(null, "prologo", false))
        assertEquals("Especial", formatChapterName(0f, "Especial", false))
        assertEquals("One-shot", formatChapterName(0f, "", true))
    }

    @Test
    fun testStatusMapping() {
        assertEquals(SManga.ONGOING, parseStatus("releasing"))
        assertEquals(SManga.ONGOING, parseStatus("ongoing"))
        assertEquals(SManga.COMPLETED, parseStatus("completed"))
        assertEquals(SManga.ON_HIATUS, parseStatus("hiatus"))
        assertEquals(SManga.CANCELLED, parseStatus("cancelled"))
        assertEquals(SManga.UNKNOWN, parseStatus("random_unknown"))
    }

    @Test
    fun testCoverUrlBuilder() {
        val apiUrl = "https://api.manhq.com.br"
        val url1 = buildCoverUrl(apiUrl, "/public/series/uuid-1/cover", "uuid-1")
        assertEquals("https://api.manhq.com.br/public/series/uuid-1/cover", url1)

        val url2 = buildCoverUrl(apiUrl, null, "uuid-2")
        assertEquals("https://api.manhq.com.br/public/series/uuid-2/cover", url2)

        val url3 = buildCoverUrl(apiUrl, "https://manhq.com.br/public/series/uuid-3/cover", "uuid-3")
        assertEquals("https://api.manhq.com.br/public/series/uuid-3/cover", url3)
    }

    @Test
    fun testFiltersList() {
        val filters = ManHQFilters.get()
        assertNotNull(filters)
        assertTrue(filters.any { it is ManHQFilters.FormatFilter })
        assertTrue(filters.any { it is ManHQFilters.StatusFilter })
        assertTrue(filters.any { it is ManHQFilters.SortFilter })
        assertTrue(filters.any { it is ManHQFilters.GenreGroup })

        val formatFilter = filters.first { it is ManHQFilters.FormatFilter } as ManHQFilters.FormatFilter
        assertEquals("", formatFilter.selected) // default "Todos"

        val sortFilter = filters.first { it is ManHQFilters.SortFilter } as ManHQFilters.SortFilter
        assertEquals("updated", sortFilter.selected) // default "Últimas atualizações"
    }

    @Test
    fun testSeriesDtoParsing() {
        val media = ManHQMediaDto(
            id = "test-media-1",
            title = "Capítulo 100",
            number = 100f,
            isReady = true,
            pageCount = 20,
        )
        val series = ManHQSeriesDto(
            id = "test-id-1",
            title = "Test Manga",
            description = "Uma descrição de teste",
            author = "Autor Teste",
            artist = "Artista Teste",
            status = "RELEASING",
            tags = "Manga, Action, Fantasy",
            coverUrl = "/public/series/test-id-1/cover",
            workType = "manga",
            medias = listOf(media),
        )

        assertEquals("test-id-1", series.id)
        assertEquals("Test Manga", series.title)
        assertEquals("manga", series.workType)
        assertEquals(1, series.medias.size)
        assertEquals(100f, series.medias[0].number)
    }

    @Test
    fun testChapterSortingDescending() {
        val m1 = ManHQMediaDto(id = "1", title = "Capítulo 1", number = 1f)
        val m2 = ManHQMediaDto(id = "2", title = "Capítulo 2", number = 2f)
        val m10 = ManHQMediaDto(id = "10", title = "Capítulo 10", number = 10f)
        val m0 = ManHQMediaDto(id = "0", title = "Capítulo 0", number = 0f)

        val list = listOf(m1, m2, m10, m0)
        val sorted = list.sortedWith(
            compareByDescending<ManHQMediaDto> { it.number ?: -1f }
                .thenByDescending { it.id },
        )

        assertEquals(10f, sorted[0].number)
        assertEquals(2f, sorted[1].number)
        assertEquals(1f, sorted[2].number)
        assertEquals(0f, sorted[3].number)
    }

    @Test
    fun testPaginationLogic() {
        val items = (1..50).map { i ->
            ManHQSeriesDto(
                id = "id-$i",
                title = "Manga $i",
            )
        }

        // Page 1 of 24
        val fromIndex1 = (1 - 1) * 24
        val toIndex1 = kotlin.math.min(fromIndex1 + 24, items.size)
        val page1 = items.subList(fromIndex1, toIndex1)
        assertEquals(24, page1.size)
        assertTrue(toIndex1 < items.size)

        // Page 2 of 24
        val fromIndex2 = (2 - 1) * 24
        val toIndex2 = kotlin.math.min(fromIndex2 + 24, items.size)
        val page2 = items.subList(fromIndex2, toIndex2)
        assertEquals(24, page2.size)
        assertTrue(toIndex2 < items.size)

        // Page 3 of 24 (remaining 2 items)
        val fromIndex3 = (3 - 1) * 24
        val toIndex3 = kotlin.math.min(fromIndex3 + 24, items.size)
        val page3 = items.subList(fromIndex3, toIndex3)
        assertEquals(2, page3.size)
        assertFalse(toIndex3 < items.size)
    }
}
