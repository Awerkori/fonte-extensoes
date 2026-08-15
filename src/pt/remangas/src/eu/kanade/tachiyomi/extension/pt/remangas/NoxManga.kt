package eu.kanade.tachiyomi.extension.pt.remangas

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import okhttp3.Request
import okhttp3.Response

@Source
abstract class NoxManga : HttpSource() {
    override val supportsLatest = true

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/home", headers)

    override fun popularMangaParse(response: Response): MangasPage = parseCards(response, ".cx-card")

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/home", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseCards(response, ".cx-side-item")

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = GET("$baseUrl/search?q=${query.trim().replace(" ", "+")}", headers)

    override fun searchMangaParse(response: Response): MangasPage = parseCards(response, "a[href^='/manga/']")

    private fun parseCards(response: Response, selector: String): MangasPage {
        val cards = response.asJsoup().select(selector).mapNotNull { card ->
            val link = card.selectFirst("a[href^='/manga/']") ?: if (card.tagName() == "a") card else null
            val href = link?.attr("href") ?: return@mapNotNull null
            val title = card.selectFirst(".cx-card-title, .cx-side-name, .cx-title")?.text()?.trim()
                ?: card.selectFirst("img[alt]")?.attr("alt")?.trim()
                ?: return@mapNotNull null
            SManga.create().apply {
                url = href.substringAfter("/manga/")
                this.title = title
                thumbnail_url = card.selectFirst("img")?.absUrl("src")
            }
        }.distinctBy { it.url }
        require(cards.isNotEmpty()) { "O HTML do NixManga não contém obras; o layout pode ter mudado." }
        return MangasPage(cards, false)
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/manga/${manga.url}"

    override fun mangaDetailsRequest(manga: SManga): Request = GET(getMangaUrl(manga), headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()
        val title = doc.selectFirst("h1, .detail-title")?.text()?.trim()
            ?: throw Exception("O HTML do NixManga não contém o título da obra.")
        return SManga.create().apply {
            this.title = title
            thumbnail_url = doc.selectFirst("img[alt='$title'], .detail-cover img, img[alt]")?.absUrl("src")
            description = doc.selectFirst(".detail-description, .synopsis, [class*=description]")?.text()?.trim()
            genre = doc.select(".tag-chip, .detail-tags a").joinToString { it.text().trim() }
            val statusText = doc.selectFirst("p:matches(^Status:)")?.text()?.lowercase().orEmpty()
            status = when {
                "completo" in statusText -> SManga.COMPLETED
                "hiato" in statusText -> SManga.ON_HIATUS
                "cancelado" in statusText -> SManga.CANCELLED
                "andamento" in statusText -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }
        }
    }

    override fun chapterListRequest(manga: SManga): Request = GET(getMangaUrl(manga), headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val chapters = response.asJsoup().select(".chapter-item-modern[href^='/read/']").mapNotNull { link ->
            val href = link.attr("href")
            val number = Regex("(?:capitulo|capítulo)[- ]([0-9]+(?:\\.[0-9]+)?)", RegexOption.IGNORE_CASE)
                .find(href)?.groupValues?.get(1)?.toFloatOrNull() ?: return@mapNotNull null
            SChapter.create().apply {
                url = href
                name = link.text().trim().ifEmpty { "Capítulo $number" }
                chapter_number = number
            }
        }.distinctBy { it.url }
        require(chapters.isNotEmpty()) { "O HTML do NixManga não contém capítulos." }
        return chapters
    }

    override fun pageListRequest(chapter: SChapter): Request = GET(baseUrl + chapter.url, headers)

    override fun pageListParse(response: Response): List<Page> {
        val pages = response.asJsoup().select("img[alt*='Página'], .reader-content img, main img")
            .mapNotNull { it.absUrl("src").takeIf(String::isNotBlank) }
            .distinct()
        require(pages.isNotEmpty()) { "O HTML do NixManga não contém páginas de leitura." }
        return pages.mapIndexed { index, url -> Page(index, imageUrl = url) }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()
}
