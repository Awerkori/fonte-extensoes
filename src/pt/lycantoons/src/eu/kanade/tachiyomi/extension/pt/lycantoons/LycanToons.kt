package eu.kanade.tachiyomi.extension.pt.lycantoons

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import kotlin.time.Duration.Companion.seconds

@Source
abstract class LycanToons : HttpSource() {

    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .rateLimit(2, 1.seconds) { it.host == baseUrl.toHttpUrl().host }
        .build()

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .set("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
        .set("Referer", "$baseUrl/")

    // =====================Popular=====================

    override fun popularMangaRequest(page: Int): Request = if (page == 1) {
        GET("$baseUrl/ranking/obras", headers)
    } else {
        GET("$baseUrl/ranking/obras?page=$page", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        if (page > 1) {
            return MangasPage(emptyList(), false)
        }

        val html = response.body.string()

        val match = RANKING_ALL_REGEX.find(html) ?: RANKING_WEEKLY_REGEX.find(html)
        val jsonString = match?.groupValues?.get(1)?.replace("\\\"", "\"")?.replace("\\\\", "\\")
            ?: return MangasPage(emptyList(), false)

        val series = json.decodeFromString<List<RankingRowDto>>(jsonString)

        return MangasPage(series.map { it.toSManga() }, false)
    }

    // =====================Latest=====================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/series?page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val html = response.body.string()

        val match = SERIES_REGEX.find(html)
        val jsonString = match?.groupValues?.get(1)?.replace("\\\"", "\"")?.replace("\\\\", "\\")
            ?: return MangasPage(emptyList(), false)

        val series = json.decodeFromString<List<SeriesDto>>(jsonString)
        val totalPages = TOTAL_PAGES_REGEX.find(html)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1

        return MangasPage(series.map { it.toSManga() }, currentPage < totalPages)
    }

    // =====================Search=====================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/series".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())

        val trimmed = query.trim()
        if (trimmed.isNotEmpty()) {
            url.fragment(trimmed)
        }

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val html = response.body.string()

        val match = SERIES_REGEX.find(html)
        val jsonString = match?.groupValues?.get(1)?.replace("\\\"", "\"")?.replace("\\\\", "\\")
            ?: return MangasPage(emptyList(), false)

        val series = json.decodeFromString<List<SeriesDto>>(jsonString)
        val totalPages = TOTAL_PAGES_REGEX.find(html)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1

        val query = response.request.url.fragment
        val resultSeries = if (!query.isNullOrBlank()) {
            series.filter { it.title.contains(query, ignoreCase = true) }
        } else {
            series
        }

        return MangasPage(resultSeries.map { it.toSManga() }, currentPage < totalPages)
    }

    override fun getFilterList(): FilterList = LycanToonsFilters.get()

    // =====================Details=====================

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$baseUrl${manga.url}", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val html = response.body.string()

        return SManga.create().apply {
            title = OG_TITLE_REGEX.find(html)?.groupValues?.get(1) ?: ""
            thumbnail_url = OG_IMAGE_REGEX.find(html)?.groupValues?.get(1)
            description = OG_DESC_REGEX.find(html)?.groupValues?.get(1)

            val genresMatch = GENRES_REGEX.find(html)
            if (genresMatch != null) {
                val g = genresMatch.groupValues[1]
                genre = """"([^"]+)"""".toRegex().findAll(g).map { it.groupValues[1] }
                    .map { tagMapping[it] ?: it }
                    .joinToString()
            }

            status = when (STATUS_REGEX.find(html)?.groupValues?.get(1)?.lowercase()) {
                "ongoing" -> SManga.ONGOING
                "completed" -> SManga.COMPLETED
                "hiatus" -> SManga.ON_HIATUS
                "cancelled" -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
            initialized = true
        }
    }

