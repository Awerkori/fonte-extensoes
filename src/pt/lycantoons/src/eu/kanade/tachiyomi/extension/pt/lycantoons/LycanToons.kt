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
import kotlinx.serialization.Serializable
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

    override fun mangaDetailsRequest(manga: SManga): Request = rscRequest("$baseUrl/series/${manga.slug()}")

    override fun mangaDetailsParse(response: Response): SManga {
        if (response.isCloudflareChallenge()) throw CloudflareBlockedException()
        return response.extractNextJs<SeriesDto>()!!.toSManga()
    }

    // =====================Chapters=====================

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable {
        val slug = manga.slug()

        try {
            val response = client.newCall(chapterPageRequest(slug)).execute()
            if (response.isCloudflareChallenge()) throw CloudflareBlockedException()

            response.extractNextJs<ChapterResponse>()?.capitulos!!
                .map { it.toSChapter(slug) }
                .sortedByDescending { it.chapter_number }
        } catch (e: Exception) {
            if (e is CloudflareBlockedException) throw e
            if (e.isCloudflareFailure()) throw CloudflareBlockedException(e)
            throw e
        }
    }

    private fun chapterPageRequest(slug: String): Request = rscRequest("$baseUrl/series/$slug/1")

    override fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException()

    // =====================Pages========================

    override fun pageListRequest(chapter: SChapter): Request = rscRequest("$baseUrl${chapter.url}")

    override fun pageListParse(response: Response): List<Page> {
        val html = response.body.string()
        if (response.isCloudflareChallenge(html)) throw CloudflareBlockedException()

        val capituloIdMatch = """\\?"capituloId\\?"\s*:\s*(\d+)""".toRegex().find(html)
        if (capituloIdMatch != null) {
            val capituloId = capituloIdMatch.groupValues[1]

            try {
                val apiUrl = "$baseUrl/api/chapters/$capituloId/view-pages"
                val apiRequest = GET(apiUrl, headers)
                val apiResponse = network.client.newCall(apiRequest).execute()
                val apiJson = apiResponse.body.string()
                if (apiResponse.isCloudflareChallenge(apiJson)) throw CloudflareBlockedException()

                val imageUrls = mutableListOf<String>()
                val urlRegex = """(https?:\/\/[^"]+\.(?:jpg|png|webp|jpeg)[^"\\]*)""".toRegex(RegexOption.IGNORE_CASE)

                urlRegex.findAll(apiJson.replace("\\/", "/")).forEach {
                    val url = it.groupValues[1]
                    if (url.contains("lycantoons.com")) {
                        imageUrls.add(url)
                    }
                }

                if (imageUrls.isNotEmpty()) {
                    return imageUrls.distinct().mapIndexed { index, imageUrl ->
                        Page(index, imageUrl = imageUrl)
                    }
                }
            } catch (e: Exception) {
                if (e is CloudflareBlockedException) throw e
                if (e.isCloudflareFailure()) throw CloudflareBlockedException(e)
                throw Exception("Nenhuma página! " + e.message)
            }
        }

        throw Exception("Nenhuma página encontrada!")
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

@Serializable
class ChapterResponse(val capitulos: List<ChapterDto>)

private fun Response.isCloudflareChallenge(body: String = peekBody(1_048_576).string()): Boolean = body.hasCloudflareChallenge() || request.url.toString().hasCloudflareChallenge()
