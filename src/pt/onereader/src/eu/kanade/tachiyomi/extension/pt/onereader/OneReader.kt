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
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

@Source
abstract class OneReader : KeiSource() {

    // Key by canonical OkHttp URL so a host that rehydrates a Page object cannot
    // accidentally lose a signed query parameter while looking up its local key.
    private val mediaGrants = ConcurrentHashMap<HttpUrl, MediaGrant>()

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        rateLimit(3)
        addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val body = response.body
            val contentType = body.contentType()?.toString().orEmpty()
            if (!response.isSuccessful || !contentType.startsWith("application/octet-stream")) {
                return@addInterceptor response
            }
            val encrypted = body.bytes()
            val magic = encrypted.copyOfRange(0, minOf(4, encrypted.size))
            val isOrx3 = magic.contentEquals(ORX3_MAGIC)
            val isOrx4 = magic.contentEquals(ORX4_MAGIC)
            if (!isOrx3 && !isOrx4) return@addInterceptor response.newBuilder().body(encrypted.toResponseBody(body.contentType())).build()

            val grant = mediaGrants[chain.request().url]
            if (grant == null) {
                throw IOException("Autorização da mídia OneReader não encontrada")
            }
            val decrypted = if (isOrx3) {
                Orx3Decoder.decode(encrypted, grant.key)
            } else {
                Orx4Decoder.decode(encrypted, grant.keyBytes ?: throw IOException("Chave ORX4 ausente"), chain.request().url.queryParameter("or_n").orEmpty())
            }
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
        val (mangaKey, number) = chapterIdentity(chapter)

        return "$baseUrl/leitor".toHttpUrl().newBuilder()
            .addQueryParameter("id", mangaKey)
            .addQueryParameter("capitulo", number)
            .build()
            .toString()
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = coroutineScope {
        val (mangaKey, chapterNumber) = chapterIdentity(chapter)
        val secureTransport = SecureReaderTransport.create()
        val readerHeaders = headers.newBuilder()
            .set("Accept", "application/json")
            .set("X-OneReader-Client-Key", secureTransport.clientKey)
            .set("X-OneReader-Key-Transport", KEY_TRANSPORT)
            .build()
        val pages = client.get(
            apiUrl("api", "reader", "works", mangaKey, "chapters", chapterNumber),
            readerHeaders,
            cacheControl = CacheControl.FORCE_NETWORK,
        )
            .parseAs<PagesDto>()
            .toPages(baseUrl.toHttpUrl())
        pages.map { page ->
            async {
                val pageUrl = page.imageUrl ?: throw IOException("URL da página ausente no manifesto OneReader")
                page to authorizeMedia(pageUrl, readerHeaders, secureTransport)
            }
        }
            .awaitAll()
            .map { (page, grant) -> Page(page.index, page.url, grant.url) }
    }

    private suspend fun authorizeMedia(
        pageUrl: String,
        readerHeaders: Headers,
        secureTransport: SecureReaderTransport,
    ): MediaGrant {
        val requestHeaders = readerHeaders.newBuilder().set("Accept", "application/vnd.onereader.media+json").build()
        val response = client.get(
            pageUrl.toHttpUrl(),
            requestHeaders,
            cacheControl = CacheControl.FORCE_NETWORK,
            ensureSuccess = false,
        )
        if (!response.isSuccessful) {
            val detail = response.body.string().replace(Regex("[\\r\\n\\t]+"), " ").take(MAX_ERROR_BODY_LENGTH)
            response.close()
            throw IOException("Falha ao autorizar mídia: HTTP ${response.code}: $detail")
        }

        val grant = response.parseAs<MediaGrantDto>()
        if (!grant.ok || grant.mode !in SUPPORTED_MEDIA_MODES) throw IOException("Autorização de mídia inválida")
        val keyBytes = grant.keyWrap?.let(secureTransport::unwrap)
        if (grant.mode == MODE_AES_GCM_V4 && keyBytes == null) throw IOException("Chave ORX4 ausente")
        if (grant.mode == MODE_XOR_PREFIX_V3 && grant.key.isBlank()) throw IOException("Chave ORX3 ausente")
        val mediaGrant = MediaGrant(grant.url, grant.key, keyBytes, grant.contentType)
        mediaGrants[grant.url.toHttpUrl()] = mediaGrant
        return mediaGrant
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
        private const val MAX_ERROR_BODY_LENGTH = 500
        private const val KEY_TRANSPORT = "ecdh-p256-aesgcm-v1"
        private const val MODE_XOR_PREFIX_V3 = "xor-prefix-v3"
        private const val MODE_AES_GCM_V4 = "aes-gcm-v4"
        private val SUPPORTED_MEDIA_MODES = setOf(MODE_XOR_PREFIX_V3, MODE_AES_GCM_V4)
        private val ORX3_MAGIC = byteArrayOf(0x4f, 0x52, 0x58, 0x33)
        private val ORX4_MAGIC = byteArrayOf(0x4f, 0x52, 0x58, 0x34)
    }
}

private data class MediaGrant(val url: String, val key: String, val keyBytes: ByteArray?, val contentType: String)

private fun chapterIdentity(chapter: SChapter): Pair<String, String> = resolveChapterIdentity(
    chapter.url,
    chapter.memo["id"]?.string,
    chapter.memo["number"]?.string,
)

internal fun resolveChapterIdentity(chapterUrl: String, memoMangaKey: String?, memoChapterNumber: String?): Pair<String, String> {
    // Some hosts persist only the standard chapter URL and omit extension memo.
    val fallbackMangaKey = chapterUrl.substringBeforeLast('/', "")
    val fallbackNumber = chapterUrl.substringAfterLast('/', "")
    val mangaKey = memoMangaKey?.takeIf(String::isNotBlank) ?: fallbackMangaKey
    val chapterNumber = memoChapterNumber?.takeIf(String::isNotBlank) ?: fallbackNumber
    if (mangaKey.isBlank() || chapterNumber.isBlank()) {
        throw IOException("Capítulo OneReader sem identificador; atualize a lista de capítulos")
    }
    return mangaKey to chapterNumber
}
