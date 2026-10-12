package eu.kanade.tachiyomi.extension.pt.supermanga

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
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.jsonInstance
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

@Source
abstract class SuperManga : KeiSource() {

    override val supportsLatest = true

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(3)

    override fun Headers.Builder.configureHeaders(): Headers.Builder = set("Accept", "application/json, text/plain, */*")

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$baseUrl/api/v1/mangas".toHttpUrl().newBuilder()
            .addQueryParameter("sort", "popular")
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("offset", ((page - 1) * PAGE_SIZE).toString())
            .build()

        val response = client.get(url).parseAs<CatalogResponseDto>()
        val mangas = response.items.map { it.toSManga(baseUrl) }
        val hasNextPage = (page * PAGE_SIZE) < response.total
        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$baseUrl/api/v1/mangas".toHttpUrl().newBuilder()
            .addQueryParameter("sort", "newest")
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("offset", ((page - 1) * PAGE_SIZE).toString())
            .build()

        val response = client.get(url).parseAs<CatalogResponseDto>()
        val mangas = response.items.map { it.toSManga(baseUrl) }
        val hasNextPage = (page * PAGE_SIZE) < response.total
        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/api/v1/mangas".toHttpUrl().newBuilder().apply {
            addQueryParameter("limit", PAGE_SIZE.toString())
            addQueryParameter("offset", ((page - 1) * PAGE_SIZE).toString())

            val trimmedQuery = query.trim()
            if (trimmedQuery.isNotBlank()) {
                addQueryParameter("search", trimmedQuery)
            }

            val sort = filters.firstInstanceOrNull<SortFilter>()?.selectedValue ?: "popular"
            addQueryParameter("sort", sort)

            filters.firstInstanceOrNull<StatusFilter>()?.selectedValue?.let {
                addQueryParameter("status", it)
            }

            filters.firstInstanceOrNull<TypeFilter>()?.selectedValue?.let {
                addQueryParameter("type", it)
            }

            filters.firstInstanceOrNull<DemographicFilter>()?.selectedValue?.let {
                addQueryParameter("demographic", it)
            }

            filters.firstInstanceOrNull<ContentRatingFilter>()?.selectedValue?.let {
                addQueryParameter("contentRating", it)
            }

