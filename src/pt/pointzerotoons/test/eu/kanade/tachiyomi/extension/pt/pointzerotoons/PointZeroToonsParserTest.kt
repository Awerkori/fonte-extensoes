package eu.kanade.tachiyomi.extension.pt.pointzerotoons

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PointZeroToonsParserTest {
    @Test
    fun parsesCurrentInkraCardsAndKeepsThumbnails() {
        val document = Jsoup.parse(
            """
            <header><a href="/"><img src="/branding.png"><h3>Header</h3></a></header>
            <main>
                <a class="inkra-catalog-card__media" href="https://kitsuneyako.com/manga/obra-um/">
                    <img src="/wp-content/uploads/capa-um.webp" alt="Obra Um">
                    <h3>Obra Um</h3>
                </a>
                <a class="inkra-catalog-card__media" href="/manga/obra-dois/">
                    <img data-src="/wp-content/uploads/capa-dois.webp" alt="Obra Dois">
                    <h3>Obra Dois</h3>
                </a>
            </main>
            """.trimIndent(),
            "https://kitsuneyako.com/",
        )

        val cards = parsePointZeroCards(document)

        assertEquals(listOf("Obra Um", "Obra Dois"), cards.map { it.title })
        assertEquals(listOf("/manga/obra-um/", "/manga/obra-dois/"), cards.map { it.url.removePrefix("https://kitsuneyako.com") })
        assertTrue(cards.all { it.thumbnail.startsWith("https://kitsuneyako.com/wp-content/") })
    }

    @Test
    fun ignoresBrandingAndDetectsNextPage() {
        val document = Jsoup.parse(
            """
            <header><a href="/"><img src="/logo.png"><h3>Logo</h3></a></header>
            <nav><a class="next page-numbers" href="/manga/page/2/">Próxima</a></nav>
            """.trimIndent(),
        )

        assertEquals(0, parsePointZeroCards(document).size)
        assertTrue(hasPointZeroNextPage(document))
        assertEquals("/manga/page/2/", pointZeroCatalogPath(2))
    }

    @Test
    fun parsesCurrentInkraChapters() {
        val document = Jsoup.parse(
            """
            <div class="inkra-chapter-list">
                <article class="inkra-chapter-item" data-chapter-id="401224">
                    <a class="inkra-chapter-item__link" href="/obra-capitulo-22/">
                        <span class="inkra-chapter-item__label">Capítulo 22</span>
                    </a>
                </article>
                <article class="inkra-chapter-item" data-chapter-id="401224">
                    <a class="inkra-chapter-item__link" href="/obra-capitulo-22/">
                        <span class="inkra-chapter-item__label">Capítulo 22</span>
                    </a>
                </article>
                <article class="inkra-chapter-item">
                    <a class="inkra-chapter-item__link" href="/obra-capitulo-01/">
                        <span class="inkra-chapter-item__title">Obra — Capítulo 01</span>
                    </a>
                </article>
            </div>
            """.trimIndent(),
            "https://kitsuneyako.com/manga/obra/",
        )

        val chapters = parsePointZeroChapters(document)

        assertEquals(listOf("Capítulo 22", "Obra — Capítulo 01"), chapters.map { it.name })
        assertEquals(listOf("/obra-capitulo-22/", "/obra-capitulo-01/"), chapters.map { it.url.removePrefix("https://kitsuneyako.com") })
    }
}
