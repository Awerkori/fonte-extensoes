package eu.kanade.tachiyomi.extension.pt.mangaonline

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Calendar
import kotlin.time.Duration.Companion.seconds

@Source
abstract class MangaOnline : KeiSource() {

    override fun OkHttpClient.Builder.configureClient() = rateLimit(2, 1.seconds)

    // ============================== Popular ================================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val document = client.get("$baseUrl/manga/page/$page/?ordem=popular").asJsoup()

        val mangas = document.select(".manga-archive-grid article.home-manga-card, article.home-manga-card").map(::mangaFromElement)
        val hasNextPage = document.selectFirst(".manga-archive-pagination a.next, a.next.page-numbers") != null

        return MangasPage(mangas, hasNextPage)
    }

    // ============================== Latest ================================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = if (page == 1) "$baseUrl/?atualizacoes=1" else "$baseUrl/page/$page/?atualizacoes=1"
        val document = client.get(url).asJsoup()

        val mangas = document.select(".home-latest-grid article.home-manga-card, article.home-manga-card").map(::mangaFromElement)
        val hasNextPage = document.selectFirst(".home-latest-pagination a.next, .home-latest-pagination span.is-current + a, link[rel=next]") != null

        return MangasPage(mangas, hasNextPage)
    }

    private fun mangaFromElement(element: Element) = SManga.create().apply {
        val link = element.selectFirst("a.manga-card-link, h3 a, a:has(h3)") ?: element.selectFirst("a")!!
        val titleElement = element.selectFirst("h3.manga-card-title, h3 a, h3")
        title = titleElement?.text()?.ifEmpty { null } ?: element.selectFirst("img")?.attr("alt")?.ifEmpty { null } ?: ""
        setUrlWithoutDomain(link.attr("abs:href"))
        thumbnail_url = element.selectFirst("img")?.attr("abs:src")
    }

    // ============================== Search ================================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) {
                addQueryParameter("s", query)
                if (page > 1) {
                    addQueryParameter("paged", page.toString())
                }
            } else {
                addPathSegment("manga")
                if (page > 1) {
                    addPathSegment("page")
                    addPathSegment(page.toString())
                }
                val order = filters.firstInstanceOrNull<SortFilter>()?.selectedValue ?: "popular"
                addQueryParameter("ordem", order)
            }
        }.build()

        val document = client.get(url).asJsoup()
        val mangas = document.select(".manga-grid.search-results-grid .manga-card, .manga-archive-grid article.home-manga-card, article.home-manga-card").map(::mangaFromElement)
        val hasNextPage = document.selectFirst("a.next.page-numbers, .manga-archive-pagination a.next") != null

        return MangasPage(mangas, hasNextPage)
    }

    // ============================== Details ================================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()

        val updatedManga = manga.apply {
            title = document.selectFirst("h1.manga-title")?.ownText()?.ifEmpty { null }
                ?: document.selectFirst("h1.manga-title")?.text()?.removePrefix("br")?.trim()
                ?: manga.title
            document.selectFirst(".manga-cover img")?.attr("abs:src")?.let { thumbnail_url = it }
            description = document.selectFirst(".synopsis-content")?.text()
            genre = document.select(".manga-tags a").joinToString { it.text() }

            document.select(".manga-meta-item").forEach { item ->
                val label = item.selectFirst(".meta-label")?.text() ?: return@forEach
                val value = item.selectFirst(".meta-value")?.text() ?: return@forEach

                when (label) {
                    "Status:" -> status = parseStatus(value)
                    "Autor:" -> author = value
                    "Artista:" -> artist = value
                }
            }
        }

        val updatedChapters = if (fetchChapters) fetchChapterList(document) else chapters

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun fetchChapterList(document: Document): List<SChapter> {
        val container = document.selectFirst(".chapters-container") ?: return emptyList()
        return container.select(".chapters-list .chapter-item").map { el ->
            SChapter.create().apply {
                name = el.selectFirst(".chapter-number")?.text() ?: "Capítulo"
                setUrlWithoutDomain(el.selectFirst(".chapter-link")!!.attr("abs:href"))
                date_upload = parseRelativeDate(el.selectFirst(".chapter-date")?.text())
            }
        }
    }

    private fun parseStatus(status: String) = when (status) {
        "Em Lançamento", "Em Andamento" -> SManga.ONGOING
        "Completo" -> SManga.COMPLETED
        "Cancelado" -> SManga.CANCELLED
        "Pausado", "Hiato" -> SManga.ON_HIATUS
        else -> SManga.UNKNOWN
    }

    // ============================== Pages ================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()
        return document.select(".chapter-image-container img, .chapter-images img").mapIndexed { index, img ->
            val url = img.attr("abs:src").ifEmpty { img.attr("abs:data-src") }
            Page(index, imageUrl = url)
        }
    }

    // ============================== Helpers ================================

    private fun parseRelativeDate(date: String?): Long {
        if (date.isNullOrEmpty()) return 0L

        val number = Regex("""\d+""").find(date)?.value?.toIntOrNull() ?: return 0L
        val cal = Calendar.getInstance()

        return when {
            date.contains("ano", true) -> cal.apply { add(Calendar.YEAR, -number) }.timeInMillis
            date.contains("mês", true) || date.contains("meses", true) -> cal.apply { add(Calendar.MONTH, -number) }.timeInMillis
            date.contains("semana", true) -> cal.apply { add(Calendar.WEEK_OF_YEAR, -number) }.timeInMillis
            date.contains("dia", true) -> cal.apply { add(Calendar.DAY_OF_MONTH, -number) }.timeInMillis
            date.contains("hora", true) -> cal.apply { add(Calendar.HOUR, -number) }.timeInMillis
            date.contains("minuto", true) -> cal.apply { add(Calendar.MINUTE, -number) }.timeInMillis
            else -> 0L
        }
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        SortFilter(),
    )

    class SortFilter :
        Filter.Select<String>(
            "Ordenar por",
            arrayOf("Mais populares", "Mais recentes"),
        ) {
        val selectedValue: String
            get() = when (state) {
                0 -> "popular"
                1 -> "recentes"
                else -> "popular"
            }
    }
}