            filters.firstInstanceOrNull<GenreGroup>()?.selectedGenres?.forEach {
                addQueryParameter("genre", it)
            }
        }.build()

        val response = client.get(url).parseAs<CatalogResponseDto>()
        val mangas = response.items.map { it.toSManga(baseUrl) }
        val hasNextPage = (page * PAGE_SIZE) < response.total
        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val segments = url.pathSegments.filter(String::isNotBlank)
        if (segments.firstOrNull() != "manga") return null
        val slug = segments.getOrNull(1)?.takeIf(String::isNotBlank) ?: return null

        val response = runCatching {
            client.get("$baseUrl/api/v1/mangas/slug/$slug").parseAs<MangaDetailsDto>()
        }.getOrNull() ?: return null

        return response.toSManga(baseUrl)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val id = Regex("[?&]id=(\\d+)").find(manga.url)?.groupValues?.get(1)?.toLongOrNull()
        val slug = manga.url.substringBefore('?').trim('/').substringAfterLast('/')

        val detailsDto = when {
            id != null -> {
                runCatching {
                    client.get("$baseUrl/api/v1/mangas/$id").parseAs<MangaDetailsDto>()
                }.getOrElse {
                    client.get("$baseUrl/api/v1/mangas/slug/$slug").parseAs()
                }
            }
            slug.isNotBlank() -> {
                client.get("$baseUrl/api/v1/mangas/slug/$slug").parseAs<MangaDetailsDto>()
            }
            else -> throw IOException("Não foi possível identificar a obra: ${manga.url}")
        }

        val updatedManga = if (fetchDetails) detailsDto.toSManga(baseUrl) else manga
        val updatedChapters = if (fetchChapters) {
            val resolvedSlug = slug.ifBlank { slugify(detailsDto.t) }
            detailsDto.chapters
                .filter { it.language == null || it.language.equals("pt-br", ignoreCase = true) }
                .sortedWith(compareByDescending<ChapterDto> { it.number }.thenByDescending { it.id })
                .map { it.toSChapter(resolvedSlug, detailsDto.id) }
        } else {
            chapters
        }

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val mangaId = Regex("[?&]mangaId=(\\d+)").find(chapter.url)?.groupValues?.get(1)
        val chapterNum = Regex("/capitulo/([^/?#]+)").find(chapter.url)?.groupValues?.get(1)

        if (mangaId != null && chapterNum != null) {
            val apiPages = runCatching {
                val apiUrl = "$baseUrl/api/v1/mangas/$mangaId/chapters/$chapterNum"
                val response = client.get(apiUrl).parseAs<ChapterPagesDto>()
                response.pages.mapIndexed { index, p ->
                    val imgUrl = if (p.imageUrl.startsWith("/")) "$baseUrl${p.imageUrl}" else p.imageUrl
                    Page(index, imageUrl = imgUrl)
                }
            }.getOrNull()

            if (!apiPages.isNullOrEmpty()) {
                return apiPages
            }
        }

        // Fallback HTML parsing
        val doc = client.get("$baseUrl${chapter.url.substringBefore('?')}").asJsoup()

        // 1. Check window.__INITIAL_DATA__
        val scriptContent = doc.selectFirst("script:containsData(window.__INITIAL_DATA__)")?.data()
        if (!scriptContent.isNullOrBlank()) {
            val jsonStr = scriptContent.substringAfter("window.__INITIAL_DATA__=").substringBeforeLast(";").trim()
            runCatching {
                val json = jsonInstance.parseToJsonElement(jsonStr).jsonObject
                val pages = json["chapter"]?.jsonObject?.get("pages")?.jsonArray
                if (pages != null && pages.isNotEmpty()) {
                    return pages.mapIndexed { index, p ->
                        val imgUrl = p.jsonObject["imageUrl"]?.jsonPrimitive?.content ?: ""
                        val resolvedUrl = if (imgUrl.startsWith("/")) "$baseUrl$imgUrl" else imgUrl
                        Page(index, imageUrl = resolvedUrl)
                    }
                }
            }
        }

        // 2. DOM fallback
        val imgElements = doc.select(".mr-page img.mr-img")
        if (imgElements.isNotEmpty()) {
            return imgElements.mapIndexed { index, el ->
                val src = el.attr("abs:src").ifBlank { el.attr("src") }
                val resolvedUrl = if (src.startsWith("/")) "$baseUrl$src" else src
                Page(index, imageUrl = resolvedUrl)
            }
        }

        throw IOException("Nenhuma página encontrada para este capítulo.")
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url.substringBefore('?')}"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl${chapter.url.substringBefore('?')}"

    override fun imageRequest(page: Page): Request = Request.Builder()
        .url(page.imageUrl!!)
        .headers(headers)
        .addHeader("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
        .get()
        .build()

    override val supportsFilterFetching = true

    override suspend fun fetchFilterData(): JsonElement = client.get("$baseUrl/api/v1/mangas/meta/genres")
        .parseAs<GenresResponseDto>()
        .let { FilterData(it.genres) }
        .toJsonElement()

    override fun getFilterList(data: JsonElement?): FilterList {
        val genres = data?.parseAs<FilterData>()?.genres?.takeIf { it.isNotEmpty() } ?: DEFAULT_GENRES
        return FilterList(
            SortFilter(),
            StatusFilter(),
            TypeFilter(),
            DemographicFilter(),
            ContentRatingFilter(),
            GenreGroup(genres),
        )
    }

    private companion object {
        const val PAGE_SIZE = 20
    }
}
