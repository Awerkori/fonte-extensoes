package eu.kanade.tachiyomi.extension.pt.neotoons

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
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
private const val PAGE_LIMIT = 24

@Source
abstract class NeoToons : KeiSource() {

    private val apiUrl = "https://api.neotoons.com.br"

    private val apiHeaders: Headers
        get() = headersBuilder()
            .set("Accept", "application/json, text/plain, */*")
            .set("Origin", baseUrl)
            .set("Referer", "$baseUrl/")
            .build()

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        rateLimit(permits = 3)
    }

    override fun Headers.Builder.configureHeaders(): Headers.Builder = apply {
        set("User-Agent", USER_AGENT)
        set("Referer", "$baseUrl/")
    }

    override fun imageRequest(page: Page): Request {
        val imageUrl = page.imageUrl
        if (imageUrl.isNullOrBlank()) {
            return super.imageRequest(page)
        }
        return buildNeoToonsImageRequest(imageUrl, baseUrl, headers)
    }

    // ============================== Popular ==============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$apiUrl/mangas".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", PAGE_LIMIT.toString())
            .addQueryParameter("sortBy", "views")
            .build()

        val dto = client.get(url, headers = apiHeaders).parseAs<MangaPageDto>()
        val mangas = dto.items
            .filterNot { it.isTextNovel() }
            .map { it.toSManga(baseUrl) }

        val hasNextPage = page < dto.totalPages
        return MangasPage(mangas, hasNextPage)
    }

    // ============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$apiUrl/mangas".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", PAGE_LIMIT.toString())
            .addQueryParameter("sortBy", "newest")
            .build()

        val dto = client.get(url, headers = apiHeaders).parseAs<MangaPageDto>()
        val mangas = dto.items
            .filterNot { it.isTextNovel() }
            .map { it.toSManga(baseUrl) }

        val hasNextPage = page < dto.totalPages
        return MangasPage(mangas, hasNextPage)
    }

    // ============================== Search ===============================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$apiUrl/mangas".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", PAGE_LIMIT.toString())

        val cleanQuery = query.trim()
        if (cleanQuery.isNotEmpty()) {
            url.addQueryParameter("search", cleanQuery)
        }

        var sortBy = "newest"
        for (filter in filters) {
            when (filter) {
                is SortFilter -> {
                    sortBy = filter.selected
                }
                is StatusFilter -> {
                    if (filter.selected.isNotEmpty() && filter.selected != "ALL") {
                        url.addQueryParameter("status", filter.selected)
                    }
                }
                is DemographicFilter -> {
                    if (filter.selected.isNotEmpty() && filter.selected != "ALL") {
                        url.addQueryParameter("demographic", filter.selected)
                    }
                }
                is GenreFilter -> {
                    if (filter.selected.isNotEmpty()) {
                        url.addQueryParameter("genreSlug", filter.selected)
                    }
                }
                else -> {}
            }
        }

        url.addQueryParameter("sortBy", sortBy)

        val dto = client.get(url.build(), headers = apiHeaders).parseAs<MangaPageDto>()
        val mangas = dto.items
            .filterNot { it.isTextNovel() }
            .map { it.toSManga(baseUrl) }

        val hasNextPage = page < dto.totalPages
        return MangasPage(mangas, hasNextPage)
    }

    // ============================== URL Search ===========================

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val path = url.encodedPath
        if (!path.contains("/obras/")) return null
        val slug = path.substringAfter("/obras/").substringBefore("/").trim()
        if (slug.isEmpty()) return null

        val dto = client.get("$apiUrl/mangas/$slug", headers = apiHeaders).parseAs<MangaDto>()
        return dto.toSManga(baseUrl)
    }

    // ========================== Manga Details & Chapters =================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val slug = manga.url.substringAfter("/obras/").substringBefore("/").trim()
        val dto = client.get("$apiUrl/mangas/$slug", headers = apiHeaders).parseAs<MangaDto>()

        val sManga = if (fetchDetails) dto.toSManga(baseUrl) else manga
        val chapterList = if (fetchChapters) {
            dto.chapters
                .map { it.toSChapter(dto.slug) }
                .distinctBy { it.url }
        } else {
            chapters
        }

        return SMangaUpdate(sManga, chapterList)
    }

    // ============================== Pages ================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = chapter.url
        val slug = chapterUrl.substringAfter("/obras/").substringBefore("/").trim()
        val chapterNumber = chapterUrl.substringAfterLast("/").trim()

        // 1. Primary: Use the official reader API endpoint called by the site's React reader
        if (slug.isNotEmpty() && chapterNumber.isNotEmpty()) {
            try {
                val readUrl = "$apiUrl/chapters/read/$slug/$chapterNumber"
                val response = client.get(readUrl, headers = apiHeaders)
                val dto = response.parseAs<ChapterReadResponseDto>()
                val pages = dto.pages
                    .filter { it.imageUrl.isNotBlank() && !it.imageUrl.contains("/banners/") }
                    .sortedBy { it.pageNumber }
                    .mapIndexed { index, p -> Page(index, imageUrl = p.imageUrl.trim()) }

                if (pages.isNotEmpty()) {
                    return pages
                }
            } catch (_: Exception) {
                // Fallback to web reader page parsing below
            }
        }

        // 2. Fallback: Parse the web reader page
        val fullUrl = if (chapterUrl.startsWith("http")) chapterUrl else "$baseUrl$chapterUrl"
        val request = Request.Builder()
            .url(fullUrl)
            .headers(headers)
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw Exception("HTTP ${response.code} ao carregar capítulo")
        }

        val html = response.body.string()
        val doc = Jsoup.parse(html, fullUrl)

        val pages = mutableListOf<Page>()
        val seenUrls = mutableSetOf<String>()

        val imgElements = doc.select("img[src*='token='], img[src*='/obras/'], img[src*='/capitulo/']")
        for (img in imgElements) {
            val src = img.attr("abs:data-src").ifEmpty { img.attr("abs:src") }.ifEmpty { img.attr("src") }.trim()
            if (src.isNotEmpty() && !src.contains("/banners/") && seenUrls.add(src)) {
                pages.add(Page(pages.size, imageUrl = src))
            }
        }

        if (pages.isEmpty()) {
            val regex = Regex("""https://[^"'\s\\]+token=[^"'\s\\]+""")
            for (match in regex.findAll(html)) {
                val url = match.value.replace("&amp;", "&").replace("\\u0026", "&").replace("\\", "").trim()
                if (!url.contains("/banners/") && seenUrls.add(url)) {
                    pages.add(Page(pages.size, imageUrl = url))
                }
            }
        }

        if (pages.isEmpty()) {
            throw Exception("Nenhuma página encontrada para este capítulo.")
        }

        return pages
    }

    // ============================== Filters ==============================

    override fun getFilterList(data: JsonElement?): FilterList = NeoToonsFilters.get()
}

internal fun buildNeoToonsImageRequest(imageUrl: String, baseUrl: String, baseHeaders: Headers? = null): Request {
    val trimmed = imageUrl.trim().replace(" ", "%20")
    val fullUrl = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        trimmed
    } else {
        "$baseUrl${if (trimmed.startsWith("/")) "" else "/"}$trimmed"
    }

    val uri = fullUrl.toHttpUrlOrNull() ?: fullUrl.toHttpUrl()
    val builder = baseHeaders?.newBuilder() ?: Headers.Builder()

    builder
        .set("User-Agent", USER_AGENT)
        .set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
        .set("Sec-Fetch-Dest", "image")
        .set("Sec-Fetch-Mode", "no-cors")
        .removeAll("Origin")

    if (uri.host.contains("neotoons.com.br")) {
        builder.set("Referer", "$baseUrl/")
        builder.set("Sec-Fetch-Site", "same-site")
    } else {
        builder.removeAll("Referer")
        builder.set("Sec-Fetch-Site", "cross-site")
    }

    return Request.Builder()
        .url(uri)
        .headers(builder.build())
        .get()
        .build()
}
