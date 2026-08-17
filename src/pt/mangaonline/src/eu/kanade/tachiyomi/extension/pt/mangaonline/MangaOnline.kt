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

@Source
abstract class MangaOnline : HttpSource() {

    override val supportsLatest = true

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(2)
        .build()

    // ============================== Popular (Navegar) ==============================

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/populares?page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string(), response.request.url.toString())
        val mangas = doc.select(".manga-card.popular-card").map { el ->
            SManga.create().apply {
                val a = el.selectFirst("a")!!
                setUrlWithoutDomain(a.attr("href"))
                title = el.selectFirst("img")?.attr("alt")
                    ?: el.selectFirst(".card-title, h3, h2")?.text()
                    ?: a.attr("href").substringAfterLast("/")
                thumbnail_url = el.selectFirst("img")?.let { buildImgUrl(it.attr("src"), response) }
            }
        }
        val hasNext = doc.selectFirst("a.public-page-link:last-child") != null
        return MangasPage(mangas, hasNext)
    }

    // ============================== Latest (Recentes) ==============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/atualizacoes?page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string(), response.request.url.toString())
        val seen = mutableSetOf<String>()
        val mangas = doc.select(".latest-manga-card").mapNotNull { el ->
            val a = el.selectFirst("a.latest-cover-link") ?: return@mapNotNull null
            val url = a.attr("href")
            if (!seen.add(url)) return@mapNotNull null
            SManga.create().apply {
                setUrlWithoutDomain(url)
                title = el.selectFirst(".latest-card-title")?.text()
                    ?: el.selectFirst("img")?.attr("alt")
                    ?: url.substringAfterLast("/")
                thumbnail_url = el.selectFirst("img")?.let { buildImgUrl(it.attr("src"), response) }
            }
        }
        val hasNext = doc.selectFirst("a.public-page-link:last-child") != null
        return MangasPage(mangas, hasNext)
    }

    // ============================== Search ==============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = GET("$baseUrl/buscar?q=${java.net.URLEncoder.encode(query.trim(), "UTF-8")}&page=$page", headers)

    override fun searchMangaParse(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string(), response.request.url.toString())
        val mangas = doc.select(".manga-card, .search-result, .latest-manga-card").map { el ->
            SManga.create().apply {
                val a = el.selectFirst("a")!!
                setUrlWithoutDomain(a.attr("href"))
                title = el.selectFirst("img")?.attr("alt")
                    ?: el.selectFirst(".card-title, .latest-card-title, h3")?.text()
                    ?: a.attr("href").substringAfterLast("/")
                thumbnail_url = el.selectFirst("img")?.let { buildImgUrl(it.attr("src"), response) }
            }
        }
        val hasNext = doc.selectFirst("a.public-page-link:last-child") != null
        return MangasPage(mangas, hasNext)
    }

    override fun getFilterList() = FilterList()

    // ============================== Details ==============================

    override fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = Jsoup.parse(response.body.string(), response.request.url.toString())
        return SManga.create().apply {
            title = doc.selectFirst("h1")?.text().orEmpty()
            thumbnail_url = doc.selectFirst(".manga-cover img, .series-cover img, img[src*='/uploads/covers/']")
                ?.let { buildImgUrl(it.attr("src"), response) }
            description = doc.selectFirst(".manga-synopsis, .synopsis, .description")?.text()
            genre = doc.select(".genre-tag, .tag, a[href*='/genero/']").joinToString { it.text() }
            status = when (doc.selectFirst(".manga-status, .status")?.text()?.lowercase()) {
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
        return doc.select(".chapter-list a[href*='/chapter/']").mapNotNull { el ->
            val href = el.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SChapter.create().apply {
                setUrlWithoutDomain(href)
                name = el.text().takeIf { it.isNotBlank() } ?: href.substringAfterLast("/")
                chapter_number = href.substringAfterLast("/").toFloatOrNull() ?: -1f
            }
        }.distinctBy { it.url }
    }

    // ============================== Pages ==============================

    override fun pageListRequest(chapter: SChapter): Request = GET(baseUrl + chapter.url, headers)

    override fun pageListParse(response: Response): List<Page> {
        val doc = Jsoup.parse(response.body.string(), response.request.url.toString())
        return doc.select("#readerContent img[src*='/uploads/chapters/']").mapIndexed { i, el ->
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
}
