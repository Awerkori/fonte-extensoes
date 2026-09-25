package eu.kanade.tachiyomi.extension.pt.pointzerotoons

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.jsoup.Jsoup

class PointZeroSearchTest {
    private fun request(query: String, page: Int = 1, order: String = "update") = pointZeroSearchUrl(
        "https://kitsuneyako.com/manga/".toHttpUrl().newBuilder().apply {
            if (query.isNotEmpty()) addQueryParameter("title", query)
            addQueryParameter("page", page.toString())
            addQueryParameter("order", order)
        },
        page,
    ).build()

    @Test fun usesInkraSearchParameterForRealQueries() {
        for (query in listOf("Sobrevivendo no Jogo como um Bárbaro", "Bárbaro", "bárbaro", "BARBARO", "Jogo", "  Bárbaro  ", "zzznoxsemresultado98765")) {
            val url = request(query)
            assertEquals(query, url.queryParameter("s"))
            assertNull(url.queryParameter("title"))
            assertEquals("/manga/", url.encodedPath)
        }
    }

    @Test fun encodesAccentsAndReservedCharactersExactlyOnce() {
        val url = request("Bárbaro & +?#")
        assertEquals("Bárbaro & +?#", url.queryParameter("s"))
        assertTrue(url.toString().contains("B%C3%A1rbaro%20%26%20%2B%3F%23"))
        assertFalse(url.toString().contains("%25C3"))
        assertNull(url.fragment)
    }

    @Test fun preservesSearchOnNextPage() {
        val url = request("Jogo", 2)
        assertEquals("/manga/page/2/", url.encodedPath)
        assertEquals("Jogo", url.queryParameter("s"))
        assertNull(url.queryParameter("page"))
        assertNull(url.queryParameter("title"))
    }

    @Test fun keepsCatalogPopularLatestAndFiltersUnchanged() {
        assertEquals("https://kitsuneyako.com/manga/?page=1&order=popular", request("", order = "popular").toString())
        assertEquals("https://kitsuneyako.com/manga/?page=1&order=updated", request("").toString())
        assertEquals("https://kitsuneyako.com/manga/page/2/?order=updated", request("", 2).toString())
        val input = "https://kitsuneyako.com/manga/?title=B%C3%A1rbaro&genre=acao&status=ongoing&type=manhwa".toHttpUrl()
        val result = pointZeroSearchUrl(input.newBuilder(), 1).build()
        for (key in listOf("genre", "status", "type")) assertEquals(input.queryParameter(key), result.queryParameter(key))
    }

    private fun fixture(name: String) = Jsoup.parse(
        checkNotNull(javaClass.getResourceAsStream("/inkra/$name.html")).bufferedReader().use { it.readText() },
        "https://kitsuneyako.com/manga/",
    )

    @Test fun parsesLivePartialSearchCards() {
        val document = fixture("partial")
        val cards = parsePointZeroCards(document)
        assertEquals(13, cards.size)
        assertEquals("Sobrevivendo no Jogo como um Bárbaro", cards.first().title)
        assertEquals("https://kitsuneyako.com/manga/sobrevivendo-no-jogo-como-um-barbaro/", cards.first().url)
        assertEquals("https://kitsuneyako.com/wp-content/uploads/2026/09/capa-20-480x680.webp", cards.first().thumbnail)
        assertFalse(hasPointZeroNextPage(document))
    }

    @Test fun parsesLiveExactSearch() {
        val cards = parsePointZeroCards(fixture("exact"))
        assertEquals(2, cards.size)
        assertEquals("Sobrevivendo no Jogo como um Bárbaro", cards.first().title)
    }

    @Test fun liveEmptySearchHasNoCardsOrNextPage() {
        val document = fixture("empty")
        assertTrue(parsePointZeroCards(document).isEmpty())
        assertFalse(hasPointZeroNextPage(document))
    }

    @Test fun livePaginationMatchesSite() {
        val document = fixture("page1")
        assertEquals(24, parsePointZeroCards(document).size)
        assertTrue(hasPointZeroNextPage(document))
        assertEquals("https://kitsuneyako.com/manga/page/2/?s=Jogo", document.selectFirst("a.next.page-numbers")!!.absUrl("href"))
    }

    @Test fun liveSecondPageHasDifferentResultsAndKeepsQuery() {
        val first = parsePointZeroCards(fixture("page1")).map { it.url }.toSet()
        val document = fixture("page2")
        val second = parsePointZeroCards(document).map { it.url }.toSet()
        assertEquals(24, second.size)
        assertTrue(first.intersect(second).isEmpty())
        assertTrue(hasPointZeroNextPage(document))
        assertEquals("https://kitsuneyako.com/manga/page/3/?s=Jogo", document.selectFirst("a.next.page-numbers")!!.absUrl("href"))
    }
}
