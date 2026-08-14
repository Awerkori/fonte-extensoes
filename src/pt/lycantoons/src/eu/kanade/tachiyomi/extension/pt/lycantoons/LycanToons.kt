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

    override fun popularMangaRequest(page: Int): Request = metricsRequest("popular", page)

    override fun popularMangaParse(response: Response): MangasPage = response.parseAs<PopularResponse>().toMangasPage()

    // =====================Latest=====================

    override fun latestUpdatesRequest(page: Int): Request = metricsRequest("recently-updated", page)

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

    override fun mangaDetailsRequest(manga: SManga): Request {
        val payload = SearchRequestBody(
            limit = 20,
            page = 1,
            search = manga.title,
            seriesType = "",
            status = "",
            tags = emptyList(),
        )

        return POST("$baseUrl/api/series?slug=${manga.slug()}", headers, payload.toJsonRequestBody())
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val slug = response.request.url.queryParameter("slug")!!
        return response.parseAs<SearchResponse>().toSManga(slug)
    }

    // =====================Chapters=====================

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable {
        val slug = manga.slug()
        val chapters = mutableListOf<SChapter>()
        var skip = 0
        var total: Int? = null

        do {
            val page = client.newCall(chapterListRequest(slug, skip)).execute().use { response ->
                response.parseAs<ChapterResponse>()
            }

            if (total == null) {
                total = page.total
            } else {
                check(page.total == total) { "A quantidade de capítulos mudou durante a paginação. Tente novamente." }
            }

            val batch = page.toSChapters(slug)
            check(batch.isNotEmpty() || skip >= page.total) {
                "A API interrompeu a lista de capítulos em $skip/${page.total}."
            }

            chapters += batch
            skip += page.size
        } while (skip < total)

        val uniqueChapters = chapters.distinctBy { it.url }
        check(uniqueChapters.size == total) {
            "A API retornou uma lista de capítulos incompleta: ${uniqueChapters.size}/$total."
        }

        uniqueChapters.sortedByDescending { it.chapter_number }
    }

    private fun chapterListRequest(slug: String, skip: Int) = GET("$baseUrl/api/series/$slug/chapters?skip=$skip&take=$CHAPTER_LIMIT", headers)

    override fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException()

    // =====================Pages========================

    override fun pageListRequest(chapter: SChapter): Request = rscRequest("$baseUrl${chapter.url}")

    override fun pageListParse(response: Response): List<Page> {
        val dto = response.extractNextJs<PageList>()
            ?: error("O site alterou o formato da página do capítulo.")

        check(dto.imageUrls.isNotEmpty()) { "O site não retornou imagens para este capítulo." }

        return dto.imageUrls.mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // =====================Utils=====================

    private fun metricsRequest(path: String, page: Int): Request = GET("$baseUrl/api/metrics/$path?limit=$PAGE_LIMIT&page=$page", headers)

    private fun SManga.slug(): String = url.substringBefore("?").substringAfterLast("/")

    private fun String.rscBust() = "$this?_rsc=${List(5) { BASE36.random() }.joinToString("")}"

    private fun getRscHeaders(url: String) = headers.newBuilder()
        .add("next-router-state-tree", NEXT_ROUTER)
        .add("next-url", url.removePrefix(baseUrl))
        .add("RSC", "1")
        .build()

    private fun rscRequest(url: String) = GET(url.substringBefore("?").rscBust(), getRscHeaders(url))

    companion object {
        private const val PAGE_LIMIT = 20
        private const val CHAPTER_LIMIT = 100
        private const val BASE36 = "0123456789abcdefghijklmnopqrstuvwxyz"
        private const val NEXT_ROUTER = "%5B%22%22%2C%7B%22children%22%3A%5B%22__PAGE__%22%2C%7B%7D%2Cnull%2Cnull%5D%7D%2Cnull%2Cnull%2Ctrue%5D"
    }
}
