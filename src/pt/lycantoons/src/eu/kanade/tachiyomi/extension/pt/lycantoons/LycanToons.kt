package eu.kanade.tachiyomi.extension.pt.lycantoons

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import okhttp3.Request
import okhttp3.Response
import rx.Observable

@Source
abstract class LycanToons : HttpSource() {

    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .addInterceptor(WebViewInterceptor(baseUrl, headers["User-Agent"]))
        .rateLimit(2)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // =====================Popular=====================

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/api/metrics/popular?limit=$PAGE_LIMIT&page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage = response.parseAs<PopularResponse>().toMangasPage()

    // =====================Latest=====================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/api/metrics/recently-updated?limit=$PAGE_LIMIT&page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = response.parseAs<PopularResponse>().toMangasPage()

    // =====================Search=====================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var search = query
        val tags = filters.selectedTags().toMutableList()

        val genreEntry = tagMapping.entries.find { it.value.equals(query, ignoreCase = true) }
        if (genreEntry != null) {
            tags.add(genreEntry.key)
            search = ""
        }

        val payload = SearchRequestBody(
            limit = PAGE_LIMIT,
            page = page,
            search = search,
            seriesType = filters.valueOrEmpty<SeriesTypeFilter>(),
            status = filters.valueOrEmpty<StatusFilter>(),
            tags = tags.distinct(),
        )

        return POST("$baseUrl/api/series", headers, payload.toJsonRequestBody())
    }

    override fun searchMangaParse(response: Response): MangasPage = response.parseAs<SearchResponse>().toMangasPage()

    override fun getFilterList(): FilterList = LycanToonsFilters.get()

    // =====================Details=====================

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$baseUrl/series/${manga.slug()}", headers)

    override fun mangaDetailsParse(response: Response): SManga = response.extractNextJs<SeriesDto>()?.toSManga()
        ?: error("O site alterou o formato da página da obra.")

    // =====================Chapters=====================

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable {
        val slug = manga.slug()
        val firstPage = client.newCall(
            GET("$baseUrl/api/series/$slug/chapters?skip=0&take=$CHAPTER_LIMIT", headers),
        ).execute().use { response ->
            if (response.header("Content-Type")?.contains("json") == true) {
                response.parseAs<ChapterListDto>()
            } else {
                // Not JSON – site likely returned a Cloudflare challenge page
                // Touch the series page to refresh cookies, then retry
                client.newCall(GET("$baseUrl/series/$slug", headers)).execute().close()
                client.newCall(
                    GET("$baseUrl/api/series/$slug/chapters?skip=0&take=$CHAPTER_LIMIT", headers),
                ).execute().use { it.parseAs<ChapterListDto>() }
            }
        }

        val allChapters = firstPage.chapters.toMutableList()
        val total = firstPage.total

        if (allChapters.size < total) {
            for (skip in CHAPTER_LIMIT until total step CHAPTER_LIMIT) {
                val page = client.newCall(
                    GET("$baseUrl/api/series/$slug/chapters?skip=$skip&take=$CHAPTER_LIMIT", headers),
                ).execute().use { it.parseAs<ChapterListDto>() }
                allChapters.addAll(page.chapters)
            }
        }

        allChapters
            .map { it.toSChapter(slug) }
            .sortedByDescending { it.chapter_number }
    }

    override fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException()

    // =====================Pages========================

    override fun pageListRequest(chapter: SChapter): Request = GET("$baseUrl${chapter.url}", headers)

    override fun pageListParse(response: Response): List<Page> {
        val dto = response.extractNextJs<PageList>()
        return dto?.imageUrls?.mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }
            ?: emptyList()
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // =====================Utils=====================

    private fun SManga.slug(): String = url.substringBefore("?").substringAfterLast("/")

    companion object {
        private const val PAGE_LIMIT = 20
        private const val CHAPTER_LIMIT = 100
    }
}
