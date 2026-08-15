package eu.kanade.tachiyomi.extension.pt.zinnes

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
import keiyoushi.utils.toJsonElement
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response

@Source
abstract class Zinnes : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(2)

    override suspend fun getPopularManga(page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        return getSection("dominaram") { it.dominaram }
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        return getSection("latest_releases") { it.latestReleases }
    }

    private suspend fun getSection(section: String, selector: (SectionDataDto) -> List<ProjectDto>?): MangasPage {
        val response = apiGet("$API_BASE_URL/api/home/section/$section").parseAs<SectionResponseDto>()
        val items = selector(response.data)
            ?: throw Exception("Resposta inesperada da seção $section: dados ausentes.")
        require(items.isNotEmpty()) { "A seção $section não retornou obras." }
        return MangasPage(items.map(ProjectDto::toSManga), false)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val genre = filters.firstInstanceOrNull<GenreFilter>()?.selectedValue
        val language = filters.firstInstanceOrNull<LanguageFilter>()?.selectedValue
        val type = filters.firstInstanceOrNull<TypeFilter>()?.selectedValue
        val url = "$API_BASE_URL/api/search/projects".toHttpUrl().newBuilder()
            .addQueryParameter("name", query.trim())
            .addQueryParameter("project_category_id", "1")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("per_page", PAGE_SIZE.toString())
            .apply {
                genre?.let { addQueryParameter("gender_id", it) }
                language?.let { addQueryParameter("language_id", it) }
                type?.let { addQueryParameter("project_type_id", it) }
            }
            .build()

        val response = apiGet(url.toString()).parseAs<SearchResponseDto>()
        return MangasPage(
            response.data.data.map(ProjectDto::toSManga),
            response.data.currentPage < response.data.lastPage,
        )
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.pathSegments.firstOrNull() != "obra") return null
        val id = url.pathSegments.getOrNull(1)?.toIntOrNull() ?: return null
        return SManga.create().apply {
            this.url = id.toString()
            title = url.pathSegments.getOrNull(2)?.replace('-', ' ')?.replaceFirstChar(Char::uppercase).orEmpty()
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val id = manga.url.toIntOrNull() ?: throw Exception("URL da obra inválida. Abra a obra novamente.")
        val response = if (fetchDetails || fetchChapters) {
            async { apiGet("$API_BASE_URL/api/projects/chapters/$id").parseAs<DetailsResponseDto>() }
        } else {
            null
        }
        val details = if (fetchDetails) async { response!!.await().data.project.toSManga() } else null
        val chapterList = if (fetchChapters) {
            async {
                response!!.await().data.titles
                    .sortedWith(compareByDescending<TitleDto> { it.chapterNumber }.thenByDescending { it.releaseDate })
                    .map { it.toSChapter() }
            }
        } else {
            null
        }

        SMangaUpdate(details?.await() ?: manga, chapterList?.await() ?: chapters)
    }

    override fun getMangaUrl(manga: SManga): String {
        val id = manga.url.toIntOrNull() ?: throw Exception("URL da obra inválida")
        return "$baseUrl/obra/$id".toHttpUrl().toString()
    }

    override fun getChapterUrl(chapter: SChapter): String {
        val id = chapter.memo["id"]?.toString()?.trim('"') ?: throw Exception("Atualize a lista de capítulos")
        return "$baseUrl/$id/page"
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val id = chapter.memo["id"]?.toString()?.trim('"') ?: throw Exception("Atualize a lista de capítulos")
        val response = apiGet("$API_BASE_URL/api/titles/$id/view").parseAs<TitleViewResponseDto>()
        require(response.data.pages.isNotEmpty()) { "O servidor não retornou páginas para este capítulo." }
        return response.data.pages.filter { it.active != 0 }.sortedBy { it.page }.mapIndexed { index, page ->
            Page(index, imageUrl = page.image)
        }
    }

    override val supportsFilterFetching: Boolean get() = true

    override suspend fun fetchFilterData(): JsonElement = coroutineScope {
        val genres = async { apiGet("$API_BASE_URL/api/gender").parseAs<List<FilterOptionDto>>() }
        val languages = async { apiGet("$API_BASE_URL/api/language").parseAs<List<FilterOptionDto>>() }
        val types = async { apiGet("$API_BASE_URL/api/project_type?project_category_id=1").parseAs<List<FilterOptionDto>>() }
        FilterData(genres.await(), languages.await(), types.await()).toJsonElement()
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val filterData = data?.parseAs<FilterData>() ?: return FilterList()
        return FilterList(
            GenreFilter(filterData.genres),
            LanguageFilter(filterData.languages),
            TypeFilter(filterData.types),
        )
    }

    private suspend fun apiGet(url: String): Response {
        val response = client.get(url, apiHeaders, ensureSuccess = false)
        if (response.isSuccessful) return response

        val message = when (response.code) {
            401 -> "O Zinnes exige uma sessão autenticada para este conteúdo."
            403 -> "O Zinnes recusou acesso a este conteúdo; ele pode exigir uma permissão adicional."
            404 -> "O conteúdo solicitado não foi encontrado no Zinnes."
            429 -> "O Zinnes limitou temporariamente as requisições. Tente novamente mais tarde."
            in 500..599 -> "O servidor do Zinnes está com problemas temporários."
            else -> "O Zinnes respondeu com HTTP ${response.code}."
        }
        response.close()
        throw Exception(message)
    }

    private val apiHeaders
        get() = headers.newBuilder()
            .set("Origin", baseUrl)
            .set("Referer", "$baseUrl/")
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Accept", "application/json, text/plain, */*")
            .build()

    private companion object {
        const val PAGE_SIZE = 15
        const val API_BASE_URL = "https://apizinnes.com.br"
    }
}
