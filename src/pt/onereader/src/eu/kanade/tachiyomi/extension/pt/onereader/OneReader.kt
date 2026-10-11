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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

@Source
abstract class OneReader : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        rateLimit(3)
        addInterceptor { chain ->
            val request = chain.request()
            val url = request.url

            if (url.host == apiBaseUrl.host &&
                url.encodedPath.contains("/api/reader/works/") &&
                url.encodedPath.contains("/pages/") &&
                !request.header("Accept").orEmpty().contains("application/vnd.onereader.media+json")
            ) {
                return@addInterceptor handlePageImageRequest(chain, request)
            }

            val response = chain.proceed(request)
            response.header("Date")?.let(SecureReaderTransport::updateServerTimeFromHeader)
            response
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
        val chapterKey = "$mangaKey/$chapterNumber"
        val secureTransport = SecureReaderTransport.create()
        val chapterUrl = getChapterUrl(chapter)
        val manifestUrl = apiUrl("api", "reader", "works", mangaKey, "chapters", chapterNumber)
        val readerHeaders = headers.newBuilder()
            .set("Accept", "application/json")
            .set("Referer", chapterUrl)
            .set("X-OneReader-Client-Key", secureTransport.clientKey)
            .set("X-OneReader-Key-Transport", KEY_TRANSPORT)
            .build()
        val initialManifest = fetchInitialManifest(manifestUrl, readerHeaders, secureTransport)

        val pageUrls = (initialManifest.chapter?.pages ?: emptyList()).mapNotNull { path ->
            baseUrl.toHttpUrl().resolve(path)?.toString()
        }

        if (pageUrls.isEmpty()) return@coroutineScope emptyList()

        val windowLimit = extractWindowLimit(pageUrls.firstOrNull().orEmpty()) ?: pageUrls.size

        chapterSessions[chapterKey] = ChapterSession(
            transport = secureTransport,
            manifestUrl = manifestUrl,
            chapterUrl = chapterUrl,
            pages = pageUrls,
            windowLimit = windowLimit,
        )

        pageUrls.mapIndexed { index, url ->
            Page(index, "", url)
        }
    }

    private suspend fun fetchInitialManifest(
        manifestUrl: HttpUrl,
        readerHeaders: Headers,
        secureTransport: SecureReaderTransport,
    ): PagesDto {
        var lastError: Exception? = null
        for (attempt in 0 until MAX_ADVANCE_ATTEMPTS) {
            val response = client.get(
                manifestUrl,
                readerHeaders,
                cacheControl = CacheControl.FORCE_NETWORK,
                ensureSuccess = false,
            )
            response.header("Date")?.let(SecureReaderTransport::updateServerTimeFromHeader)
            if (response.isSuccessful) {
                val manifest = response.parseAs<PagesDto>()
                response.close()
                secureTransport.configure(manifest.protection?.transport)
                return manifest
            }

            val statusCode = response.code
            val bodyString = response.body.string()
            val retryAfterHeader = response.header("Retry-After")?.toLongOrNull()
            response.close()

            val isRateLimited = statusCode == 429 && (bodyString.contains("READER_RATE_LIMITED") || bodyString.contains("READER_COOLDOWN"))
            if (isRateLimited && attempt < MAX_ADVANCE_ATTEMPTS - 1) {
                val baseWaitMs = if (retryAfterHeader != null) {
                    retryAfterHeader * 1000L
                } else {
                    (DEFAULT_RETRY_AFTER_SECONDS + attempt * 2L) * 1000L
                }
                val jitter = Random.nextLong(100L, 500L)
                delay(baseWaitMs + jitter)
                continue
            }

            val detail = bodyString.replace(Regex("[\\r\\n\\t]+"), " ").take(MAX_ERROR_BODY_LENGTH)
            lastError = IOException("Falha ao obter manifesto do capítulo: HTTP $statusCode: $detail")
            break
        }

        throw lastError ?: IOException("Não foi possível obter o manifesto inicial do capítulo")
    }

    private fun handlePageImageRequest(chain: Interceptor.Chain, request: Request): Response {
        val url = request.url
        val pathSegments = url.pathSegments
        val worksIdx = pathSegments.indexOf("works")
        val chaptersIdx = pathSegments.indexOf("chapters")
        if (worksIdx == -1 || chaptersIdx == -1 || chaptersIdx + 1 >= pathSegments.size) {
            return chain.proceed(request)
        }
        val mangaKey = pathSegments[worksIdx + 1]
        val chapterNumber = pathSegments[chaptersIdx + 1]
        val chapterKey = "$mangaKey/$chapterNumber"

        val pageNumberStr = pathSegments.lastOrNull().orEmpty()
        val pageNumber = pageNumberStr.toIntOrNull() ?: 1

        var session = getOrFetchSession(chain, mangaKey, chapterNumber)
        session = ensureWindowForPage(chain, mangaKey, chapterNumber, session, pageNumber)

        val targetUrl = session.pages.getOrNull(pageNumber - 1)?.toHttpUrl() ?: url

        val grant = try {
            authorizeMediaWithChain(chain, targetUrl, mangaKey, chapterNumber, session.transport)
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            val isExpired = msg.contains("HTTP 428") || msg.contains("HTTP 403") ||
                msg.contains("READER_PROOF_EXPIRED") || msg.contains("READER_GRANT_INVALID") ||
                msg.contains("Chave") || msg.contains("Cloudflare")
            if (isExpired) {
                mediaGrants.remove(targetUrl)
                val freshSession = synchronized(getChapterLock(chapterKey)) {
                    val refreshed = refreshChapterSessionSync(chain, mangaKey, chapterNumber)
                    ensureWindowForPage(chain, mangaKey, chapterNumber, refreshed, pageNumber)
                }
                session = freshSession
                val retryUrl = session.pages.getOrNull(pageNumber - 1)?.toHttpUrl() ?: targetUrl
                authorizeMediaWithChain(chain, retryUrl, mangaKey, chapterNumber, session.transport)
            } else {
                throw e
            }
        }

        val response = try {
            fetchAndDecodeCdnImage(chain, grant)
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            if (msg.contains("Cloudflare") || msg.contains("403")) {
                mediaGrants.remove(targetUrl)
            }
            throw e
        }

        if (response.code in listOf(403, 404)) {
            mediaGrants.remove(targetUrl)
        }

        return response
    }

    private fun fetchAndDecodeCdnImage(chain: Interceptor.Chain, grant: MediaGrant): Response {
        val cdnRequest = Request.Builder()
            .url(grant.url)
            .header("Referer", "$baseUrl/")
            .header("Origin", baseUrl)
            .build()

        val cdnResponse = chain.proceed(cdnRequest)
        if (!cdnResponse.isSuccessful) {
            return cdnResponse
        }

        val body = cdnResponse.body
        val encrypted = body.bytes()
        cdnResponse.close()

        val magic = encrypted.copyOfRange(0, minOf(4, encrypted.size))
        val isOrx3 = magic.contentEquals(ORX3_MAGIC)
        val isOrx4 = magic.contentEquals(ORX4_MAGIC)
        if (!isOrx3 && !isOrx4) {
            return cdnResponse.newBuilder()
                .body(encrypted.toResponseBody(body.contentType()))
                .build()
        }

        val decrypted = if (isOrx3) {
            Orx3Decoder.decode(encrypted, grant.key)
        } else {
            val nonce = grant.url.toHttpUrl().queryParameter("or_n").orEmpty()
            val keyBytes = grant.keyBytes ?: throw IOException("Chave ORX4 ausente")
            Orx4Decoder.decode(encrypted, keyBytes, nonce)
        }

        return cdnResponse.newBuilder()
            .code(200)
            .message("OK")
            .removeHeader("Content-Length")
            .removeHeader("Content-Encoding")
            .header("Content-Type", grant.contentType)
            .body(decrypted.toResponseBody(grant.contentType.toMediaType()))
            .build()
    }

    private fun getOrFetchSession(
        chain: Interceptor.Chain,
        mangaKey: String,
        chapterNumber: String,
    ): ChapterSession {
        val chapterKey = "$mangaKey/$chapterNumber"
        val existing = chapterSessions[chapterKey]
        val now = System.currentTimeMillis()
        if (existing != null && (now - existing.updatedAt) < SESSION_TTL_MS) {
            return existing
        }

        return synchronized(getChapterLock(chapterKey)) {
            val lockedExisting = chapterSessions[chapterKey]
            if (lockedExisting != null && (System.currentTimeMillis() - lockedExisting.updatedAt) < SESSION_TTL_MS) {
                return@synchronized lockedExisting
            }
            refreshChapterSessionSync(chain, mangaKey, chapterNumber)
        }
    }

    private fun ensureWindowForPage(
        chain: Interceptor.Chain,
        mangaKey: String,
        chapterNumber: String,
        session: ChapterSession,
        targetPageNumber: Int,
    ): ChapterSession {
        if (targetPageNumber <= session.windowLimit || session.windowLimit >= session.pages.size) {
            return session
        }

        val chapterKey = "$mangaKey/$chapterNumber"
        return synchronized(getChapterLock(chapterKey)) {
            if (targetPageNumber <= session.windowLimit || session.windowLimit >= session.pages.size) {
                return@synchronized session
            }

            var currentLimit = session.windowLimit
            var currentPages = session.pages
            while (targetPageNumber > currentLimit && currentLimit < currentPages.size) {
                val prevGrant = currentPages[minOf(currentLimit - 1, currentPages.size - 1)]
                    .toHttpUrl().queryParameter("g").orEmpty()
                val refreshed = advanceWindowSync(
                    chain,
                    session.manifestUrl,
                    prevGrant,
                    currentLimit + 1,
                    session.transport,
                    session.chapterUrl,
                )
                val newPages = refreshed.chapter?.pages.orEmpty().mapNotNull { path ->
                    baseUrl.toHttpUrl().resolve(path)?.toString()
                }
                if (newPages.isEmpty()) break
                currentPages = newPages
                val newLimit = extractWindowLimit(currentPages.getOrNull(currentLimit).orEmpty()) ?: currentPages.size
                if (newLimit <= currentLimit) break
                currentLimit = newLimit
            }

            session.pages = currentPages
            session.windowLimit = currentLimit
            session.updatedAt = System.currentTimeMillis()
            session
        }
    }

    private fun refreshChapterSessionSync(
        chain: Interceptor.Chain,
        mangaKey: String,
        chapterNumber: String,
    ): ChapterSession {
        val chapterKey = "$mangaKey/$chapterNumber"
        val transport = SecureReaderTransport.create()
        val manifestUrl = apiUrl("api", "reader", "works", mangaKey, "chapters", chapterNumber)
        val chapterUrl = "$baseUrl/leitor?id=$mangaKey&capitulo=$chapterNumber"
        val req = Request.Builder()
            .url(manifestUrl)
            .header("Accept", "application/json")
            .header("Referer", chapterUrl)
            .header("Origin", baseUrl)
            .header("X-OneReader-Client-Key", transport.clientKey)
            .header("X-OneReader-Key-Transport", KEY_TRANSPORT)
            .cacheControl(CacheControl.FORCE_NETWORK)
            .build()

        var lastError: Exception? = null
        var manifest: PagesDto? = null
        for (attempt in 0 until 3) {
            val resp = chain.proceed(req)
            resp.header("Date")?.let(SecureReaderTransport::updateServerTimeFromHeader)
            if (resp.isSuccessful) {
                manifest = resp.parseAs<PagesDto>()
                resp.close()
                break
            }

            val code = resp.code
            val body = resp.body.string()
            val retryAfterHeader = resp.header("Retry-After")?.toLongOrNull()
            resp.close()

            val isCooldown = code == 429 && (body.contains("READER_COOLDOWN") || body.contains("READER_RATE_LIMITED"))
            if (isCooldown && attempt < 2) {
                val waitSeconds = retryAfterHeader ?: 1L
                Thread.sleep(waitSeconds * 1000L + Random.nextLong(50L, 250L))
                continue
            }

            val detail = body.replace(Regex("[\\r\\n\\t]+"), " ").take(MAX_ERROR_BODY_LENGTH)
            lastError = IOException("Falha ao atualizar manifesto do leitor: HTTP $code: $detail")
            break
        }

        if (manifest == null) {
            throw lastError ?: IOException("Falha ao atualizar manifesto do leitor")
        }

        transport.configure(manifest.protection?.transport)
        val currentPages = manifest.chapter?.pages.orEmpty().mapNotNull { path ->
            baseUrl.toHttpUrl().resolve(path)?.toString()
        }
        val currentLimit = extractWindowLimit(currentPages.firstOrNull().orEmpty()) ?: currentPages.size

        val newSession = ChapterSession(
            transport = transport,
            manifestUrl = manifestUrl,
            chapterUrl = chapterUrl,
            pages = currentPages,
            windowLimit = currentLimit,
        )
        chapterSessions[chapterKey] = newSession
        return newSession
    }

    private fun advanceWindowSync(
        chain: Interceptor.Chain,
        manifestUrl: HttpUrl,
        previousGrant: String,
        nextPageNumber: Int,
        transport: SecureReaderTransport,
        chapterUrl: String,
    ): PagesDto {
        val refreshUrl = manifestUrl.newBuilder()
            .addQueryParameter("_or_pg", previousGrant)
            .addQueryParameter("_or_page", nextPageNumber.toString())
            .build()

        val req = Request.Builder()
            .url(refreshUrl)
            .header("Accept", "application/json")
            .header("Referer", chapterUrl)
            .header("Origin", baseUrl)
            .header("X-OneReader-Client-Key", transport.clientKey)
            .header("X-OneReader-Key-Transport", KEY_TRANSPORT)
            .cacheControl(CacheControl.FORCE_NETWORK)
            .build()

        var lastError: Exception? = null
        for (attempt in 0 until 3) {
            val resp = chain.proceed(req)
            resp.header("Date")?.let(SecureReaderTransport::updateServerTimeFromHeader)
            if (resp.isSuccessful) {
                val refreshed = resp.parseAs<PagesDto>()
                resp.close()
                transport.configure(refreshed.protection?.transport)
                return refreshed
            }

            val code = resp.code
            val body = resp.body.string()
            val retryAfterHeader = resp.header("Retry-After")?.toLongOrNull()
            resp.close()

            val isWait = code == 429 && (body.contains("READER_PAGE_WINDOW_WAIT") || body.contains("READER_RATE_LIMITED") || body.contains("READER_COOLDOWN"))
            if (isWait && attempt < 2) {
                val waitSeconds = retryAfterHeader ?: 1L
                Thread.sleep(waitSeconds * 1000L + Random.nextLong(50L, 250L))
                continue
            }

            val detail = body.replace(Regex("[\\r\\n\\t]+"), " ").take(MAX_ERROR_BODY_LENGTH)
            lastError = IOException("Falha ao avançar janela sincronamente: HTTP $code: $detail")
            break
        }

        throw lastError ?: IOException("Falha ao avançar janela sincronamente")
    }

    private fun authorizeMediaWithChain(
        chain: Interceptor.Chain,
        pageUrl: HttpUrl,
        mangaKey: String,
        chapterNumber: String,
        transport: SecureReaderTransport,
    ): MediaGrant {
        val cached = mediaGrants[pageUrl]
        if (cached != null && (System.currentTimeMillis() - cached.createdAt) < MEDIA_GRANT_TTL_MS) {
            return cached
        }

        var lastError: Exception? = null
        val chapterUrl = "$baseUrl/leitor?id=$mangaKey&capitulo=$chapterNumber"

        for (attempt in 0 until 3) {
            val proofHeaders = transport.buildProofHeaders(pageUrl)
            val authReq = Request.Builder()
                .url(pageUrl)
                .header("Accept", "application/vnd.onereader.media+json")
                .header("Referer", chapterUrl)
                .header("Origin", baseUrl)
                .header("X-OneReader-Client-Key", transport.clientKey)
                .header("X-OneReader-Key-Transport", KEY_TRANSPORT)
                .apply {
                    proofHeaders.forEach { (name, value) -> header(name, value) }
                }
                .cacheControl(CacheControl.FORCE_NETWORK)
                .build()

            val response = chain.proceed(authReq)
            response.header("Date")?.let(SecureReaderTransport::updateServerTimeFromHeader)
            if (response.isSuccessful) {
                val grant = response.parseAs<MediaGrantDto>()
                if (!grant.ok || grant.mode !in SUPPORTED_MEDIA_MODES) {
                    throw IOException("Autorização de mídia inválida")
                }
                val keyBytes = grant.keyWrap?.let(transport::unwrap)
                if (grant.mode == MODE_AES_GCM_V4 && keyBytes == null) {
                    throw IOException("Chave ORX4 ausente")
                }
                if (grant.mode == MODE_XOR_PREFIX_V3 && grant.key.isBlank()) {
                    throw IOException("Chave ORX3 ausente")
                }
                val mediaGrant = MediaGrant(grant.url, grant.key, keyBytes, grant.contentType)
                mediaGrants[pageUrl] = mediaGrant
                return mediaGrant
            }

            val code = response.code
            val body = response.body.string()
            val retryAfterHeader = response.header("Retry-After")?.toLongOrNull()
            response.close()

            val isProofExpired = code == 428 && body.contains("READER_PROOF_EXPIRED")
            if (isProofExpired && attempt < 2) {
                Thread.sleep(100L)
                continue
            }

            val isRateLimited = code == 429
            if (isRateLimited && attempt < 2) {
                val waitSeconds = retryAfterHeader ?: 1L
                val baseWaitMs = waitSeconds * 1000L
                val jitter = Random.nextLong(50L, 250L)
                Thread.sleep(baseWaitMs + jitter)
                continue
            }

            val detail = body.replace(Regex("[\\r\\n\\t]+"), " ").take(MAX_ERROR_BODY_LENGTH)
            lastError = IOException("Falha ao autorizar mídia: HTTP $code: $detail")
            break
        }

        throw lastError ?: IOException("Falha ao autorizar mídia da página")
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
        private const val MAX_ADVANCE_ATTEMPTS = 4
        private const val DEFAULT_RETRY_AFTER_SECONDS = 1L
        private const val SESSION_TTL_MS = 10 * 60 * 1000L
        private const val MEDIA_GRANT_TTL_MS = 3 * 60 * 1000L
        private const val KEY_TRANSPORT = "ecdh-p256-aesgcm-v1"
        private const val MODE_XOR_PREFIX_V3 = "xor-prefix-v3"
        private const val MODE_AES_GCM_V4 = "aes-gcm-v4"
        private val SUPPORTED_MEDIA_MODES = setOf(MODE_XOR_PREFIX_V3, MODE_AES_GCM_V4)
        private val ORX3_MAGIC = byteArrayOf(0x4f, 0x52, 0x58, 0x33)
        private val ORX4_MAGIC = byteArrayOf(0x4f, 0x52, 0x58, 0x34)
        private val mediaGrants = ConcurrentHashMap<HttpUrl, MediaGrant>()
        private val chapterSessions = ConcurrentHashMap<String, ChapterSession>()
        private val chapterLocks = ConcurrentHashMap<String, Any>()

        private fun getChapterLock(key: String): Any = chapterLocks.computeIfAbsent(key) { Any() }
    }
}

private class ChapterSession(
    val transport: SecureReaderTransport,
    val manifestUrl: HttpUrl,
    val chapterUrl: String,
    @Volatile var pages: List<String>,
    @Volatile var windowLimit: Int,
    @Volatile var updatedAt: Long = System.currentTimeMillis(),
)

private data class MediaGrant(
    val url: String,
    val key: String,
    val keyBytes: ByteArray?,
    val contentType: String,
    val createdAt: Long = System.currentTimeMillis(),
)

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
