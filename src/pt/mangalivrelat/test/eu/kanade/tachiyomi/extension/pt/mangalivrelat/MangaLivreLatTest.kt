package eu.kanade.tachiyomi.extension.pt.mangalivrelat

import eu.kanade.tachiyomi.source.model.SManga
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class MangaLivreLatTest {

    @Test
    fun testCleanThumbnailUrl() {
        val input = "https://mangalivre.lat/wp-content/uploads/2026/10/ba8ffd74.jpg.512-350x500.jpg"
        val expected = "https://mangalivre.lat/wp-content/uploads/2026/10/ba8ffd74.jpg.512.jpg"
        assertEquals(expected, cleanThumbnailUrl(input))

        val thumb150 = "https://mangalivre.lat/wp-content/uploads/2026/09/image-150x150.png"
        val expected150 = "https://mangalivre.lat/wp-content/uploads/2026/09/image.png"
        assertEquals(expected150, cleanThumbnailUrl(thumb150))
    }

    @Test
    fun testFormatChapterName() {
        assertEquals("Cap. 14: Grande prêmio", formatChapterName("Capítulo 14: Grande prêmio"))
        assertEquals("Cap. 250", formatChapterName("Capítulo 250"))
        assertEquals("Cap. 10.5", formatChapterName("Capitulo 10.5"))
        assertEquals("Prólogo", formatChapterName("Prólogo"))
        assertEquals("Especial", formatChapterName("Especial"))
    }

    @Test
    fun testParseStatus() {
        assertEquals(SManga.ONGOING, parseStatus("Em Andamento"))
        assertEquals(SManga.ONGOING, parseStatus("Em Lançamento"))
        assertEquals(SManga.COMPLETED, parseStatus("Completo"))
        assertEquals(SManga.CANCELLED, parseStatus("Cancelado"))
        assertEquals(SManga.ON_HIATUS, parseStatus("Pausado"))
        assertEquals(SManga.ON_HIATUS, parseStatus("Hiato"))
        assertEquals(SManga.UNKNOWN, parseStatus("Desconhecido"))
    }

    @Test
    fun testParseRelativeDate() {
        val now = Calendar.getInstance().timeInMillis
        val weekAgo = parseRelativeDate("há 1 semana")
        assertTrue(weekAgo > 0L)
        assertTrue(weekAgo <= now)

        val hourAgo = parseRelativeDate("há 2 horas")
        assertTrue(hourAgo > 0L)
        assertTrue(hourAgo <= now)

        assertEquals(0L, parseRelativeDate(null))
        assertEquals(0L, parseRelativeDate(""))
    }

    @Test
    fun testParseMangaTitleFromSlug() {
        assertEquals("Yotaka Futatabi", parseMangaTitleFromSlug("https://mangalivre.lat/manga/yotaka-futatabi/"))
        assertEquals("A Dot Alice", parseMangaTitleFromSlug("https://mangalivre.lat/manga/a-dot-alice/"))
        assertEquals("The Beginning After The End", parseMangaTitleFromSlug("https://mangalivre.lat/manga/the-beginning-after-the-end/"))
    }

    @Test
    fun testParseDetailsHtml() {
        val sampleHtml = """
            <h1 class="manga-title">
                <div class="manga-language-flag" title="日本語"><img src="jp.png" alt="jp"></div>
                Yotaka Futatabi
            </h1>
            <div class="manga-cover">
                <img src="https://mangalivre.lat/cover-350x500.jpg" class="manga-cover-image" />
            </div>
            <div class="manga-synopsis">
                <div class="synopsis-content">
                    <p>Hitman Nighthawk retired life.</p>
                </div>
            </div>
            <div class="manga-tags">
                <span class="manga-tag">Action</span>
                <span class="manga-tag">Comedy</span>
            </div>
            <div class="manga-meta-grid">
                <div class="manga-meta-item">
                    <span class="meta-label">Status:</span>
                    <span class="meta-value">Em Andamento</span>
                </div>
                <div class="manga-meta-item">
                    <span class="meta-label">Autor:</span>
                    <span class="meta-value">Izumida Fuyuki</span>
                </div>
            </div>
        """.trimIndent()

        val doc = Jsoup.parse(sampleHtml)
        val titleEl = doc.selectFirst("h1.manga-title")
        assertNotNull(titleEl)
        val title = titleEl!!.ownText().trim()
        assertEquals("Yotaka Futatabi", title)

        val cover = cleanThumbnailUrl(doc.selectFirst(".manga-cover-image")!!.attr("src"))
        assertEquals("https://mangalivre.lat/cover.jpg", cover)

        val description = doc.selectFirst(".synopsis-content")?.text()?.trim()
        assertEquals("Hitman Nighthawk retired life.", description)

        val genre = doc.select(".manga-tags .manga-tag").joinToString { it.text().trim() }
        assertEquals("Action, Comedy", genre)

        val status = parseStatus(doc.select(".manga-meta-item:contains(Status:) .meta-value").text())
        assertEquals(SManga.ONGOING, status)
    }

    @Test
    fun testParseChapterHtml() {
        val sampleHtml = """
            <ul class="chapters-list">
                <li class="chapter-item">
                    <div class="chapter-details">
                        <a href="https://mangalivre.lat/capitulo/yotaka-futatabi-capitulo-14/" class="chapter-link">
                            <span class="chapter-number">Capítulo 14: Grande prêmio</span>
                        </a>
                        <span class="chapter-date">há 3 semanas</span>
                    </div>
                </li>
            </ul>
        """.trimIndent()

        val doc = Jsoup.parse(sampleHtml)
        val items = doc.select("ul.chapters-list li.chapter-item")
        assertEquals(1, items.size)
        val name = formatChapterName(items[0].selectFirst(".chapter-number")!!.text().trim())
        assertEquals("Cap. 14: Grande prêmio", name)
    }

    @Test
    fun testParseReaderHtml() {
        val sampleHtml = """
            <div class="chapter-images">
                <div class="chapter-image-container" id="page-1" data-page="1">
                    <img src="https://mangalivre.lat/page1.jpg" class="chapter-image" />
                </div>
                <div class="chapter-image-container" id="page-2" data-page="2">
                    <img src="https://mangalivre.lat/page2.jpg" class="chapter-image" />
                </div>
            </div>
        """.trimIndent()

        val doc = Jsoup.parse(sampleHtml)
        val images = doc.select(".chapter-image-container img").map { it.attr("src") }
        assertEquals(2, images.size)
        assertEquals("https://mangalivre.lat/page1.jpg", images[0])
        assertEquals("https://mangalivre.lat/page2.jpg", images[1])
    }
}
