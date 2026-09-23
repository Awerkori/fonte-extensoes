package eu.kanade.tachiyomi.extension.pt.mangaonline

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

@Source
abstract class MangaOnline : HttpSource() {

    override val supportsLatest = true

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(2)
        .build()

    // ============================== Popular (Navegar) ==============================

    override fun popularMangaRequest(page: Int): Request = GET(
        if (page == 1) "$baseUrl/manga/?ordem=popular" else "$baseUrl/manga/page/$page/?ordem=popular",
        headers,
    )

    override fun popularMangaParse(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string(), response.request.url.toString())
        val mangas = doc.select(".manga-archive-grid article.home-manga-card").mapNotNull { el ->
            val a = el.selectFirst("a.home-manga-cover") ?: return@mapNotNull null
            SManga.create().apply {
                setUrlWithoutDomain(a.attr("href"))
                title = el.selectFirst(".home-card-body h3 a")?.text()
                    ?: el.selectFirst("img")?.attr("alt")
                    ?: a.attr("href").substringAfterLast("/")
                thumbnail_url = el.selectFirst("img")?.let { buildImgUrl(it.attr("src"), response) }
            }
        }
        val hasNext = doc.selectFirst(".manga-archive-pagination a.next") != null
        return MangasPage(mangas, hasNext)
    }

    // ============================== Latest (Recentes) ==============================

    override fun latestUpdatesRequest(page: Int): Request = GET(if (page == 1) baseUrl else "$baseUrl/?atualizacoes=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string(), response.request.url.toString())
        val seen = mutableSetOf<String>()
        val mangas = doc.select("section.home-latest .home-latest-grid article.home-manga-card").mapNotNull { el ->
            val a = el.selectFirst("a.home-manga-cover") ?: return@mapNotNull null
            val url = a.attr("href")
            if (!seen.add(url)) return@mapNotNull null
            SManga.create().apply {
                setUrlWithoutDomain(url)
                title = el.selectFirst(".home-card-body h3 a")?.text()
                    ?: el.selectFirst("img")?.attr("alt")
                    ?: url.substringAfterLast("/")
                thumbnail_url = el.selectFirst("img")?.let { buildImgUrl(it.attr("src"), response) }
            }
        }
        val hasNext = doc.selectFirst("section.home-latest .home-latest-pagination a[aria-label='Próxima página']") != null
        return MangasPage(mangas, hasNext)
    }

    // ============================== Search ==============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = GET(
        "$baseUrl/?s=${java.net.URLEncoder.encode(query.trim(), "UTF-8")}&paged=$page",
        headers,
    )

    override fun searchMangaParse(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string(), response.request.url.toString())
        val mangas = doc.select(".manga-card, .search-result, .latest-manga-card").mapNotNull { el ->
            val a = el.selectFirst("a[href*='/manga/']") ?: return@mapNotNull null
            SManga.create().apply {
                setUrlWithoutDomain(a.attr("href"))
                title = el.selectFirst("img")?.attr("alt")
                    ?: el.selectFirst(".card-title, .latest-card-title, h3")?.text()
                    ?: a.attr("href").substringAfterLast("/")
                thumbnail_url = el.selectFirst("img")?.let { buildImgUrl(it.attr("src"), response) }
            }
        }
        val hasNext = doc.selectFirst(".pagination a.next") != null
        return MangasPage(mangas, hasNext)
    }

    override fun getFilterList() = FilterList()

    // ============================== Details ==============================

    override fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = Jsoup.parse(response.body.string(), response.request.url.toString())
        val meta = doc.select(".manga-meta-item")
        fun metaValue(label: String) = meta.firstOrNull {
            it.selectFirst(".meta-label")?.text()?.trim()?.equals(label, ignoreCase = true) == true
        }?.selectFirst(".meta-value")?.text()?.trim()?.takeIf { it.isNotBlank() }

        return SManga.create().apply {
            title = doc.selectFirst("h1.manga-title")?.text()?.trim().orEmpty()
            thumbnail_url = doc.selectFirst(".manga-cover img, .series-cover img, img[src*='/uploads/covers/']")
                ?.let { buildImgUrl(it.attr("src"), response) }
            description = doc.selectFirst(".manga-synopsis, .synopsis, .description")?.text()
            genre = doc.select(".manga-tags a, .genre-tag, a[href*='/genero/']").joinToString { it.text() }
            author = metaValue("Autor:")
            artist = metaValue("Artista:")
            status = when ((metaValue("Status:") ?: doc.selectFirst(".manga-status, .status")?.text())?.lowercase()) {
                "em andamento", "ongoing" -> SManga.ONGOING
                "completo", "completed" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }
    }

    // ============================== Chapters ==============================

    override fun chapterListRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = Jsoup.parse(response.body.string(), response.request.url.toString())
        return doc.select(".chapters-list .chapter-item a.chapter-link").mapNotNull { el ->
            val href = el.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SChapter.create().apply {
                setUrlWithoutDomain(href)
                name = el.selectFirst(".chapter-number")?.text()?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: el.text().trim().takeIf { it.isNotBlank() }
                    ?: href.substringAfterLast("/")
                chapter_number = Regex("\\d+(?:\\.\\d+)?").find(name)?.value?.toFloatOrNull() ?: -1f
                date_upload = parseRelativeDate(el.parent()?.selectFirst(".chapter-date")?.text())
            }
        }.distinctBy { it.url }
    }

    // ============================== Pages ==============================

    override fun pageListRequest(chapter: SChapter): Request = GET(baseUrl + chapter.url, headers)

    override fun pageListParse(response: Response): List<Page> {
        val doc = Jsoup.parse(response.body.string(), response.request.url.toString())
        return doc.select(".chapter-images img.chapter-image").mapIndexed { i, el ->
            Page(i, "", buildImgUrl(el.attr("src"), response))
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================== Helpers ==============================

    private fun buildImgUrl(src: String, response: Response): String {
        if (src.isBlank()) return ""
        return when {
            src.startsWith("http") -> src
            src.startsWith("//") -> "https:$src"
            src.startsWith("/") -> "${response.request.url.scheme}://${response.request.url.host}$src"
            else -> "$baseUrl/$src"
        }
    }

    private fun parseRelativeDate(value: String?): Long {
        val match = Regex("há\\s+(\\d+)\\s+(minuto|hora|dia|semana|mês|ano)", RegexOption.IGNORE_CASE)
            .find(value.orEmpty()) ?: return 0L
        val amount = match.groupValues[1].toLongOrNull() ?: return 0L
        val unit = match.groupValues[2].lowercase()
        val millis = when (unit) {
            "minuto" -> TimeUnit.MINUTES.toMillis(amount)
            "hora" -> TimeUnit.HOURS.toMillis(amount)
            "dia" -> TimeUnit.DAYS.toMillis(amount)
            "semana" -> TimeUnit.DAYS.toMillis(amount * 7)
            "mês" -> TimeUnit.DAYS.toMillis(amount * 30)
            "ano" -> TimeUnit.DAYS.toMillis(amount * 365)
            else -> return 0L
        }
        return System.currentTimeMillis() - millis
    }
}
