package eu.kanade.tachiyomi.extension.pt.remangas

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
import keiyoushi.utils.stringOrNull
import keiyoushi.utils.toJsonElement
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import kotlin.time.Duration.Companion.seconds

@Source
abstract class NoxManga : KeiSource() {

    private val apiUrl get() = "$baseUrl/api/v1"

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        addInterceptor(SignatureInterceptor { baseUrl })
        rateLimit(3, 1.seconds) { it.encodedPath.startsWith("/api/") }
    }

    override fun Headers.Builder.configureHeaders(): Headers.Builder = add("Sec-Fetch-Site", "same-origin")

    // =====================Popular=====================

    override suspend fun getPopularManga(page: Int): MangasPage = getMangaList(page, defaultSort = "popular")

    // =====================Latest=====================

    override suspend fun getLatestUpdates(page: Int): MangasPage = getMangaList(page, defaultSort = "latest")

    // =====================Search=====================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = getMangaList(page, query, filters)

    private suspend fun getMangaList(
        page: Int,
        query: String = "",
        filters: FilterList = FilterList(),
        defaultSort: String? = null,
    ): MangasPage {
        val url = "$apiUrl/comics".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("per_page", PAGE_SIZE.toString())
            .apply {
                if (query.isNotBlank()) {
                    addQueryParameter("q", query)
                }
                val hasSortFilter = filters.filterIsInstance<SortFilter>().any { it.selectedValue.isNotEmpty() }
                if (!hasSortFilter && defaultSort != null) {
                    addQueryParameter("sort", defaultSort)
                }
                filters.filterIsInstance<UrlFilter>().forEach { it.addToUrl(this) }
            }
            .build()

        val result = client.get(url).parseAs<MangaListDto>()
        val mangas = result.comics
            .distinctBy(MangaDto::stableKey)
            .map(MangaDto::toSManga)
        return MangasPage(mangas, result.hasNextPage)
    }

    // =====================Details & Chapters=====================

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.pathSegments.firstOrNull() !in MANGA_PATH_SEGMENTS) return null
        val slug = url.pathSegments.getOrNull(1)?.takeIf(String::isNotEmpty) ?: return null
        val manga = SManga.create().apply { this.url = "/manga/$slug" }

        return fetchMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false)
            .manga
            .apply { initialized = true }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val slug = manga.slug

        val details = async {
            if (fetchDetails) {
                client.get("$apiUrl/comics/slug/$slug").parseAs<MangaDto>().toSManga().apply {
                    this.url = "/manga/$slug"
                }
            } else {
                manga
            }
        }

        val chapterList = async {
            if (fetchChapters) {
                val url = "$apiUrl/comics/slug/$slug/chapters".toHttpUrl().newBuilder()
                    .addQueryParameter("page", "1")
                    .addQueryParameter("per_page", CHAPTER_PAGE_SIZE.toString())
                    .addQueryParameter("sort", "newest")
                    .build()

                client.get(url).parseAs<ChapterListDto>().chapters.map { it.toSChapter(slug) }
            } else {
                chapters
            }
        }

        SMangaUpdate(manga = details.await(), chapters = chapterList.await())
    }

    // =====================Pages=====================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterId = extractChapterId(chapter)
        return client.get("$apiUrl/chapters/$chapterId?skip_view=true")
            .parseAs<ChapterPagesDto>()
            .toPageList()
    }

    private fun extractChapterId(chapter: SChapter): String {
        val url = chapter.url
        val nixId = url.substringAfter("?nix_id=", "")
            .substringAfter("&nix_id=", "")
            .substringBefore("&")
        if (nixId.isNotEmpty()) return nixId

        val paramId = url.substringAfter("?id=", "")
            .substringAfter("&id=", "")
            .substringBefore("&")
        if (paramId.isNotEmpty()) return paramId

        return url.substringAfterLast("/").substringBefore("?")
    }

    // =====================Filters=====================

    override val supportsFilterFetching: Boolean get() = true

    override suspend fun fetchFilterData(): JsonElement = client.get("$apiUrl/genres")
        .parseAs<GenreListDto>()
        .data
        .filter { it.slug.isNotEmpty() }
        .toJsonElement()

    override fun getFilterList(data: JsonElement?): FilterList {
        val genres = data?.parseAs<List<GenreDto>>()
            ?: return FilterList(SortFilter(), TypeFilter(), StatusFilter(), DemographicFilter(), ContentFilter())

        return FilterList(
            SortFilter(),
            TypeFilter(),
            StatusFilter(),
            DemographicFilter(),
            ContentFilter(),
            GenreFilter(genres),
        )
    }

    // =====================Urls=====================

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/manga/${manga.slug}"

    override fun getChapterUrl(chapter: SChapter): String {
        val mangaSlug = chapter.memo["mangaSlug"]?.stringOrNull
        val slug = chapter.memo["slug"]?.stringOrNull
        if (mangaSlug != null && slug != null) {
            return "$baseUrl/read/$mangaSlug/$slug"
        }
        if (chapter.url.contains("/read/")) {
            return "$baseUrl${chapter.url.substringBefore('?')}"
        }
        return "$baseUrl/read/${chapter.url}"
    }

    private val SManga.slug: String
        get() = url
            .removePrefix("https://noxmangas.org")
            .removePrefix("https://noxtoons.com")
            .removePrefix("https://loobyt.org")
            .removePrefix("http://noxmangas.org")
            .removePrefix("http://noxtoons.com")
            .removePrefix("http://loobyt.org")
            .substringAfter("/manga/")
            .substringAfter("/read/")
            .substringAfter("/ler/")
            .trim('/')
            .substringBefore('?')
            .substringBefore('/')

    companion object {
        private const val PAGE_SIZE = 24
        private const val CHAPTER_PAGE_SIZE = 10000
        private val MANGA_PATH_SEGMENTS = listOf("manga", "read", "ler")
    }
}
