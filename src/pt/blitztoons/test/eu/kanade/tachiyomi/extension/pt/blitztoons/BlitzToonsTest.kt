package eu.kanade.tachiyomi.extension.pt.blitztoons

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlitzToonsTest {

    private val baseUrl = "https://blitztoons.com"

    @Test
    fun imageRequestStrikesOriginAndSetsCdnHeaders() {
        val baseHeaders = Headers.Builder()
            .set("Origin", "https://blitztoons.com")
            .set("Referer", "https://blitztoons.com/")
            .build()

        val request = buildBlitzImageRequest(
            imageUrl = "https://cdn.blitztoons.com/covers/solsticio-a3b60c347712.webp",
            baseUrl = baseUrl,
            baseHeaders = baseHeaders,
        )

        assertNull("Image request must NOT contain Origin header (prevents CDN 403)", request.header("Origin"))
        assertEquals("https://blitztoons.com/", request.header("Referer"))
        assertEquals("image", request.header("Sec-Fetch-Dest"))
        assertEquals("no-cors", request.header("Sec-Fetch-Mode"))
        assertEquals("same-site", request.header("Sec-Fetch-Site"))
        assertTrue("Accept header must request image types", request.header("Accept")?.contains("image/") == true)
        assertEquals(BLITZTOONS_USER_AGENT, request.header("User-Agent"))
    }

    @Test
    fun resolveMangaUrlHandlesObraAndLerLinks() {
        val obraUrl = "https://blitztoons.com/obra/solsticio".toHttpUrl()
        assertEquals("/obra/solsticio", resolveMangaUrl(obraUrl))

        val lerUrl = "https://blitztoons.com/ler/solsticio/57".toHttpUrl()
        assertEquals("/obra/solsticio", resolveMangaUrl(lerUrl))

        val invalidUrl = "https://blitztoons.com/ranking".toHttpUrl()
        assertNull(resolveMangaUrl(invalidUrl))
    }

    @Test
    fun filtersContainExpectedValues() {
        val orderFilter = OrderFilter()
        assertEquals("recentes", orderFilter.selected)

        val typeFilter = TypeFilter()
        assertEquals("", typeFilter.selected)

        val statusFilter = StatusFilter()
        assertEquals("", statusFilter.selected)

        val genreFilter = GenreFilter()
        assertEquals("", genreFilter.selected)
    }

    @Test
    fun parsePageListExtractsImageUrls() {
        val html = """
            <div class="chapters" data-chapters>
                <section class="ch-block" data-block>
                    <div class="pages" data-pages-wrap>
                        <img class="page" src="https://cdn.blitztoons.com/chapters/421/57/001.webp?v=1792353996"
                             alt="Página 1" data-page="1">
                        <img class="page" src="https://cdn.blitztoons.com/chapters/421/57/002.webp?v=1792353996"
                             alt="Página 2" data-page="2">
                        <img class="page" src="https://cdn.blitztoons.com/chapters/421/57/003.webp?v=1792353996"
                             alt="Página 3" data-page="3">
                    </div>
                </section>
            </div>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://blitztoons.com/ler/solsticio/57")
        val pages = parsePageList(doc)

        assertEquals(3, pages.size)
        assertEquals(0, pages[0].index)
        assertEquals("https://cdn.blitztoons.com/chapters/421/57/001.webp?v=1792353996", pages[0].imageUrl)
        assertEquals(1, pages[1].index)
        assertEquals("https://cdn.blitztoons.com/chapters/421/57/002.webp?v=1792353996", pages[1].imageUrl)
        assertEquals(2, pages[2].index)
        assertEquals("https://cdn.blitztoons.com/chapters/421/57/003.webp?v=1792353996", pages[2].imageUrl)
    }

    @Test
    fun htmlSelectorsExtractExpectedValues() {
        val cardHtml = """
            <article class="card">
                <a class="card-cover" href="/obra/solsticio">
                    <img src="https://cdn.blitztoons.com/covers/solsticio-a3b60c347712.webp"
                         alt="Capa de Solstício" loading="lazy">
                    <span class="card-type card-type-comic">Comic</span>
                </a>
                <div class="card-body">
                    <h3 class="card-title"><a href="/obra/solsticio">Solstício</a></h3>
                    <p class="card-meta">57 cap. · há 17 min</p>
                </div>
            </article>
        """.trimIndent()

        val cardDoc = Jsoup.parse(cardHtml, baseUrl)
        val link = cardDoc.selectFirst("a.card-cover")
        assertNotNull(link)
        assertEquals("/obra/solsticio", link!!.attr("href"))

        val title = cardDoc.selectFirst("h3.card-title a")?.text()
        assertEquals("Solstício", title)

        val img = cardDoc.selectFirst("img")
        assertEquals("https://cdn.blitztoons.com/covers/solsticio-a3b60c347712.webp", img?.attr("src"))
    }
}
