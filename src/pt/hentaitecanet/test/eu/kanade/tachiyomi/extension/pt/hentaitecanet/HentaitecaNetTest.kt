package eu.kanade.tachiyomi.extension.pt.hentaitecanet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HentaitecaNetTest {

    @Test
    fun testParseDateRelative() {
        val now = System.currentTimeMillis()

        val threeHours = parseDate("3 horas ago")
        assertTrue("3 hours ago should be in the past", threeHours > 0L && threeHours <= now)

        val oneHour = parseDate("1 hora ago")
        assertTrue("1 hour should be after 3 hours", oneHour > threeHours)

        val fortyFiveMins = parseDate("45 mins ago")
        assertTrue("45 mins should be after 1 hour", fortyFiveMins > oneHour)

        val oneDay = parseDate("1 dia atrás")
        assertTrue("1 day should be before 3 hours", oneDay < threeHours)

        val twoDays = parseDate("2 dias ago")
        assertTrue("2 days should be before 1 day", twoDays < oneDay)

        val oneWeek = parseDate("1 semana ago")
        assertTrue("1 week should be before 2 days", oneWeek < twoDays)

        val oneMonth = parseDate("1 mês ago")
        assertTrue("1 month should be before 1 week", oneMonth < oneWeek)

        val oneYear = parseDate("1 ano ago")
        assertTrue("1 year should be before 1 month", oneYear < oneMonth)

        assertEquals(0L, parseDate(null))
        assertEquals(0L, parseDate(""))
        assertEquals(0L, parseDate("   "))
        assertEquals(0L, parseDate("desconhecido"))
    }

    @Test
    fun testParseDateAbsolute() {
        val date1 = parseDate("22/12/2024")
        assertTrue("22/12/2024 parsed timestamp should be positive", date1 > 0L)

        val date2 = parseDate("16/10/2026")
        assertTrue("16/10/2026 should be after 22/12/2024", date2 > date1)

        val isoDate = parseDate("2025-01-15")
        assertTrue("2025-01-15 timestamp should be positive", isoDate > 0L)
    }

    @Test
    fun testExtractChapterNumber() {
        assertEquals(55f, extractChapterNumber("Capítulo 55", "/obra/foo/capitulo-55/"), 0.001f)
        assertEquals(54f, extractChapterNumber("Capítulo 54", "/obra/foo/capitulo-54/"), 0.001f)
        assertEquals(1.5f, extractChapterNumber("Capítulo 1.5", "/obra/foo/capitulo-1-5/"), 0.001f)
        assertEquals(0f, extractChapterNumber("Capítulo 0", "/obra/foo/capitulo-0/"), 0.001f)
        assertEquals(104f, extractChapterNumber("Cap. 104", ""), 0.001f)
        assertEquals(12f, extractChapterNumber("", "/obra/foo/capitulo-12/"), 0.001f)
    }

    @Test
    fun testCleanThumbnail() {
        assertEquals(
            "https://hentaiteca.net/wp-content/uploads/2026/10/ler-The-Professors-Secret-Account-hentai-online.jpg",
            cleanThumbnail("https://hentaiteca.net/wp-content/uploads/2026/10/ler-The-Professors-Secret-Account-hentai-online-193x278.jpg"),
        )
        assertEquals(
            "https://hentaiteca.net/wp-content/uploads/2024/12/IMG_5660.jpeg",
            cleanThumbnail("https://hentaiteca.net/wp-content/uploads/2024/12/IMG_5660-220x300.jpeg"),
        )
        assertEquals(
            "https://hentaiteca.net/wp-content/uploads/2026/10/ler-cover.webp",
            cleanThumbnail("https://hentaiteca.net/wp-content/uploads/2026/10/ler-cover-386x556.webp"),
        )
        assertEquals(null, cleanThumbnail(null))
        assertEquals(null, cleanThumbnail(""))
        assertEquals(null, cleanThumbnail("   "))
    }

    @Test
    fun testFilters() {
        val filters = HentaitecaNetFilters.get()
        assertEquals(4, filters.size)

        val sort = filters[0] as SortFilter
        assertEquals("views", sort.getValue())

        val status = filters[1] as StatusFilter
        assertEquals("", status.getValue())

        val condition = filters[2] as GenreConditionFilter
        assertEquals("", condition.getValue())

        val genres = filters[3] as GenreFilter
        assertEquals(79, genres.state.size)
        assertEquals("Ação", genres.state[0].name)
        assertEquals("acao", genres.state[0].value)
    }
}
