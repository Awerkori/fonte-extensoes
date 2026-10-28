package eu.kanade.tachiyomi.extension.pt.mangalivrelat

import eu.kanade.tachiyomi.network.GET
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
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Calendar
import kotlin.time.Duration.Companion.seconds

@Source
abstract class MangaLivreLat : KeiSource() {

    override fun OkHttpClient.Builder.configureClient() = rateLimit(permits = 2, period = 1.seconds)

    override fun Headers.Builder.configureHeaders() = add("Referer", "$baseUrl/")

    // ============================== Popular ================================
    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = if (page == 1) {
            "$baseUrl/manga/?ordem=popular"
        } else {
            "$baseUrl/manga/page/$page/?ordem=popular"
        }
        val document = client.get(url).asJsoup()
        val mangas = document.select(".manga-archive-grid article.home-manga-card").map(::mangaFromCard)
        val hasNextPage = document.selectFirst(".manga-archive-pagination a.next, .manga-archive-pagination a.page-numbers:contains(›)") != null

        return MangasPage(mangas.distinctBy { it.url }, hasNextPage)
    }

    // ============================== Latest ================================
    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = if (page == 1) {
            baseUrl
        } else {
            "$baseUrl/?atualizacoes=$page"
        }
        val document = client.get(url).asJsoup()
        val mangas = document.select(".home-latest-grid article.home-manga-card").map(::mangaFromCard)
        val hasNextPage = document.selectFirst(".home-latest-pagination a.home-page-arrow, .home-latest-pagination span.is-current + a") != null

        return MangasPage(mangas.distinctBy { it.url }, hasNextPage)
    }

    private fun mangaFromCard(el: Element) = SManga.create().apply {
        val link = el.selectFirst("h3 a") ?: el.selectFirst("a.home-manga-cover")!!
        val cardTitle = el.selectFirst("h3 a")?.text()?.trim()
        val href = link.attr("abs:href")
        setUrlWithoutDomain(href)
        title = if (!cardTitle.isNullOrEmpty()) cardTitle else parseMangaTitleFromSlug(href)
        thumbnail_url = el.selectFirst("img")?.attr("abs:src")?.let(::cleanThumbnailUrl)
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
                addPathSegment("pesquisa")
                addQueryParameter("s", "")

                filters.forEach { filter ->
                    when (filter) {
                        is RatingMinFilter -> addQueryParameter("rating_min", filter.selectedValue())
                        is SortFilter -> addQueryParameter("sort", filter.selectedValue())
                        is OrderFilter -> addQueryParameter("order", filter.selectedValue())
                        else -> {}
                    }
                }

                if (page > 1) {
                    addQueryParameter("paged", page.toString())
                }
            }
        }.build()

        val document = client.get(url).asJsoup()
        val mangas = document.select(".manga-grid.search-results-grid .manga-card").map(::mangaFromSearchCard)
        val hasNextPage = document.selectFirst("a.next.page-numbers") != null

        return MangasPage(mangas.distinctBy { it.url }, hasNextPage)
    }

    private fun mangaFromSearchCard(el: Element) = SManga.create().apply {
        val link = el.selectFirst("a.manga-card-link") ?: el.selectFirst("a")!!
        val href = link.attr("abs:href")
        setUrlWithoutDomain(href)

        val cardTitle = el.selectFirst("h3.manga-card-title")?.text()?.trim()
        val img = el.selectFirst("img")
        val imgAlt = img?.attr("alt")?.trim()

        title = when {
            !cardTitle.isNullOrEmpty() && !cardTitle.startsWith("Pesquisa", ignoreCase = true) -> cardTitle
            !imgAlt.isNullOrEmpty() -> imgAlt
            else -> parseMangaTitleFromSlug(href)
        }

        thumbnail_url = img?.attr("abs:src")?.let(::cleanThumbnailUrl)
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
            if (fetchDetails) {
                val titleEl = document.selectFirst("h1.manga-title")
                if (titleEl != null) {
                    val own = titleEl.ownText().trim()
                    title = if (own.isNotEmpty()) own else titleEl.text().trim()
                }
                document.selectFirst(".manga-cover-image, .manga-cover img")?.attr("abs:src")?.let {
                    thumbnail_url = cleanThumbnailUrl(it)
                }
                description = document.selectFirst(".synopsis-content")?.text()?.trim()
                genre = document.select(".manga-tags .manga-tag").joinToString { it.text().trim() }
                    .ifEmpty { null }

                document.select(".manga-meta-item").forEach { item ->
                    val label = item.selectFirst(".meta-label")?.text()?.trim() ?: return@forEach
                    val value = item.selectFirst(".meta-value")?.text()?.trim() ?: return@forEach

                    when (label) {
                        "Status:" -> status = parseStatus(value)
                        "Autor:" -> author = value
                        "Artista:" -> artist = value
                    }
                }
            }
        }

        val updatedChapters = if (fetchChapters) parseChapterList(document) else chapters

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun parseChapterList(document: Document): List<SChapter> = document.select("ul.chapters-list li.chapter-item").map { el ->
        SChapter.create().apply {
            val link = el.selectFirst("a.chapter-link") ?: el.selectFirst("a")!!
            setUrlWithoutDomain(link.attr("abs:href"))
            val rawName = el.selectFirst(".chapter-number")?.text()?.trim() ?: link.text().trim()
            name = formatChapterName(rawName)
            date_upload = parseRelativeDate(el.selectFirst(".chapter-date")?.text())
        }
    }

    // ============================== Pages ================================
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()
        return document.select(".chapter-image-container img").mapIndexed { index, img ->
            val url = img.attr("abs:src").ifEmpty { img.attr("abs:data-src") }
            Page(index, imageUrl = url)
        }
    }

    override fun imageRequest(page: Page): Request {
        val imageUrl = page.imageUrl ?: return super.imageRequest(page)
        return GET(imageUrl, headers.newBuilder().set("Referer", "$baseUrl/").build())
    }

    // ============================== Filters ================================
    override fun getFilterList(data: JsonElement?) = getFilters()
}

// ============================== Helpers ================================
internal fun cleanThumbnailUrl(url: String): String = url.replace(Regex("""-\d+x\d+"""), "")

internal fun formatChapterName(name: String): String = name
    .replace(Regex("""(?i)\bcapítulo\b"""), "Cap.")
    .replace(Regex("""(?i)\bcapitulo\b"""), "Cap.")
    .trim()

internal fun parseStatus(status: String) = when (status) {
    "Em Lançamento", "Em Andamento" -> SManga.ONGOING
    "Completo" -> SManga.COMPLETED
    "Cancelado" -> SManga.CANCELLED
    "Pausado", "Hiato" -> SManga.ON_HIATUS
    else -> SManga.UNKNOWN
}

internal fun parseRelativeDate(date: String?): Long {
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

internal fun parseMangaTitleFromSlug(url: String): String {
    val slug = url.removeSuffix("/").substringAfterLast('/')
    return slug.replace('-', ' ').split(' ')
        .filter { it.isNotEmpty() }
        .joinToString(" ") { it.replaceFirstChar(Char::titlecase) }
}
