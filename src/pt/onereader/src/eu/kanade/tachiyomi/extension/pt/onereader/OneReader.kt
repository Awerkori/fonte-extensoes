package eu.kanade.tachiyomi.extension.pt.onereader

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
import keiyoushi.utils.string
import keiyoushi.utils.toJsonElement
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

@Source
abstract class OneReader : KeiSource() {

    private val mediaGrants = ConcurrentHashMap<String, MediaGrant>()

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        rateLimit(3)
        addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val body = response.body ?: return@addInterceptor response
            val contentType = body.contentType()?.toString().orEmpty()
            if (!response.isSuccessful || !contentType.startsWith("application/octet-stream")) {
                return@addInterceptor response
            }
            val encrypted = body.bytes()
            if (!encrypted.copyOfRange(0, minOf(4, encrypted.size)).contentEquals(byteArrayOf(0x4f, 0x52, 0x58, 0x33))) {
                return@addInterceptor response.newBuilder().body(encrypted.toResponseBody(body.contentType())).build()
            }
            val requestUrl = chain.request().url.toString()
            val grant = mediaGrants[requestUrl]
            if (grant == null) {
                throw IOException("Autorização da mídia OneReader não encontrada")
            }
            val decrypted = Orx3Decoder.decode(encrypted, grant.key)
            response.newBuilder().removeHeader("Content-Length").removeHeader("Content-Encoding").header("Content-Type", grant.contentType)
                .body(decrypted.toResponseBody(grant.contentType.toMediaType())).build()
        }
    }

    private val apiBaseUrl = baseUrl.toHttpUrl()

    override suspend fun getPopularManga(page: Int): MangasPage = client.get(apiUrl("api", "reader", "home"))
        .parseAs<HomeDto>().toPopularPage()

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = apiUrl("api", "reader", "home", "updates").newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("filter", "all")
            .build()

        return client.get(url).parseAs<UpdatesDto>().toMangasPage()
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val order = filters.firstInstanceOrNull<OrderFilter>()?.selected() ?: "az"
        val genre = filters.firstInstanceOrNull<TagFilter>()?.selected().orEmpty()
        val type = filters.firstInstanceOrNull<TypeFilter>()?.selected().orEmpty()
        val status = filters.firstInstanceOrNull<StatusFilter>()?.selected().orEmpty()

        return search(page, query, order, genre, type, status)
    }

    private suspend fun search(
        page: Int,
        query: String,
        order: String,
        genre: String,
        type: String,
        status: String,
    ): MangasPage {
        val url = apiUrl("api", "reader", "catalog", "page").newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("sort", if (order == "recent") "DATE" else "AZ")
            .addQueryParameter("q", query.trim())
            .apply { if (type.isNotBlank()) addQueryParameter("format", type) }
            .apply { if (status.isNotBlank()) addQueryParameter("status", status) }
            .apply { if (genre.isNotBlank()) addQueryParameter("genre", genre) }
            .build()

        return client.get(url).parseAs<CatalogDto>().toMangasPage()
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val mangaKey = url.queryParameter("id") ?: return null

        val manga = SManga.create().apply { this.url = mangaKey }

        return fetchMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false)
            .manga
            .apply { initialized = true }
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/obra".toHttpUrl().newBuilder()
        .addQueryParameter("id", manga.url)
        .build()
        .toString()

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val updatedManga = if (fetchDetails) {
            async {
                client.get(apiUrl("api", "reader", "works", manga.url))
                    .parseAs<WorkDetailsDto>().work.toSManga(details = true)
            }
        } else {
            null
        }

        val updatedChapters = if (fetchChapters) {
            async {
                client.get(apiUrl("api", "reader", "works", manga.url))
                    .parseAs<WorkDetailsDto>().chapters
                    .sortedByDescending { it.number }
                    .map { it.toSChapter(manga.url) }
            }
        } else {
            null
        }

        SMangaUpdate(
            manga = updatedManga?.await() ?: manga,
            chapters = updatedChapters?.await() ?: chapters,
        )
    }

    override fun getChapterUrl(chapter: SChapter): String {
        val mangaKey = chapter.memo["id"]!!.string
        val number = chapter.memo["number"]!!.string

        return "$baseUrl/leitor".toHttpUrl().newBuilder()
            .addQueryParameter("id", mangaKey)
            .addQueryParameter("capitulo", number)
            .build()
            .toString()
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = coroutineScope {
        val mangaKey = chapter.memo["id"]?.string ?: throw Exception("Atualize a lista de capítulos")
        val chapterNumber = chapter.memo["number"]!!.string
        val pages = client.get(apiUrl("api", "reader", "works", mangaKey, "chapters", chapterNumber))
            .parseAs<PagesDto>()
            .toPages(baseUrl.toHttpUrl())
        pages.map { page -> async { page to authorizeMedia(page.imageUrl!!) } }
            .awaitAll()
            .map { (page, grant) -> Page(page.index, page.url, grant.url) }
    }

    private fun authorizeMedia(pageUrl: String): MediaGrant {
        val request = Request.Builder().url(pageUrl)
            .header("Accept", "application/vnd.onereader.media+json").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Falha ao autorizar mídia: HTTP ${response.code}")
            val grant = response.parseAs<MediaGrantDto>()
            if (!grant.ok || grant.mode != "xor-prefix-v3") throw IOException("Autorização de mídia inválida")
            val mediaGrant = MediaGrant(grant.url, grant.key, grant.contentType)
            mediaGrants[grant.url] = mediaGrant
            return mediaGrant
        }
    }

    override val supportsFilterFetching get() = true

    override suspend fun fetchFilterData(): JsonElement = coroutineScope {
        val meta = async { client.get(apiUrl("api", "reader", "catalog", "meta")).parseAs<MetaDto>() }

        FilterData(
            tags = meta.await().genres.values.flatten().map { it.name }.filter(String::isNotBlank).distinct(),
            types = listOf("Manga", "Manhwa", "Manhua", "Webtoon"),
        ).toJsonElement()
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val filterData = data?.parseAs<FilterData>()

        return FilterList(
            buildList {
                add(OrderFilter())
                add(StatusFilter())
                if (filterData != null) {
                    add(TypeFilter(filterData.types))
                    add(TagFilter(filterData.tags))
                }
            },
        )
    }

    private fun apiUrl(vararg pathSegments: String): HttpUrl = apiBaseUrl.newBuilder()
        .apply { pathSegments.forEach { addPathSegment(it) } }
        .build()

    companion object {
        private const val PAGE_SIZE = 24
        private const val HOME_LIMIT = 60
    }
}

private data class MediaGrant(val url: String, val key: String, val contentType: String)
