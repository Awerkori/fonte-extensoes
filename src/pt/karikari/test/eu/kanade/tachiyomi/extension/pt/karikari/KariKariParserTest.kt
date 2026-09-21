package eu.kanade.tachiyomi.extension.pt.karikari

import eu.kanade.tachiyomi.source.model.SManga
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KariKariParserTest {
    @Test
    fun popularFixtureIsNotEmptyAndDeduplicated() {
        val document = Jsoup.parse(
            """
            <main>
              <a href="/obra/sense-life"><img alt="Sense Life" src="https://cdn.karikari.app/sense.webp"><p>Sense Life</p></a>
              <a href="/obra/sense-life"><img alt="Sense Life" src="https://cdn.karikari.app/sense.webp"><p>Sense Life</p></a>
              <a href="/obra/enoh-k"><img alt="Enoh.K" src="https://cdn.karikari.app/enoh.webp"><p>Enoh.K</p></a>
            </main>
            """.trimIndent(),
        )

        assertEquals(listOf("/obra/sense-life", "/obra/enoh-k"), KariKari.parseRankingCards(document).map { it.url })
    }

    @Test
    fun searchKnownAndUnknownFixtures() {
        val works = listOf(MangaDto(slug = "sense-life", titulo = "Sense Life"), MangaDto(slug = "enoh-k", titulo = "Enoh.K"))
        assertTrue(works.any { it.titulo.contains("Sense Life", ignoreCase = true) })
        assertFalse(works.any { it.titulo.contains("does-not-exist", ignoreCase = true) })
    }

    @Test
    fun detailsMapSenseLifeAndEnohStatusAndGenres() {
        val sense = MangaDto(slug = "sense-life", titulo = "Sense Life", status = "Em produção")
        val enoh = MangaDto(slug = "enoh-k", titulo = "Enoh.K", status = "Finalizado")

        assertEquals("Sense Life", sense.titulo)
        assertEquals(SManga.ONGOING, sense.status.toMihonStatus())
        assertEquals(SManga.COMPLETED, enoh.status.toMihonStatus())
    }

    @Test
    fun chapterTitleWithoutNumberIsPreservedAndNumberedTitleParses() {
        val special = ChapterDto(titulo = "Prólogo", slug = "prologo", ordem = 1)
        val numbered = ChapterDto(titulo = "Capítulo 4 - Eu não vou me curvar", slug = "cap-4", ordem = 8)

        assertEquals("Prólogo", special.titulo)
        assertEquals(1f, parseChapterNumber(special.titulo, special.ordem))
        assertEquals("Capítulo 4 - Eu não vou me curvar", numbered.titulo)
        assertEquals(4f, parseChapterNumber(numbered.titulo, numbered.ordem))
    }

    @Test
    fun inaccessibleFuturePremiumAndAdultChaptersNeverExposeReader() {
        val base = ChapterAccessDto(status = "Aprovado", publico = true, visibilidade = "Disponível para todos")
        assertTrue(base.isAccessible())
        assertFalse(base.copy(publico = false).isAccessible())
        assertFalse(base.copy(scheduled = true).isAccessible())
        assertFalse(base.copy(membersOnly = true).isAccessible())
        assertFalse(base.copy(obras = AdultWorkDto(adult = true)).isAccessible())
        assertTrue(base.copy(obras = AdultWorkDto(adult = true)).isAccessible(allowAdult = true))
    }

    @Test
    fun readerOrdersPagesAndRemovesDuplicates() {
        val pages = listOf(
            PageDto(3, "https://cdn.karikari.app/3.webp"),
            PageDto(1, "https://cdn.karikari.app/1.webp"),
            PageDto(2, "https://cdn.karikari.app/2.webp"),
            PageDto(4, "https://cdn.karikari.app/2.webp"),
        )
        assertEquals(
            listOf("https://cdn.karikari.app/1.webp", "https://cdn.karikari.app/2.webp", "https://cdn.karikari.app/3.webp"),
            pages.orderedImageUrls(),
        )
    }

    @Test
    fun latestUpdatesDeduplicateWorksAcrossChapterRows() {
        val rows = listOf(RecentChapterDto("sense"), RecentChapterDto("sense"), RecentChapterDto("enoh"))
        assertEquals(listOf("sense", "enoh"), rows.distinctWorkIds())
    }
}
