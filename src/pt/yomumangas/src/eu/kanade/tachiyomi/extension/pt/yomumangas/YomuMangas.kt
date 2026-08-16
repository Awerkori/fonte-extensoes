package eu.kanade.tachiyomi.extension.pt.yomumangas

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Source
abstract class YomuMangas : KeiSource() {

    private val apiUrl = "https://api.yomumangas.com"
    override val supportsLatest = true

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    // ---------- Helpers ----------
    private fun String.replaceB2Uri(): String = replace("b2://", "https://b2.yomumangas.com/")

    // API requires query param to be present (even as empty string); omitting it returns 400.
    private fun catalogUrl(page: Int): String = "$apiUrl/mangas".toHttpUrl().newBuilder()
        .addQueryParameter("query", "")
        .addQueryParameter("page", page.toString())
        .build()
        .toString()

    // ---------- Popular ----------
    override suspend fun getPopularManga(page: Int): MangasPage {
        val response = client.newCall(GET(catalogUrl(page), headers)).execute()
        val dto = response.parseAs<SearchResponse>()
        return MangasPage(dto.mangas.map { it.toSManga() }, page < dto.pages)
    }

    // ---------- Latest Updates ----------
    // /home returns the 25 most recently updated mangas in the `updates` field (no pagination).
    override suspend fun getLatestUpdates(page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        val response = client.newCall(GET("$apiUrl/home", headers)).execute()
        val dto = response.parseAs<HomeResponse>()
        return MangasPage(dto.updates.map { it.toSManga() }, false)
    }

    // ---------- Search ----------
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val urlBuilder = "$apiUrl/mangas".toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("page", page.toString())
        filters.forEach { filter ->
            when (filter) {
                is TypeFilter -> if (filter.toUriPart().isNotEmpty()) urlBuilder.addQueryParameter("type", filter.toUriPart())
                is StatusFilter -> if (filter.toUriPart().isNotEmpty()) urlBuilder.addQueryParameter("status", filter.toUriPart())
                is NsfwFilter -> if (filter.toUriPart().isNotEmpty()) urlBuilder.addQueryParameter("nsfw", filter.toUriPart())
                is GenreFilter -> {
                    val selected = filter.state.filter { it.state }.map { it.id }
                    if (selected.isNotEmpty()) urlBuilder.addQueryParameter("genres", selected.joinToString(","))
                }
                is TagFilter -> {
                    val selected = filter.state.filter { it.state }.map { it.id }
                    if (selected.isNotEmpty()) urlBuilder.addQueryParameter("tags", selected.joinToString(","))
                }
                else -> {}
            }
        }
        val response = client.newCall(GET(urlBuilder.build(), headers)).execute()
        val dto = response.parseAs<SearchResponse>()
        val currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        return MangasPage(dto.mangas.map { it.toSManga() }, currentPage < dto.pages)
    }

    // ---------- Manga URL ----------
    override fun getMangaUrl(manga: SManga): String {
        val (id, slug) = manga.url.split("#", limit = 2)
        return "$baseUrl/mangas/$id/$slug"
    }

    // ---------- Update Fetch (details + chapters via KeiSource) ----------
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val (id, slug) = manga.url.split("#", limit = 2)
        val updatedManga = if (fetchDetails) {
            val response = client.newCall(GET("$apiUrl/mangas/$id", headers)).execute()
            response.parseAs<MangaDetailsResponse>().manga.toSManga()
        } else {
            manga
        }
        val updatedChapters = if (fetchChapters) {
            val response = client.newCall(GET("$apiUrl/mangas/$id/chapters", headers)).execute()
            val dto = response.parseAs<ChaptersResponse>()
            dto.chapters.map { it.toSChapter(id, slug, dateFormat) }.reversed()
        } else {
            chapters
        }
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    // ---------- Pages ----------
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = if (chapter.url.startsWith("http")) chapter.url else "$baseUrl${chapter.url}"
        val response = client.newCall(GET(chapterUrl, headers)).execute()
        val html = response.body.string()
        val pages = URI_REGEX.findAll(html).mapIndexed { index, matchResult ->
            Page(index, imageUrl = matchResult.value.replaceB2Uri())
        }.toList()
        if (pages.isEmpty()) {
            throw Exception("Nenhuma página encontrada. O layout do site pode ter mudado.")
        }
        return pages
    }

    // ---------- Filters ----------
    // KeiSource.getFilterList() (no-arg) is final; override the data-parameterised version.
    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        TypeFilter(),
        StatusFilter(),
        NsfwFilter(),
        Filter.Separator(),
        GenreFilter(getGenresList()),
        Filter.Separator(),
        TagFilter(getTagsList()),
    )

    // ---------- Utilities ----------
    companion object {
        private val URI_REGEX = """b2://chapters/[^"\\]+""".toRegex()
    }
}
