package eu.kanade.tachiyomi.extension.pt.hikaricomics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HikariComicsTest {

    @Test
    fun testParseIsoDate() {
        val iso = "2026-10-27T04:18:22.303Z"
        val parsed = parseIsoDate(iso)
        assertTrue("ISO date timestamp should be positive", parsed > 0L)

        // Null and blank handling
        assertEquals(0L, parseIsoDate(null))
        assertEquals(0L, parseIsoDate(""))
        assertEquals(0L, parseIsoDate("   "))
        assertEquals(0L, parseIsoDate("invalid-date-format"))
    }

    @Test
    fun testParseRelativeDate() {
        val now = System.currentTimeMillis()

        val oneHour = parseRelativeDate("1 hora atrás")
        assertTrue(oneHour > 0L && oneHour <= now)

        val twoHours = parseRelativeDate("2 horas atrás")
        assertTrue(twoHours > 0L && twoHours < oneHour)

        val tenMinutes = parseRelativeDate("10 minutos atrás")
        assertTrue(tenMinutes > 0L && tenMinutes > oneHour)

        val threeDays = parseRelativeDate("3 dias atrás")
        assertTrue(threeDays > 0L && threeDays < oneHour)

        val oneMonth = parseRelativeDate("1 mês atrás")
        assertTrue(oneMonth > 0L && oneMonth < threeDays)

        val oneYear = parseRelativeDate("1 ano atrás")
        assertTrue(oneYear > 0L && oneYear < oneMonth)

        assertEquals(0L, parseRelativeDate(null))
        assertEquals(0L, parseRelativeDate(""))
        assertEquals(0L, parseRelativeDate("data desconhecida"))
    }

    @Test
    fun testExtractChapterNumber() {
        assertEquals(53f, extractChapterNumber("capitulo-53"))
        assertEquals(53.5f, extractChapterNumber("capitulo-53.5"))
        assertEquals(141f, extractChapterNumber("capitulo-141"))
        assertEquals(1f, extractChapterNumber("capitulo-1"))
        assertEquals(0f, extractChapterNumber("capitulo-0"))
        assertEquals(12f, extractChapterNumber("Capítulo 12"))
        assertEquals(12.5f, extractChapterNumber("Capítulo 12.5"))
        assertEquals(7f, extractChapterNumber("Cap. 7"))
        assertEquals(99f, extractChapterNumber("99"))
    }

    @Test
    fun testFormatChapterNumber() {
        assertEquals("0", 0.0f.formatChapterNumber())
        assertEquals("1", 1.0f.formatChapterNumber())
        assertEquals("1.5", 1.5f.formatChapterNumber())
        assertEquals("53", 53.0f.formatChapterNumber())
        assertEquals("141", 141.0f.formatChapterNumber())
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
        assertEquals("Capítulo 53", formatChapterName(53.0f, "Capítulo 53"))
        assertEquals("Capítulo 53 - O Início", formatChapterName(53.0f, "O Início"))
        assertEquals("Capítulo 53 - O Início", formatChapterName(53.0f, "Capítulo 53 - O Início"))
        assertEquals("Capítulo 53 - O Início", formatChapterName(53.0f, "Capítulo 53: O Início"))
        assertEquals("Prólogo", formatChapterName(0.0f, null))
        assertEquals("Prólogo", formatChapterName(0.0f, "Prólogo"))
        assertEquals("Prólogo - Começo", formatChapterName(0.0f, "Começo"))
        assertEquals("Capítulo 1.5 - Especial", formatChapterName(1.5f, "Capítulo 1.5 - Especial"))
    }

    @Test
    fun testFilters() {
        val filters = HikariComicsFilters.get()
        assertEquals(3, filters.size)

        val sortFilter = filters[0] as SortFilter
        assertEquals("updated", sortFilter.selected)
        assertEquals(7, SortFilter.SORT_OPTIONS.size)

        // Select "Mais vistos" (views)
        sortFilter.state = 1
        assertEquals("views", sortFilter.selected)

        // Select "Mais recentes" (newest)
        sortFilter.state = 2
        assertEquals("newest", sortFilter.selected)

        val statusFilter = filters[1] as StatusFilter
        assertEquals("", statusFilter.selected)
        assertEquals(5, StatusFilter.STATUS_OPTIONS.size)
        statusFilter.state = 1
        assertEquals("Em lançamento", statusFilter.selected)

        val genreFilter = filters[2] as GenreFilter
        assertEquals("", genreFilter.selected)
        assertEquals(21, GenreFilter.GENRE_OPTIONS.size)
        genreFilter.state = 1
        assertEquals("ahegao", genreFilter.selected)
    }
}
