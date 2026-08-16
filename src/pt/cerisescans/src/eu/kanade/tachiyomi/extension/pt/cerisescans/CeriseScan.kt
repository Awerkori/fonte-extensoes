package eu.kanade.tachiyomi.extension.pt.cerisescans

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.time.Duration.Companion.seconds

@Source
abstract class CeriseScan : HttpSource() {

    override val supportsLatest = true

    private val apiUrl = "$baseUrl/api"

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(3, 2.seconds)
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Accept", "application/json, text/plain, */*")
        .set("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
        .set("Origin", baseUrl)
        .set("Referer", "$baseUrl/")

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    // ============================= Popular ================================

    override fun popularMangaRequest(page: Int): Request {
        val url = "$apiUrl/comics".toHttpUrl().newBuilder()
            .addQueryParameter("limit", POPULAR_LIMIT.toString())
            .addQueryParameter("sort", POPULAR_SORT)
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val result = response.parseAs<ComicListResponse>()
        return result.toMangasPage(baseUrl, hasNextPage = false)
    }

    // ============================= Latest =================================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$apiUrl/comics".toHttpUrl().newBuilder()
            .addQueryParameter("limit", LATEST_LIMIT.toString())
            .addQueryParameter("sort", LATEST_SORT)
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val result = response.parseAs<ComicListResponse>()
        return result.toMangasPage(baseUrl, hasNextPage = false)
    }

    // ============================= Search =================================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$apiUrl/comics".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", SEARCH_LIMIT.toString())
            .addQueryParameter("sort", filters.firstInstanceOrNull<SortFilter>()?.selected ?: POPULAR_SORT)

        if (query.isNotBlank()) {
            url.addQueryParameter("search", query)
        }

        filters.firstInstanceOrNull<GenreFilter>()?.selected?.let {
            url.addQueryParameter("genre", it)
        }

        filters.firstInstanceOrNull<StatusFilter>()?.selected?.let {
            url.addQueryParameter("status", it)
        }

        filters.firstInstanceOrNull<ScanFilter>()?.selected?.let {
            url.addQueryParameter("scan", it)
        }

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val result = response.parseAs<ComicListResponse>()
        val currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        return result.toMangasPage(baseUrl, currentPage)
    }

    // ============================= Details ================================

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$apiUrl/comics/${manga.ceriseSlug()}", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val result = response.parseAs<ComicDto>()
        return result.toSManga(baseUrl, initialized = true)
    }

    // ============================= Chapters ===============================

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val result = response.parseAs<ComicDto>()
        return result.toSChapterList(dateFormat)
    }

    // ============================= Pages ==================================

    override fun pageListRequest(chapter: SChapter): Request {
        val url = "$apiUrl/chapter-images".toHttpUrl().newBuilder()

        val chapterId = chapter.ceriseId()
        if (chapterId != null) {
            url.addQueryParameter("chapterId", chapterId)
        } else {
            url.addQueryParameter("path", chapter.url)
        }

        return GET(url.build(), headers)
    }

    override fun pageListParse(response: Response): List<Page> = response.parseAs<List<String>>().mapIndexed { index, imageUrl ->
        val absoluteUrl = imageUrl.toAbsoluteUrl(baseUrl)
        Page(index, url = absoluteUrl, imageUrl = absoluteUrl)
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request {
        val imageUrl = page.imageUrl ?: throw IOException("URL da imagem ausente")
        val imageHeaders = headersBuilder()
            .set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .set("Referer", "$baseUrl/")
            .build()

        return GET(imageUrl, imageHeaders)
    }

    // ============================= Filters ================================

    override fun getFilterList(): FilterList = getFilters()

    // ============================= Utils ==================================

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/comic/${manga.ceriseSlug()}"

    override fun getChapterUrl(chapter: SChapter): String {
        val url = chapter.url.ceriseUrl()
        return "$baseUrl/${url.pathSegments.joinToString("/")}"
    }

    private fun SManga.ceriseSlug(): String = url.ceriseUrl().pathSegments.let { segments ->
        when {
            segments.firstOrNull() in SUPPORTED_WEB_PREFIXES && segments.size >= 2 -> segments[1]
            segments.size >= 2 && segments[segments.lastIndex - 1] in SUPPORTED_WEB_PREFIXES -> segments.last()
            else -> segments.lastOrNull().orEmpty()
        }
    }

    private fun SChapter.ceriseId(): String? = url.ceriseUrl().queryParameter("id")

    private fun String.ceriseUrl() = when {
        startsWith("http") -> this
        startsWith("/") -> "$baseUrl$this"
        else -> "$baseUrl/$this"
    }.toHttpUrl()

    companion object {
        private const val POPULAR_LIMIT = 50
        private const val LATEST_LIMIT = 500
        private const val SEARCH_LIMIT = 500
        private const val POPULAR_SORT = "views"
        private const val LATEST_SORT = "recent"
        private val SUPPORTED_WEB_PREFIXES = setOf("comic", "manga")
    }
}
