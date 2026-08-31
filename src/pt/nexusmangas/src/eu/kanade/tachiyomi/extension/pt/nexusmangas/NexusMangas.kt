package eu.kanade.tachiyomi.extension.pt.nexusmangas

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.toJsonRequestBody
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

@Source
abstract class NexusMangas : KeiSource() {

    override suspend fun getPopularManga(page: Int): MangasPage {
        val ranking = client.post(
            "$API_URL/rpc/get_top_works_by_views",
            apiHeaders,
            TopWorksRequest(POPULAR_LIMIT).toJsonRequestBody(),
        ).parseAs<List<RankingDto>>()
        val pageItems = ranking.drop((page - 1) * PAGE_SIZE).take(PAGE_SIZE)
        if (pageItems.isEmpty()) return MangasPage(emptyList(), false)

        val works = getWorks(
            worksUrl().addQueryParameter("id", "in.(${pageItems.joinToString(",") { it.workId }})").build(),
        ).associateBy(WorkDto::id)
        return MangasPage(
            pageItems.mapNotNull { works[it.workId]?.toSManga() },
            ranking.size > page * PAGE_SIZE,
        )
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val releasePages = mutableListOf<List<LatestReleaseDto>>()
        repeat(page) { index -> releasePages += getLatestReleasePage(index) }
        val workIds = releasePages.flatten().map(LatestReleaseDto::workId).distinct()
        val pageIds = workIds.drop((page - 1) * PAGE_SIZE).take(PAGE_SIZE)
        val works = getWorksByIds(pageIds).associateBy(WorkDto::id)
        return MangasPage(
            pageIds.mapNotNull { works[it]?.toSManga() },
            releasePages.lastOrNull()?.size == LATEST_LIMIT,
        )
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val genre = filters.firstInstanceOrNull<GenreFilter>()?.selectedValue
        val format = filters.firstInstanceOrNull<FormatFilter>()?.selectedValue
        val type = filters.firstInstanceOrNull<TypeFilter>()?.selectedValue
        val rating = filters.firstInstanceOrNull<ContentRatingFilter>()?.selectedValue
        val select = buildString {
            append(WORK_LIST_SELECT)
            if (genre != null) append(",work_genres!inner(genre_id)")
            if (format != null) append(",work_formats!inner(format_id)")
        }
        val url = worksUrl(select)
            .addQueryParameter("order", "title.asc")
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("offset", ((page - 1) * PAGE_SIZE).toString())
            .apply {
                if (query.isNotBlank()) {
                    addQueryParameter("or", "(title.ilike.*${query.trim()}*,alternative_title.ilike.*${query.trim()}*)")
                }
                genre?.let {
                    addQueryParameter("work_genres.genre_id", "eq.$it")
                }
                format?.let {
                    addQueryParameter("work_formats.format_id", "eq.$it")
                }
                type?.let { addQueryParameter("type", "eq.$it") }
                rating?.let { addQueryParameter("content_rating", "eq.$it") }
            }
            .build()
        val works = getWorks(url)
        return MangasPage(works.map(WorkDto::toSManga), works.size == PAGE_SIZE)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.pathSegments.firstOrNull() != "obra") return null
        val slug = url.pathSegments.getOrNull(1)?.takeIf(String::isNotBlank) ?: return null
        return getWorks(
            worksUrl(WORK_DETAILS_SELECT).addQueryParameter("slug", "eq.$slug").build(),
        ).firstOrNull()?.toSManga()
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val slug = manga.url.substringAfterLast('/').takeIf(String::isNotBlank)
            ?: throw Exception("URL da obra inválida. Abra a obra novamente.")
        val work = async {
            getWorks(worksUrl(WORK_DETAILS_SELECT).addQueryParameter("slug", "eq.$slug").build()).firstOrNull()
                ?: throw Exception("Obra não encontrada no Nexus Mangas.")
        }
        val details = if (fetchDetails) async { work.await().toSManga() } else null
        val chapterList = if (fetchChapters) async { getChapters(work.await().id, slug) } else null
        SMangaUpdate(details?.await() ?: manga, chapterList?.await() ?: chapters)
    }

    override fun getMangaUrl(manga: SManga) = "$baseUrl${manga.url}"

    override fun getChapterUrl(chapter: SChapter) = "$baseUrl${chapter.url.substringBefore('#')}"

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val id = chapter.url.substringAfter('#').takeIf { it.isNotBlank() }
            ?: throw Exception("Atualize a lista de capítulos para abrir este capítulo.")
        val pages = client.get(
            "$API_URL/chapters".toHttpUrl().newBuilder()
                .addQueryParameter("select", "pages")
                .addQueryParameter("id", "eq.$id")
                .build().toString(),
            apiHeaders,
        ).parseAs<List<ReaderPagesDto>>().firstOrNull()?.pages.orEmpty()
        require(pages.isNotEmpty()) { "Este capítulo não está disponível gratuitamente no Nexus Mangas." }
        return pages.mapIndexed { index, image -> Page(index, imageUrl = image) }
    }

    override val supportsFilterFetching = true

    override suspend fun fetchFilterData(): JsonElement = coroutineScope {
        val genres = async { getFilterOptions("genres") }
        val formats = async { getFilterOptions("formats") }
        FilterData(genres.await(), formats.await()).toJsonElement()
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val filterData = data?.parseAs<FilterData>() ?: return FilterList()
        return FilterList(
            TypeFilter(),
            ContentRatingFilter(),
            GenreFilter(filterData.genres),
            FormatFilter(filterData.formats),
        )
    }

    private suspend fun getWorks(url: HttpUrl): List<WorkDto> = client.get(url.toString(), apiHeaders).parseAs()

    private suspend fun getWorksByIds(ids: List<String>): List<WorkDto> = ids.chunked(WORKS_REQUEST_LIMIT).flatMap { chunk ->
        getWorks(worksUrl().addQueryParameter("id", "in.(${chunk.joinToString(",")})").build())
    }

    private suspend fun getLatestReleasePage(index: Int): List<LatestReleaseDto> = client.get(
        "$API_URL/chapters".toHttpUrl().newBuilder()
            .addQueryParameter("select", "work_id,created_at")
            .addQueryParameter("order", "created_at.desc")
            .addQueryParameter("limit", LATEST_LIMIT.toString())
            .addQueryParameter("offset", (index * LATEST_LIMIT).toString())
            .build().toString(),
        apiHeaders,
    ).parseAs()

    private fun worksUrl(select: String = WORK_LIST_SELECT) = "$API_URL/works".toHttpUrl().newBuilder()
        .addQueryParameter("select", select)

    private suspend fun getChapters(workId: String, slug: String): List<SChapter> = client.get(
        "$API_URL/chapters".toHttpUrl().newBuilder()
            .addQueryParameter("select", "id,number,title,created_at,pages,scan:scans(name)")
            .addQueryParameter("work_id", "eq.$workId")
            .addQueryParameter("order", "number.desc")
            .addQueryParameter("limit", MAX_CHAPTERS.toString())
            .build().toString(),
        apiHeaders,
    ).parseAs<List<ChapterDto>>()
        .filter { it.pages.isNotEmpty() }
        .map { it.toSChapter(slug) }

    private suspend fun getFilterOptions(table: String): List<FilterOptionDto> = client.get(
        "$API_URL/$table".toHttpUrl().newBuilder()
            .addQueryParameter("select", "id,name")
            .addQueryParameter("order", "name.asc")
            .build().toString(),
        apiHeaders,
    ).parseAs()

    private val apiHeaders: Headers
        get() = headers.newBuilder()
            .set("apikey", ANON_KEY)
            .set("Authorization", "Bearer $ANON_KEY")
            .set("Accept", "application/json")
            .set("Origin", baseUrl)
            .set("Referer", "$baseUrl/")
            .build()

    private companion object {
        const val API_URL = "https://supabase.nexusmangas.com/rest/v1"
        const val PAGE_SIZE = 24
        const val LATEST_LIMIT = 900
        const val POPULAR_LIMIT = 120
        const val MAX_CHAPTERS = 1000
        const val WORKS_REQUEST_LIMIT = 240
        const val WORK_LIST_SELECT = "id,title,slug,cover_url,status,type,content_rating"
        const val WORK_DETAILS_SELECT = "id,title,slug,cover_url,description,alternative_title,status,type,content_rating,release_year,author,artist,work_genres(genre:genres(name)),work_formats(format:formats(name)),scan:scans(name)"
        const val ANON_KEY = "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJpc3MiOiJzdXBhYmFzZSIsImlhdCI6MTc4NzgwMjAwMCwiZXhwIjo0OTQzNDc1NjAwLCJyb2xlIjoiYW5vbiJ9.Cnl8Jw2DeKe84OAkmJYfO33xlcZsw0TC2Nw_il0tpRs"
    }
}
