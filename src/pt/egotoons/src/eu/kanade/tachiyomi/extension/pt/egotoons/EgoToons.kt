package eu.kanade.tachiyomi.extension.pt.egotoons

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
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

@Source
abstract class EgoToons : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(2)

    override suspend fun getPopularManga(page: Int): MangasPage = getMangaList(page, defaultSort = "populares")

    override suspend fun getLatestUpdates(page: Int): MangasPage = getMangaList(page, defaultSort = "recentes")

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = getMangaList(page, query, filters, defaultSort = "recentes")

    private suspend fun getMangaList(
        page: Int,
        query: String = "",
        filters: FilterList = FilterList(),
        defaultSort: String,
    ): MangasPage {
        val sort = filters.firstInstanceOrNull<SortFilter>()?.selectedValue ?: defaultSort
        val url = "$baseUrl/api/obras.js".toHttpUrl().newBuilder()
            .addQueryParameter("pagina", page.toString())
            .addQueryParameter("limite", MANGA_PAGE_SIZE.toString())
            .addQueryParameter("ordenarPor", sort)
            .apply {
                if (query.isNotBlank()) {
                    addQueryParameter("busca", query)
                }
                filters.filterIsInstance<UrlFilter>()
                    .filterNot { it is SortFilter }
                    .forEach { it.addToUrl(this) }
            }
            .build()

        val result = client.get(url).parseAs<MangaListDto>()
        return MangasPage(result.mangas.map { it.toSManga(baseUrl) }, result.pagination.hasNextPage)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host.removePrefix("www.") != baseUrl.toHttpUrl().host.removePrefix("www.") || url.pathSegments.firstOrNull() != "obra") return null
        val mangaId = url.pathSegments.getOrNull(1)?.toIntOrNull() ?: return null
        val manga = SManga.create().apply { this.url = "/obra/$mangaId" }

        return fetchMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga.apply {
            initialized = true
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val mangaId = manga.url.mangaId()
        val details = if (fetchDetails) {
            async {
                client.get("$baseUrl/api/obras/$mangaId")
                    .parseAs<MangaDetailsDto>()
                    .manga
                    .toSManga(baseUrl)
            }
        } else {
            null
        }
        val chapterList = if (fetchChapters) async { getChapterList(mangaId) } else null

        SMangaUpdate(
            manga = details?.await() ?: manga,
            chapters = chapterList?.await() ?: chapters,
        )
    }

    private suspend fun getChapterList(mangaId: Int): List<SChapter> = coroutineScope {
        val firstPage = getChapterPage(mangaId, 1)
        val remaining = (2..firstPage.pagination.totalPages).map { page ->
            async { getChapterPage(mangaId, page).chapters }
        }.awaitAll().flatten()

        (firstPage.chapters + remaining).map { it.toSChapter() }
    }

    private suspend fun getChapterPage(mangaId: Int, page: Int): ChapterListDto {
        val url = "$baseUrl/api/obras/$mangaId/capitulos.js".toHttpUrl().newBuilder()
            .addQueryParameter("pagina", page.toString())
            .addQueryParameter("limite", CHAPTER_PAGE_SIZE.toString())
            .addQueryParameter("ordenar", "desc")
            .addQueryParameter("fields", "list")
            .build()
        return client.get(url).parseAs()
    }

    private val apiBaseUrl: String
        get() = "https://api.${baseUrl.toHttpUrl().host.removePrefix("www.")}"

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val mangaId = chapter.url.mangaId()
        val chapterNumber = chapter.url.chapterNumber()
        val chapterUrl = getChapterUrl(chapter)
        val manifestHeaders = headers.newBuilder()
            .set("Referer", chapterUrl)
            .build()

        val manifestPages = fetchManifestPages(mangaId, chapterNumber, chapter, manifestHeaders)
        if (manifestPages.isNotEmpty()) {
            val base = apiBaseUrl.toHttpUrl()
            return manifestPages.sortedBy { it.index }.mapIndexed { i, p ->
                val imgUrl = base.resolve(p.url)?.toString() ?: p.url
                Page(p.index.takeIf { it >= 0 } ?: i, url = chapterUrl, imageUrl = imgUrl)
            }
        }

        // Fallback for legacy chapters
        val legacy = runCatching {
            client.get("$baseUrl/api/obras/$mangaId/capitulos/$chapterNumber/index.js", manifestHeaders)
                .parseAs<ChapterDetailsDto>()
        }.getOrNull()

        val legacyPages = legacy?.chapter?.toPageList(baseUrl, chapterUrl).orEmpty()
        if (legacyPages.isNotEmpty()) {
            return legacyPages
        }

        throw Exception("Nenhuma página encontrada para o capítulo")
    }

    private suspend fun fetchManifestPages(
        mangaId: Int,
        chapterNumber: String,
        chapter: SChapter,
        manifestHeaders: Headers,
    ): List<ManifestPageDto> {
        val id = runCatching { chapter.memo["id"]?.jsonPrimitive?.content }.getOrNull()
        val baseManifestUrl = if (id != null) {
            "$apiBaseUrl/api/leitor/capitulos/$id/manifest".toHttpUrl()
        } else {
            "$apiBaseUrl/api/leitor/obras/$mangaId/capitulos/$chapterNumber/manifest".toHttpUrl()
        }

        val allPages = mutableListOf<ManifestPageDto>()
        var nextOffset: Int? = 0
        var attempts = 0
        val maxPages = 50

        while (nextOffset != null && attempts < maxPages) {
            attempts++
            val url = baseManifestUrl.newBuilder()
                .apply {
                    if (nextOffset > 0) {
                        addQueryParameter("offset", nextOffset.toString())
                    }
                }
                .build()

            val response = runCatching {
                client.get(url, manifestHeaders, ensureSuccess = false)
            }.getOrNull() ?: break

            if (!response.isSuccessful) {
                if (response.code == 404 && id != null && baseManifestUrl.encodedPath.contains("/capitulos/") && allPages.isEmpty()) {
                    response.close()
                    val fallbackUrl = "$apiBaseUrl/api/leitor/obras/$mangaId/capitulos/$chapterNumber/manifest".toHttpUrl()
                    val fallbackResp = runCatching {
                        client.get(fallbackUrl, manifestHeaders, ensureSuccess = false)
                    }.getOrNull() ?: break
                    if (fallbackResp.isSuccessful) {
                        val m = fallbackResp.parseAs<ChapterManifestDto>()
                        fallbackResp.close()
                        allPages.addAll(m.pages)
                        nextOffset = m.nextOffset
                        continue
                    }
                    fallbackResp.close()
                }
                response.close()
                break
            }

            val manifest = response.parseAs<ChapterManifestDto>()
            response.close()

            if (manifest.pages.isEmpty()) break
            allPages.addAll(manifest.pages)
            nextOffset = manifest.nextOffset
        }

        return allPages
    }

    override fun imageRequest(page: Page): Request {
        val imageHeaders = headers.newBuilder().apply {
            if (page.url.isNotBlank()) {
                set("Referer", page.url)
            }
        }.build()
        return Request.Builder()
            .url(page.imageUrl!!)
            .headers(imageHeaders)
            .get()
            .build()
    }

    override val supportsFilterFetching: Boolean get() = true

    override suspend fun fetchFilterData(): JsonElement = coroutineScope {
        val formats = async {
            client.get("$baseUrl/api/filtros/formatos.js").parseAs<FormatListDto>().formats
        }
        val statuses = async {
            client.get("$baseUrl/api/filtros/status.js").parseAs<StatusListDto>().statuses
        }
        val tags = async {
            client.get("$baseUrl/api/filtros/tags.js").parseAs<TagListDto>().tags
        }

        FilterData(formats.await(), statuses.await(), tags.await()).toJsonElement()
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val filterData = data?.parseAs<FilterData>() ?: return FilterList(SortFilter())
        return FilterList(
            SortFilter(),
            FormatFilter(filterData.formats),
            StatusFilter(filterData.statuses),
            TagFilter(filterData.tags),
        )
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/obra/${manga.url.mangaId()}"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/obra/${chapter.url.mangaId()}/capitulo/${chapter.url.chapterNumber()}"

    private fun String.mangaId(): Int {
        val path = normalizedPath()
        val index = path.indexOfFirst { it == "obra" || it == "manga" }
        return path.getOrNull(index + 1)?.toIntOrNull() ?: throw Exception("Invalid manga URL")
    }

    private fun String.chapterNumber(): String {
        val path = normalizedPath().filter(String::isNotBlank)
        val index = path.indexOfFirst { it == "capitulo" || it == "chapter" }
        return path.getOrNull(index + 1) ?: throw Exception("Invalid chapter URL")
    }

    private fun String.normalizedPath(): List<String> = toHttpUrlOrNull()?.pathSegments ?: "$baseUrl/${trimStart('/')}".toHttpUrl().pathSegments

    companion object {
        private const val MANGA_PAGE_SIZE = 24
        private const val CHAPTER_PAGE_SIZE = 80
    }
}
