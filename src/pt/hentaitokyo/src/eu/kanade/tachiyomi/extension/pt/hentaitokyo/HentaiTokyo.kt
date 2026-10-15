package eu.kanade.tachiyomi.extension.pt.hentaitokyo

import eu.kanade.tachiyomi.multisrc.gattsu.Gattsu
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.utils.asJsoup
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.time.Duration.Companion.seconds

@Source
abstract class HentaiTokyo : Gattsu() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(1, 2.seconds)

    override suspend fun getPopularManga(page: Int): MangasPage {
        val path = if (page == 1) "" else "page/$page"
        val document = client.get("$baseUrl/$path").asJsoup()
        val mangas = document.select("div.meio div.lista ul li")
            .mapNotNull { it.selectFirst("a[href^=$baseUrl]") }
            .distinctBy { it.attr("abs:href").substringBefore('?').trimEnd('/') }
            .map { latestUpdatesFromElement(it) }
        val hasNextPage = document.selectFirst(latestUpdatesNextPageSelector() ?: "ul.paginacao li.next > a") != null
        return MangasPage(mangas, hasNextPage)
    }

    override fun chapterListParse(document: Document): List<SChapter> {
        val chapterLinks = document.select("div.meio div.post-box a, div.meio div.post-box button, div.meio div.post-box [data-href], div.meio div.post-box [data-url]")
            .filter { element ->
                val href = chapterUrl(element).lowercase()
                val text = element.text().lowercase()
                chapterNumber(text) != null || href.contains("capitulo") || href.contains("capítulo") ||
                    href.contains("chapter") || text.contains("capitulo") || text.contains("capítulo") || text.contains("chapter")
            }

        val translator = document.select("ul.post-itens li:contains(Tradutor) a").firstOrNull()?.text()
        val date = document.select("meta[property=article:published_time]").firstOrNull()
            ?.attr("content")
            .orEmpty()
            .toDate()

        if (chapterLinks.isEmpty()) {
            return if (document.selectFirst(pageListSelector()) != null) {
                listOf(
                    SChapter.create().apply {
                        name = "Capítulo único"
                        scanlator = translator
                        date_upload = date
                        setUrlWithoutDomain(document.location())
                    },
                )
            } else {
                emptyList()
            }
        }

        return chapterLinks
            .distinctBy { chapterUrl(it).substringBefore('?').trimEnd('/') }
            .map { element ->
                SChapter.create().apply {
                    name = element.text().ifBlank { "Capítulo único" }
                    chapter_number = chapterNumber(name) ?: -1f
                    scanlator = translator
                    date_upload = date
                    setUrlWithoutDomain(chapterUrl(element))
                }
            }
    }

    private companion object {
        val CHAPTER_NUMBER = Regex(
            "(?:cap[ií]tulo|chapter)\\s*([0-9]+(?:[.,][0-9]+)?)",
            RegexOption.IGNORE_CASE,
        )

        fun chapterNumber(text: String): Float? = CHAPTER_NUMBER.find(text)
            ?.groupValues
            ?.get(1)
            ?.replace(',', '.')
            ?.toFloatOrNull()

        val ONCLICK_URL = Regex("['\"]([^'\"]+)['\"]")
    }

    private fun chapterUrl(element: Element): String {
        val onclick = element.attr("onclick")
        return element.attr("abs:href").ifBlank {
            element.attr("data-href").ifBlank {
                element.attr("data-url").ifBlank {
                    ONCLICK_URL.find(onclick)?.groupValues?.get(1).orEmpty()
                }
            }
        }
    }
}
