package eu.kanade.tachiyomi.extension.pt.corujatoon

import org.jsoup.Jsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class CorujaToonParserTest {
    @Test
    fun parsesCurrentPagePayloadAndKeepsCatalogMetadata() {
        val payload = """
            self.__next_f.push([1,"\"chapters\":[{\"id\":\"chapter-b\",\"number\":10.5,\"title\":null,\"publishedAt\":\"${'$'}D2026-09-01T00:00:00.000Z\"},{\"id\":\"chapter-a\",\"number\":10,\"title\":\"Dez\",\"publishedAt\":\"${'$'}D2026-08-01T00:00:00.000Z\"}]"])
        """.trimIndent()

        val parsed = parseChapterPayload(payload)

        assertEquals(listOf("chapter-b", "chapter-a"), parsed.map { it.id })
        assertEquals(listOf(10.5, 10.0), parsed.map { it.number })
        assertEquals("Dez", parsed.last().title)
    }

    @Test
    fun malformedPayloadFallsBackToChapterLinks() {
        val document = Jsoup.parse(
            """
            <html><body>
                <h1>Obra</h1>
                <a href="/series/obra/capitulo/2"><h3>2</h3></a>
                <a href="/series/obra/capitulo/1.5"><h3>Especial</h3></a>
            </body></html>
            """.trimIndent(),
        )

        val parsed = parseChapterLinks(document)

        assertEquals(listOf(2.0, 1.5), parsed.map { it.number })
        assertEquals("Especial", parsed.last().title)
    }

    @Test
    fun extractsRepeatedChapterImagesFromMainAndIgnoresBranding() {
        val document = Jsoup.parse(
            """
            <header><img src="https://cdn.example/logo.png"></header>
            <main>
                <img src="/assets/placeholder.png" data-src="https://cdn.example/obras/obra/capitulo-67/001">
                <img src="https://cdn.example/obras/obra/capitulo-67/002.webp?x=1">
                <img src="https://cdn.example/obras/obra/capitulo-67/003">
                <img src="https://cdn.example/mascot.png">
            </main>
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                "https://cdn.example/obras/obra/capitulo-67/001",
                "https://cdn.example/obras/obra/capitulo-67/002.webp?x=1",
                "https://cdn.example/obras/obra/capitulo-67/003",
            ),
            parseChapterPageImages(document, "https://corujatoon.com/series/obra/capitulo/67".toHttpUrl()),
        )
    }

    @Test
    fun brandingOnlyDoesNotBecomePages() {
        val document = Jsoup.parse("<main><img src=\"https://cdn.example/mascot.png\"></main>")

        assertEquals(emptyList<String>(), parseChapterPageImages(document, "https://corujatoon.com/series/obra/capitulo/67".toHttpUrl()))
    }
}
