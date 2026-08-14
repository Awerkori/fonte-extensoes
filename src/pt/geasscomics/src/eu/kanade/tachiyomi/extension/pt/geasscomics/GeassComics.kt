package eu.kanade.tachiyomi.extension.pt.geasscomics

import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.source.ConfigurableSource
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
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

@Source
abstract class GeassComics :
    KeiSource(),
    ConfigurableSource {

    private val apiUrl = "https://api.geasscomics.xyz"

    private val preferences by getPreferencesLazy()

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(2)

    override fun Headers.Builder.configureHeaders(): Headers.Builder = this
        .add("Accept", "application/json, text/plain, */*")

    override suspend fun getPopularManga(page: Int): MangasPage = getMangaList(page, "rating")

    override suspend fun getLatestUpdates(page: Int): MangasPage = getMangaList(page, "recent")

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val sort = filters.filterIsInstance<SortFilter>().firstOrNull()
        val types = filters.filterIsInstance<TypeFilter>().firstOrNull()?.selectedValues.orEmpty()
        val genres = filters.filterIsInstance<GenreFilter>().firstOrNull()?.selectedValues.orEmpty()
        val tags = filters.filterIsInstance<TagFilter>().firstOrNull()?.selectedValues.orEmpty()

        return getMangaList(
            page = page,
            sortBy = sort?.sortBy ?: "recent",
            sortDir = sort?.sortDir ?: "desc",
            query = query,
            types = types,
            genres = genres,
            tags = tags,
        )
    }

    private suspend fun getMangaList(
        page: Int,
        sortBy: String,
        sortDir: String = "desc",
        query: String = "",
        types: List<String> = emptyList(),
        genres: List<String> = emptyList(),
        tags: List<String> = emptyList(),
    ): MangasPage {
        val url = "$apiUrl/api/works".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", PAGE_LIMIT.toString())
            .apply {
                query.trim().takeIf(String::isNotEmpty)?.let { addQueryParameter("q", it) }
                types.takeIf(List<String>::isNotEmpty)?.let { addQueryParameter("types", it.joinToString(",")) }
                genres.takeIf(List<String>::isNotEmpty)?.let { addQueryParameter("genres", it.joinToString(",")) }
                tags.takeIf(List<String>::isNotEmpty)?.let { addQueryParameter("tags", it.joinToString(",")) }
            }
            .addQueryParameter("sortBy", sortBy)
            .addQueryParameter("sortDir", sortDir)
            .apply {
                if (!showNsfwPref()) addQueryParameter("safe", "true")
            }
            .build()

        val result = client.get(url).parseAs<ApiResponse<WorkPageDto>>().data
        return MangasPage(result.items.map { it.toSManga() }, page < result.pageCount)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val path = url.pathSegments.firstOrNull()
        val slug = url.pathSegments.getOrNull(1)
            ?.takeIf { path == "work" || path == "read" }
            ?: return null
        val manga = SManga.create().apply { this.url = slug }

        return fetchMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = true)
            .manga
            .apply { initialized = true }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val work = client.get("$apiUrl/api/works/${manga.url}").parseAs<ApiResponse<WorkDto>>().data
        return SMangaUpdate(
            manga = work.toSManga(details = true),
            chapters = work.chapters.map { it.toSChapter(work.slug) },
        )
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = client
        .get("$baseUrl/api/read/${chapter.url}")
        .parseAs<ReaderChapterDto>()
        .pages
        .mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/work/${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/read/${chapter.url}"

    override val supportsFilterFetching: Boolean get() = true

    override suspend fun fetchFilterData(): JsonElement {
        val genres = client.get("$apiUrl/api/genres").parseAs<ApiResponse<List<FilterOptionDto>>>().data
        val tags = client.get("$apiUrl/api/tags").parseAs<ApiResponse<List<FilterOptionDto>>>().data
        return FilterDataDto(genres, tags).toJsonElement()
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val filterData = data?.parseAs<FilterDataDto>()
        return getFilters(filterData, showNsfwPref())
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_ADULT_KEY
            title = "Exibir conteúdo adulto"
            summary = "Inclui obras +18 no catálogo e na pesquisa."
            setDefaultValue(false)
        }.let(screen::addPreference)
    }

    private fun showNsfwPref() = preferences.getBoolean(PREF_ADULT_KEY, false)

    companion object {
        private const val PAGE_LIMIT = 24
        private const val PREF_ADULT_KEY = "pref_adult_content"
    }
}
