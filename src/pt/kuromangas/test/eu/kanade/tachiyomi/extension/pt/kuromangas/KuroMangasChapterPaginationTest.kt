package eu.kanade.tachiyomi.extension.pt.kuromangas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KuroMangasChapterPaginationTest {
    @Test
    fun supportedBatchSizesAreComplete() {
        for (total in listOf(40, 60, 61, 150)) {
            val pages = (1..total).chunked(60).mapIndexed { index, ids ->
                chapterPage(index + 1, (total + 59) / 60, ids)
            }
            val result = collectChapterPages(pages.first()) { page -> pages.getOrNull(page - 1) }
            assertEquals(total, result.size)
        }
    }

    @Test
    fun boundaryDuplicatesAreRemovedById() {
        val pages = listOf(
            chapterPage(1, 3, (150 downTo 91).toList()),
            chapterPage(2, 3, (91 downTo 31).toList()),
            chapterPage(3, 3, (30 downTo 1).toList()),
        )

        val result = collectChapterPages(pages.first()) { page -> pages.getOrNull(page - 1) }

        assertEquals(150, result.size)
        assertEquals(listOf(150, 91, 90, 1), listOf(result.first().id, result[59].id, result[60].id, result.last().id))
    }

    @Test
    fun repeatedPageStopsWithoutLoop() {
        val first = chapterPage(1, 3, (60 downTo 1).toList())
        val calls = mutableListOf<Int>()

        val result = collectChapterPages(first) { page ->
            calls += page
            first
        }

        assertEquals(listOf(2), calls)
        assertEquals(60, result.size)
    }

    @Test
    fun emptyPageStopsAndKeepsPreviousChapters() {
        val first = chapterPage(1, 3, (60 downTo 1).toList())
        val empty = chapterPage(2, 3, emptyList())

        val result = collectChapterPages(first) { page -> if (page == 2) empty else null }

        assertEquals(60, result.size)
    }

    @Test
    fun decimalsSpecialsAndServerOrderArePreserved() {
        val values = listOf("10.5", "10.1", "Especial", "Prólogo")
        val result = collectChapterPages(chapterPage(1, 1, values.indices.toList()) { id -> values[id] }) {
            error("unexpected page $it")
        }

        assertEquals(values, result.map { it.chapterNumber })
        assertTrue(result.map { it.id }.distinct().size == values.size)
    }

    private fun chapterPage(page: Int, totalPages: Int, ids: List<Int>) =
        ChapterListResponse(
            chapters = ids.map { id -> ChapterDto(id = id, chapterNumber = id.toString()) },
            pagination = PaginationDto(page = page, limit = 60, totalPages = totalPages),
        )

    private fun chapterPage(page: Int, totalPages: Int, ids: List<Int>, number: (Int) -> String) =
        ChapterListResponse(
            chapters = ids.map { id -> ChapterDto(id = id, chapterNumber = number(id)) },
            pagination = PaginationDto(page = page, limit = 60, totalPages = totalPages),
        )

}