    // =====================Chapters=====================

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable {
        val slug = manga.slug()

        try {
            val response = client.newCall(chapterPageRequest(slug)).execute()
            val html = response.body.string()

            val chaptersMatch = CHAPTERS_REGEX.find(html) ?: CAPITULOS_REGEX.find(html)

            if (chaptersMatch != null) {
                val jsonString = chaptersMatch.groupValues[1]
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                json.decodeFromString<List<ChapterDto>>(jsonString)
                    .map { it.toSChapter(slug) }
                    .sortedByDescending { it.chapter_number }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            if (e is CloudflareBlockedException) throw e
            if (e.isCloudflareFailure()) throw CloudflareBlockedException(e)
            throw e
        }
    }

    private fun chapterPageRequest(slug: String): Request = GET("$baseUrl/series/$slug", headers)

    override fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException()

    // =====================Pages========================

    override fun pageListRequest(chapter: SChapter): Request {
        val capituloId = chapter.url.substringAfter("?capituloId=", "")
            .ifEmpty { chapter.url.substringAfter("&capituloId=", "") }
            .substringBefore("&")

        return if (capituloId.isNotEmpty()) {
            GET("$baseUrl/api/chapters/$capituloId/view-pages", headers)
        } else {
            GET("$baseUrl${chapter.url}", headers)
        }
    }

    override fun pageListParse(response: Response): List<Page> {
        val body = response.body.string()

        // 1. Resposta direta da API JSON (/api/chapters/{id}/view-pages)
        if (body.trimStart().startsWith("{")) {
            try {
                val pageListDto = json.decodeFromString<ChapterPagesDto>(body)
                if (pageListDto.pages.isNotEmpty()) {
                    return pageListDto.pages.mapIndexed { index, imageUrl ->
                        Page(index, imageUrl = imageUrl)
                    }
                }
            } catch (_: Exception) {}
        }

        // 2. Fallback para HTML legado (/series/{slug}/{number})
        val capituloIdMatch = CAPITULO_ID_REGEX.find(body)
        if (capituloIdMatch != null) {
            val capituloId = capituloIdMatch.groupValues[1]
            try {
                val apiUrl = "$baseUrl/api/chapters/$capituloId/view-pages"
                val apiRequest = GET(apiUrl, headers)
                val apiResponse = network.client.newCall(apiRequest).execute()
                val apiJson = apiResponse.body.string()
                val pageListDto = json.decodeFromString<ChapterPagesDto>(apiJson)
                if (pageListDto.pages.isNotEmpty()) {
                    return pageListDto.pages.mapIndexed { index, imageUrl ->
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

    override fun imageRequest(page: Page): Request = GET(
        url = page.imageUrl!!,
        headers = headersBuilder()
            .set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .set("Referer", "$baseUrl/")
            .build(),
    )

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

        private val SERIES_REGEX = """\\"series\\"\s*:\s*(\[\{.*?\}\])""".toRegex()
        private val RANKING_ALL_REGEX = """\\"lidas:all\\"\s*:\s*\{.*?\\"rows\\"\s*:\s*(\[\{.*?\}\])\s*\}""".toRegex()
        private val RANKING_WEEKLY_REGEX = """\\"lidas:weekly\\"\s*:\s*\{.*?\\"rows\\"\s*:\s*(\[\{.*?\}\])\s*\}""".toRegex()
        private val TOTAL_PAGES_REGEX = """\\"totalPages\\"\s*:\s*(\d+)""".toRegex()
        private val CAPITULO_ID_REGEX = """\\?"capituloId\\?"\s*:\s*(\d+)""".toRegex()
        private val CHAPTERS_REGEX = """\\"chapters\\"\s*:\s*(\[\{.*?\}\])""".toRegex()
        private val CAPITULOS_REGEX = """\\"capitulos\\"\s*:\s*(\[\{.*?\}\])""".toRegex()
        private val GENRES_REGEX = """\\"genre\\":\[(.*?)\]""".toRegex()
        private val STATUS_REGEX = """\\"status\\"\s*:\s*\\"([^"]+)\\"""".toRegex(RegexOption.IGNORE_CASE)
        private val OG_TITLE_REGEX = """property="og:title"\s*content="([^"]+)"""".toRegex()
        private val OG_IMAGE_REGEX = """property="og:image"\s*content="([^"]+)"""".toRegex()
        private val OG_DESC_REGEX = """property="og:description"\s*content="([^"]+)"""".toRegex()
    }
}

@Serializable
class ChapterResponse(val capitulos: List<ChapterDto>)

private fun Response.isCloudflareChallenge(body: String = peekBody(1_048_576).string()): Boolean = body.hasCloudflareChallenge() || request.url.toString().hasCloudflareChallenge()
