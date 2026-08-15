package eu.kanade.tachiyomi.extension.pt.corujatoon

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
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Source
abstract class CorujaToon : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(2)

    override suspend fun getPopularManga(page: Int): MangasPage = getSeries(page, sort = "popular")

    override suspend fun getLatestUpdates(page: Int): MangasPage = getSeries(page, sort = "recent")

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val genre = filters.firstInstanceOrNull<GenreFilter>()?.selectedValue.orEmpty()
        val type = filters.firstInstanceOrNull<TypeFilter>()?.selectedValue.orEmpty()
        val status = filters.firstInstanceOrNull<StatusFilter>()?.selectedValue.orEmpty()
        return getSeries(page, query, "recent", genre, type, status)
    }

    private suspend fun getSeries(
        page: Int,
        query: String = "",
        sort: String,
        genre: String = "",
        type: String = "",
        status: String = "",
    ): MangasPage {
        val url = "$baseUrl/api/series/list".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("sort", sort)
            .apply {
                query.trim().takeIf(String::isNotEmpty)?.let { addQueryParameter("search", it) }
                genre.takeIf(String::isNotEmpty)?.let { addQueryParameter("genre", it) }
                type.takeIf(String::isNotEmpty)?.let { addQueryParameter("type", it) }
                status.takeIf(String::isNotEmpty)?.let { addQueryParameter("status", it) }
            }
            .build()

        val result = client.get(url).parseAs<SeriesListDto>()
        return MangasPage(result.series.map(SeriesDto::toSManga), result.pagination.page < result.pagination.totalPages)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.pathSegments.firstOrNull() != "series") return null
        val slug = url.pathSegments.getOrNull(1) ?: return null
        val id = url.queryParameter("sourceId") ?: return null
        return SManga.create().apply {
            this.url = "$slug|$id"
            title = slug.replace('-', ' ').replaceFirstChar(Char::uppercase)
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val parts = manga.url.split('|', limit = 2)
        val id = parts.getOrNull(1) ?: throw Exception("URL da obra inválida. Abra a obra novamente.")
        val details = if (fetchDetails) {
            async {
                client.get("$baseUrl/api/series/$id").parseAs<SeriesResponseDto>().series.toSManga()
            }
        } else {
            null
        }
        val chapterList = if (fetchChapters) {
            async {
                client.get("$baseUrl/api/series/$id").parseAs<SeriesResponseDto>().series.chapter
                    .sortedByDescending { it.number }
                    .map { it.toSChapter(parts[0]) }
            }
        } else {
            null
        }

        SMangaUpdate(details?.await() ?: manga, chapterList?.await() ?: chapters)
    }

    override fun getMangaUrl(manga: SManga): String {
        val (slug, id) = manga.url.split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        return "$baseUrl/series/$slug".toHttpUrl().newBuilder()
            .addQueryParameter("sourceId", id)
            .build()
            .toString()
    }

    override fun getChapterUrl(chapter: SChapter): String {
        val slug = chapter.memo["slug"]?.toString()?.trim('"') ?: throw Exception("Atualize a lista de capítulos")
        val number = chapter.memo["number"]?.toString()?.trim('"') ?: throw Exception("Atualize a lista de capítulos")
        return "$baseUrl/series/$slug/capitulo/$number"
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val id = chapter.memo["id"]?.toString()?.trim('"') ?: throw Exception("Atualize a lista de capítulos")
        val response = client.get("$baseUrl/api/chapters/$id").parseAs<ChapterResponseDto>()
        require(response.chapter.pages.isNotEmpty()) { "O servidor não retornou páginas para este capítulo." }
        return response.chapter.pages.mapIndexed { index, url -> Page(index, imageUrl = url) }
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        GenreFilter(),
        TypeFilter(),
        StatusFilter(),
    )

    private companion object {
        const val PAGE_SIZE = 24
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
}
