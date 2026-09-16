package eu.kanade.tachiyomi.extension.pt.xxxyaoi

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class LayoutTest {
    private val root = "/bl/he-might-bite/"
    private fun document(html: String) = Jsoup.parse(html, "https://3xyaoi.com$root")
    private val chapter = """<li class="wp-manga-chapter"><a href="/bl/he-might-bite/capitulo-36/"><div><span class="xyaoi-chapter-name">Capítulo 36 - Fim 1ª Temporada</span></div></a><div class="xyaoi-chapter-date-line"><span>13/09/2026</span></div></li>"""
    private val card = """<article class="xyaoi-search-card" data-post-id="123"><img data-src="/cover.webp"><h3><a href="/bl/he-might-bite/">He Might Bite!</a></h3></article>"""

    private fun fixture(name: String) = javaClass.getResource("/$name")?.readText() ?: error("Missing fixture $name")

    @Test fun capturedChapterWithRandomClasses() {
        val html = fixture("live-chapter.html")
        for (variant in listOf(html, html.replace(Regex("class=\"[^\"]*\""), "").replace("<li", "<section").replace("</li>", "</section>"))) {
            val parsed = Layout.chapters(document(variant), root).single()
            assertEquals("Capítulo 36 - Fim 1ª Temporada", parsed.name)
            assertEquals("09/08/2026", parsed.date)
            assertEquals(root + "capitulo-36-fim-1a-temporada/", parsed.path)
        }
    }
    @Test fun capturedMetadataWithoutClasses() {
        val html = fixture("live-properties.html").replace(Regex("class=\"[^\"]*\""), "")
        assertEquals("EM HIATO", Layout.property(document(html), "Status")?.text())
        assertEquals("Autor A · Autor B", Layout.property(document(html), "Autor")?.text())
        assertEquals("Artista A", Layout.property(document(html), "Artista")?.text())
    }
    @Test fun relativeLinksInAjaxResponseUseMangaPath() {
        val doc = Jsoup.parse("<li><a href='capitulo-2/'>Capítulo 2</a></li>", "https://3xyaoi.com${root}ajax/chapters/")
        assertEquals(root + "capitulo-2/", Layout.chapters(doc, root).single().path)
    }
    @Test fun chapterUrlsPreserveLegacyAndMemoPaths() {
        val expected = "https://new.example${root}capitulo-1/?id=2"
        assertEquals(expected, Layout.chapterUrl("https://new.example", "https://old.example${root}capitulo-1/?style=paged&id=2", null, null))
        assertEquals(expected, Layout.chapterUrl("https://new.example", "capitulo-1", root, root + "capitulo-1/?id=2"))
        assertEquals("https://new.example${root}capitulo-1/", Layout.chapterUrl("https://new.example", "capitulo-1", root, null))
        assertThrows(IllegalStateException::class.java) { Layout.chapterUrl("https://new.example", "capitulo-1", null, null) }
    }
    @Test fun chapterNameAndDate() {
        val parsed = Layout.chapters(document(chapter), root).single()
        assertEquals("Capítulo 36 - Fim 1ª Temporada", parsed.name)
        assertEquals(root + "capitulo-36/", parsed.path)
        assertEquals("13/09/2026", parsed.date)
    }
    @Test fun renamedChapterClass() = assertEquals(1, Layout.chapters(document(chapter.replace("wp-manga-chapter", "renamed-row")), root).size)
    @Test fun replacedWrappersAndNameClass() {
        val html = chapter.replace("li", "section").replace("xyaoi-chapter-name", "changed").replace("wp-manga-chapter", "changed-row")
        assertEquals("Capítulo 36 - Fim 1ª Temporada", Layout.chapters(document(html), root).single().name)
    }
    @Test fun relativeChapterUrlAndQueryPreserved() {
        val html = "<a href='capitulo-1/?id=42&amp;style=paged'>Capítulo 1</a>"
        assertEquals(root + "capitulo-1/?id=42", Layout.chapters(document(html), root).single().path)
    }
    @Test fun rejectsOtherSeriesAndNavigation() {
        val html = "<a href='/bl/other/capitulo-1/'>Capítulo 1</a><a href='/bl/he-might-bite/'>He Might Bite!</a><a href='/bl/he-might-bite/feed/'>Feed</a><a href='https://ads.example${root}capitulo-2/'>Capítulo 2</a>"
        assertTrue(Layout.chapters(document(html), root).isEmpty())
    }
    @Test fun chaptersDeduplicateInSiteOrder() {
        val first = chapter.replace("36", "35")
        assertEquals(listOf(root + "capitulo-35/", root + "capitulo-36/"), Layout.chapters(document(first + chapter + first), root).map { it.path })
    }
    @Test fun emptyChapterLinksIgnored() = assertTrue(Layout.chapters(document("<li class=wp-manga-chapter><a>Capítulo 1</a></li>"), root).isEmpty())
    @Test fun isoDateAndBrazilianDate() {
        val expected = LocalDate.of(2026, 9, 13).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expected, Layout.absoluteDate("2026-09-13"))
        assertEquals(Instant.parse("2026-09-13T00:00:00Z").toEpochMilli(), Layout.absoluteDate("2026-09-12T21:00:00-03:00"))
        assertEquals(expected, Layout.absoluteDate("Publicado em 13/09/2026"))
        assertNull(Layout.absoluteDate("ontem"))
        assertNull(Layout.absoluteDate("inválido"))
    }
    @Test fun timeAttributeWorksAfterClassChanges() {
        val html = "<li><a href='capitulo-1/'>Capítulo 1</a><time datetime='2026-09-13'>Hoje</time></li>"
        assertEquals("2026-09-13", Layout.chapters(document(html), root).single().date)
    }
    @Test fun searchCardCurrentAndRenamed() {
        for (html in listOf(card, card.replace("xyaoi-search-card", "new-card").replace("h3", "h2"))) {
            val parsed = Layout.cards(document(html)).single()
            assertEquals("He Might Bite!", parsed.title)
            assertEquals(root, parsed.path)
            assertEquals("123", parsed.id)
            assertEquals("/cover.webp", parsed.image?.attr("data-src"))
        }
    }
    @Test fun catalogSectionsKeepCards() {
        val html = "<section id=popular>$card</section><section id=latest>${card.replace("123", "456").replace("he-might-bite", "another-work").replace("He Might Bite!", "Another Work")}</section>"
        assertEquals(listOf("He Might Bite!", "Another Work"), Layout.cards(document(html)).map { it.title })
    }
    @Test fun searchIdFallbackAndDuplicateTitleLinks() {
        val html = card.replace("data-post-id=\"123\"", "id=\"post-123\"").replace("</article>", "<h2><a href='$root'>He Might Bite!</a></h2></article>")
        assertEquals("123", Layout.cards(document(html)).single().id)
    }
    @Test fun cardsWithoutIdRemainAvailableToSearchResolver() = assertNull(Layout.cards(document(card.replace("data-post-id=\"123\"", "data-random=\"999\""))).single().id)
    @Test fun rejectNavigationAndChapterCards() {
        assertTrue(Layout.cards(document("<h3><a href='$root'>Menu</a></h3>")).isEmpty())
        assertTrue(Layout.cards(document(card.replace(root, root + "capitulo-1/"))).isEmpty())
    }
    @Test fun semanticMetadataFallback() {
        val html = "<dl><dt>Status:</dt><dd>Em andamento</dd><dt>Autor</dt><dd><a>Autor X</a></dd></dl>"
        assertEquals("Em andamento", Layout.property(document(html), "Status")?.text())
        assertEquals("Autor X", Layout.property(document(html), "Autor")?.text())
        assertNull(Layout.property(document("<p>Status do servidor indisponível</p>"), "Status"))
    }
}
