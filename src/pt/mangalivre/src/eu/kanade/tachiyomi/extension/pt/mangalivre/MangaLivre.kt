package eu.kanade.tachiyomi.extension.pt.mangalivre

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.webkit.CookieManager
import android.webkit.WebResourceResponse
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.WebViewTimeoutException
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.runWebView
import keiyoushi.utils.toJsonRequestBody
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URLEncoder
import java.util.Collections
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Source
abstract class MangaLivre :
    KeiSource(),
    ConfigurableSource {

    private val baseUrlHost by lazy { baseUrl.toHttpUrl().host }

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(2, 1.seconds) { it.host == baseUrlHost }
        .addNetworkInterceptor { chain ->
            val req = chain.request()
            val isCdn = req.url.host == CDN_HOST || req.url.host.endsWith("edgecompute.app")
            val pageIdx = req.header("X-Page-Index")
            val t0 = SystemClock.elapsedRealtime()
            val resp = chain.proceed(req)
            val t1 = SystemClock.elapsedRealtime()
            if (isCdn && pageIdx != null) {
                if (pageIdx == "0") {
                    Log.d("ToonLivrePerf", "first_image_received page=0 code=${resp.code} duration=${t1 - t0}ms t=$t1")
                    runCatching {
                        Handler(Looper.getMainLooper()).post {
                            Choreographer.getInstance().postFrameCallback {
                                val tVisible = SystemClock.elapsedRealtime()
                                Log.d("ToonLivrePerf", "first_page_visible page=0 t=$tVisible")
                            }
                        }
                    }
                }
                Log.d("ToonLivrePerf", "image_received page=$pageIdx code=${resp.code} duration=${t1 - t0}ms t=$t1")
            }
            resp
        }

    private val apiUrl: String get() = "$baseUrl/api"

    private val preferences by getPreferencesLazy()

    private val pageListCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, List<Page>>(30, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Page>>?): Boolean = size > 30
        },
    )

    private val mangaMetadataCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, MangaDto>(20, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MangaDto>?): Boolean = size > 20
        },
    )

    override fun Headers.Builder.configureHeaders(): Headers.Builder = set("Accept", "*/*")
        .set("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
        .set("Referer", "$baseUrl/")
        .set("Sec-Fetch-Dest", "empty")
        .set("Sec-Fetch-Mode", "cors")
        .set("Sec-Fetch-Site", "same-origin")

    // ============================== Popular =======================================

    override suspend fun getPopularManga(page: Int): MangasPage = getSearchMangaList(
        page,
        "",
        FilterList(
            listOf(
                OrderByFilter(options = listOf("" to SORT_POPULAR)),
                OrderDirectionFilter(options = listOf("" to DIRECTION_DESC)),
            ),
        ),
    )

    // ============================== Latest =======================================

    override suspend fun getLatestUpdates(page: Int): MangasPage = getSearchMangaList(
        page,
        "",
        FilterList(
            listOf(
                OrderByFilter(options = listOf("" to SORT_UPDATED)),
                OrderDirectionFilter(options = listOf("" to DIRECTION_DESC)),
            ),
        ),
    )

    // ============================== Search =======================================

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val url = "$apiUrl/mangas/search".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", "24")

        if (query.isNotBlank()) {
            url.addQueryParameter("q", query)
        }

        filters.forEach { filter ->
            when (filter) {
                is OrderByFilter -> {
                    url.addQueryParameter("sortBy", filter.selected())
                }
                is OrderDirectionFilter -> {
                    url.addQueryParameter("sortOrder", filter.selected())
                }
                else -> {}
            }
        }
        val dto = client.get(url.build()).parseJson<WrapperDto>()
        val mangas = dto.mangas.map { it.toSManga(useAlternativeTitle) }
        return MangasPage(mangas, dto.hasNextPage)
    }

    // ============================== Details =======================================

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/${manga.url}"

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val dto = client.get("$apiUrl/manga-by-slug/${manga.url}").parseJson<MangaDto>()
        return SMangaUpdate(
            manga = if (fetchDetails) dto.toSManga(useAlternativeTitle) else manga,
            chapters = if (fetchChapters) dto.toSChapterList() else chapters,
        )
    }

    // ============================== Pages =======================================

    private val verificationMutex = Mutex()

    private fun normalizeCacheKey(url: String): String {
        val httpUrl = (if (url.startsWith("http")) url else "$baseUrl$url").toHttpUrlOrNull()
            ?: return url.substringBefore("#").trimEnd('/')
        return httpUrl.encodedPath.trimEnd('/')
    }

    private suspend fun fetchChapterReference(slug: String, chapterNumber: String): ChapterReferenceDto? {
        if (slug.isEmpty()) return null
        val cachedManga = mangaMetadataCache[slug]
        val mangaDto = cachedManga ?: runCatching {
            client.get("$apiUrl/manga-by-slug/$slug").parseJson<MangaDto>().also {
                mangaMetadataCache[slug] = it
            }
        }.getOrNull() ?: return null

        val targetChapter = mangaDto.chapters?.firstOrNull { it.number == chapterNumber }
        return ChapterReferenceDto(
            mangaId = mangaDto.id,
            chapterId = targetChapter?.id.orEmpty(),
            pageCount = targetChapter?.pageCount,
        )
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val cacheKey = normalizeCacheKey(chapter.url)
        Log.d("ToonLivrePerf", "chapter_open_start url=${chapter.url} cacheKey=$cacheKey t=${SystemClock.elapsedRealtime()}")
        pageListCache[cacheKey]?.takeIf { it.isNotEmpty() }?.let {
            Log.d("ToonLivrePerf", "page_list_cache_hit count=${it.size} t=${SystemClock.elapsedRealtime()}")
            return it
        }

        val chapterUrl = "$baseUrl${chapter.url}".toHttpUrl()
        var ref = runCatching { chapter.memo.parseAs<ChapterReferenceDto>() }.getOrNull()
        if (ref == null) {
            ref = chapterUrl.fragment?.let {
                runCatching { it.parseAs<ChapterReferenceDto>() }.getOrNull()
            }
        }
        val chapterNumber = chapterUrl.pathSegments.last { it.isNotEmpty() }
        val slug = chapterUrl.pathSegments.firstOrNull { it.isNotEmpty() && it != chapterNumber }.orEmpty()

        if (ref == null && slug.isNotEmpty()) {
            ref = fetchChapterReference(slug, chapterNumber)
        }

        return verificationMutex.withLock {
            pageListCache[cacheKey]?.takeIf { it.isNotEmpty() }?.let { return@withLock it }

            // 1. Direct API Access: Try fetchReaderAccess first!
            if (ref != null) {
                val access = runCatching { fetchReaderAccess(ref) }.getOrNull()
                if (access != null) {
                    val pages = resolveAccessPages(access, ref, chapterNumber)
                    if (pages.isNotEmpty()) {
                        pageListCache[cacheKey] = pages
                        Log.d("ToonLivrePerf", "direct_access_success count=${pages.size} t=${SystemClock.elapsedRealtime()}")
                        return@withLock pages
                    }
                }
            }

            // 2. Headless verification: runWebView (NO Activity, NO visible UI)
            val readerUrl = chapterUrl.newBuilder().fragment(null).build().toString()
            val pages = getPageListWithWebView(readerUrl, ref, chapterNumber)
            if (pages.isNotEmpty()) {
                pageListCache[cacheKey] = pages
            }
            Log.d("ToonLivrePerf", "page_list_ready count=${pages.size} t=${SystemClock.elapsedRealtime()}")
            pages
        }
    }

    private suspend fun fetchReaderAccess(ref: ChapterReferenceDto): ReaderAccessResponseDto? {
        Log.d("ToonLivrePerf", "calling_reader_access mangaId=${ref.mangaId} chapterId=${ref.chapterId}")
        val reqHeaders = headers.newBuilder()
            .set("Accept", "application/json, text/plain, */*")
            .set("Content-Type", "application/json")
            .set("Origin", baseUrl)
            .set("Referer", "$baseUrl/")
            .apply {
                runCatching {
                    val cookieStr = CookieManager.getInstance().getCookie(baseUrl)
                    val csrf = cookieStr?.split(";")
                        ?.map { it.trim() }
                        ?.firstOrNull { it.startsWith("csrf_token=") }
                        ?.substringAfter("csrf_token=")
                    if (!csrf.isNullOrEmpty()) {
                        set("x-csrf-token", csrf)
                    }
                }
            }
            .build()

        return client.post(
            "$apiUrl/reader/chapter/access",
            headers = reqHeaders,
            body = ref.toJsonRequestBody(),
            ensureSuccess = false,
        ).use { response ->
            Log.d("ToonLivrePerf", "reader_access_code=${response.code}")
            if (response.isSuccessful) {
                return@use response.parseAs<ReaderAccessResponseDto>()
            }
            val peek = response.peekBody(MAX_PEEK).string()
            val error = runCatching { response.parseAs<ReaderAccessErrorDto>() }.getOrNull()
            if (response.code == 403 && (error?.error == READER_VERIFICATION_REQUIRED || error?.requiresTurnstile == true || peek.contains("Reader verification required"))) {
                return@use null
            }
            throw IOException(error?.error ?: "HTTP ${response.code}: $peek")
        }
    }

    private suspend fun resolveAccessPages(
        access: ReaderAccessResponseDto,
        ref: ChapterReferenceDto,
        chapterNumber: String,
    ): List<Page> {
        val directPages = access.chapter?.pages
        if (!directPages.isNullOrEmpty()) {
            val pages = directPages.toPageList(ref.mangaId, chapterNumber)
            if (pages.isNotEmpty()) return pages
        }

        if (access.protectedPages == true && access.firstHandle?.token != null) {
            val count = access.chapter?.pageCount ?: ref.pageCount ?: access.firstHandle.pageCount ?: 200
            val urls = fetchProtectedPagesViaOkHttp(access.firstHandle, count)
            if (urls.isNotEmpty()) {
                return urls.toPageList(ref.mangaId, chapterNumber)
            }
        }

        return emptyList()
    }

    private suspend fun fetchProtectedPagesViaOkHttp(
        firstHandle: HandleDto,
        maxCount: Int,
    ): List<String> {
        val urls = mutableListOf<String>()
        var cur: HandleDto? = firstHandle
        val reqHeaders = headers.newBuilder()
            .set("Accept", "application/json, text/plain, */*")
            .set("Referer", "$baseUrl/")
            .build()

        while (cur != null && cur.token.isNotEmpty() && urls.size < maxCount) {
            val token = cur.token
            val pageResp = runCatching {
                val encodedToken = URLEncoder.encode(token, "UTF-8")
                client.get(
                    "$apiUrl/reader/p/$encodedToken",
                    headers = reqHeaders,
                ).parseJson<PageResponseDto>()
            }.getOrNull() ?: break

            val imgUrl = pageResp.imageUrl
            if (!imgUrl.isNullOrEmpty()) {
                urls.add(imgUrl)
                cur = pageResp.nextHandle
            } else {
                break
            }
        }
        return urls
    }

    private fun prewarmCdnConnection() {
        client.dispatcher.executorService.execute {
            runCatching {
                val req = Request.Builder()
                    .url("https://$CDN_HOST/favicon.ico")
                    .head()
                    .build()
                client.newCall(req).execute().close()
            }
        }
    }

    private suspend fun getPageListWithWebView(
        readerUrl: String,
        ref: ChapterReferenceDto?,
        chapterNumber: String,
    ): List<Page> {
        val mangaId = ref?.mangaId.orEmpty()
        val expectedCount = ref?.pageCount
        val imageUrls = Collections.synchronizedSet(LinkedHashSet<String>())
        val dynamicPageCount = AtomicInteger(expectedCount ?: -1)
        val bridgeName = (1..(10..20).random())
            .map { (('a'..'z') + ('A'..'Z')).random() }
            .joinToString("")
        val collectImageUrlsScript = collectImageUrlsScript(bridgeName)
        val hookFetchScript = hookFetchScript(bridgeName)

        val emptyTextBytes = ByteArray(0)
        val emptyTextHeaders = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, POST, OPTIONS, HEAD",
            "Access-Control-Allow-Headers" to "*",
        )
        fun createEmptyTextResponse(): WebResourceResponse = WebResourceResponse("text/plain", "UTF-8", 200, "OK", emptyTextHeaders, ByteArrayInputStream(emptyTextBytes))
        fun create404Response(): WebResourceResponse = WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyTextHeaders, ByteArrayInputStream(emptyTextBytes))

        Log.d("ToonLivrePerf", "headless_webview_start url=$readerUrl t=${SystemClock.elapsedRealtime()}")
        prewarmCdnConnection()

        try {
            return runWebView(timeout = WEBVIEW_TIMEOUT) {
                var previousCount = 0
                var stablePolls = 0

                javaScriptEnabled = true
                domStorageEnabled = true

                fun collect(rawUrl: String) {
                    val imageUrl = rawUrl.toValidImageUrl() ?: return
                    if (!imageUrl.isChapterImage(mangaId, chapterNumber)) return
                    if (imageUrls.add(imageUrl)) {
                        val count = expectedCount ?: dynamicPageCount.get().takeIf { it > 0 }
                        if (count != null && imageUrls.size >= count) {
                            Log.d("ToonLivrePerf", "chapter_access_done collected_count=${imageUrls.size} t=${SystemClock.elapsedRealtime()}")
                            resolve(imageUrls.toPageList(mangaId, chapterNumber))
                        }
                    }
                }

                interceptRequest { request ->
                    val urlStr = request.url.toString()
                    val host = request.url.host.orEmpty()

                    // 1. Allow Cloudflare Turnstile, ToonLivre API and edge CDN
                    if (isAllowedHost(host)) {
                        // Fonts: return 404 to avoid OTS font corruption warnings
                        if (urlStr.endsWith(".woff2") || urlStr.endsWith(".ttf") || urlStr.endsWith(".woff")) {
                            return@interceptRequest create404Response()
                        }
                        return@interceptRequest null
                    }

                    // 2. Block third-party ad networks, trackers
                    if (urlStr.contains("ptag.js") || urlStr.contains("pubadx") ||
                        urlStr.contains("googletagmanager") || urlStr.contains("cloudflareinsights") ||
                        urlStr.contains("whos.amung.us")
                    ) {
                        return@interceptRequest createEmptyTextResponse()
                    }

                    null
                }

                jsBridge(bridgeName) { payload ->
                    try {
                        val json = Json.parseToJsonElement(payload).jsonObject
                        val type = json["type"]?.jsonPrimitive?.content
                        if (type == "PAGES") {
                            val list = json["pages"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
                            if (list.isNotEmpty()) {
                                Log.d("ToonLivrePerf", "bridge_received_pages count=${list.size} t=${SystemClock.elapsedRealtime()}")
                                val pages = list.toPageList(mangaId, chapterNumber)
                                if (pages.isNotEmpty()) {
                                    resolve(pages)
                                    return@jsBridge
                                }
                            }
                        }
                    } catch (e: Exception) {
                        val rawList = runCatching { payload.parseAs<List<String>>() }.getOrNull()
                        if (rawList != null) {
                            rawList.forEach(::collect)
                            val count = expectedCount ?: dynamicPageCount.get().takeIf { it > 0 }
                            if (count != null && imageUrls.size >= count) {
                                resolve(imageUrls.toPageList(mangaId, chapterNumber))
                            }
                        }
                    }
                }

                onPageStarted {
                    evaluateJs(hookFetchScript)
                }

                onPageFinished {
                    evaluateJs(hookFetchScript)
                    evaluateJs(collectImageUrlsScript)
                }

                poll(250.milliseconds) {
                    evaluateJs(collectImageUrlsScript)
                    val currentCount = imageUrls.size
                    if (currentCount > 0 && currentCount == previousCount) {
                        stablePolls++
                    } else {
                        stablePolls = 0
                    }
                    previousCount = currentCount
                    val count = expectedCount ?: dynamicPageCount.get().takeIf { it > 0 }
                    if (stablePolls >= STABLE_POLLS && imageUrls.isNotEmpty() && (count == null || imageUrls.size >= count)) {
                        resolve(imageUrls.toPageList(mangaId, chapterNumber))
                    }
                }

                val navHeaders = mapOf(
                    "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
                    "Referer" to "$baseUrl/",
                )
                Log.d("ToonLivrePerf", "webview_first_navigation t=${SystemClock.elapsedRealtime()}")
                loadUrl(readerUrl, navHeaders)
            }
        } catch (error: WebViewTimeoutException) {
            if (imageUrls.isNotEmpty()) {
                return imageUrls.toPageList(mangaId, chapterNumber)
            }
            throw IOException("Tempo esgotado na verificação automática do leitor (${error.message ?: "timeout"})")
        } finally {
            runCatching {
                CookieManager.getInstance().flush()
            }
        }
    }

    override fun imageRequest(page: Page): Request {
        if (page.index == 0) {
            Log.d("ToonLivrePerf", "first_image_request page=0 t=${SystemClock.elapsedRealtime()}")
        }
        val builder = headers.newBuilder()
            .set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .set("Sec-Fetch-Dest", "image")
            .set("Sec-Fetch-Mode", "no-cors")
            .set("X-Page-Index", page.index.toString())

        val imgUrl = page.imageUrl
        val httpUrl = imgUrl?.toHttpUrlOrNull()
        if (httpUrl != null && httpUrl.host.endsWith("edgecompute.app")) {
            builder.removeAll("Referer")
        } else {
            builder.set("Referer", "$baseUrl/")
        }
        return GET(imgUrl!!, builder.build())
    }

    // ============================== Filters =======================================

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        listOf(
            OrderByFilter(
                "Ordem",
                listOf(
                    "Mais Visualizados" to SORT_POPULAR,
                    "Lançamentos" to SORT_RELEASE,
                    "Última Atualização" to SORT_UPDATED,
                    "Melhor Avaliação" to SORT_RATING,
                    "A-Z" to SORT_TITLE,
                ),
            ),
            Filter.Separator(),
            OrderDirectionFilter(
                "Direção",
                listOf(
                    "↑ Decrescente" to DIRECTION_DESC,
                    "↓ Crescente" to DIRECTION_ASC,
                ),
            ),
        ),
    )

    val useAlternativeTitle: Boolean get() =
        preferences.getBoolean(ALTERNATIVE_TITLE_PREF, false)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = ALTERNATIVE_TITLE_PREF
            title = "Titulo alternativo"
            summary = buildString {
                append("Use titulos alternativos como principal quando disponivel.")
                append(" Essa opção não tem efeito sobre obras já adicionadas na sua biblioteca")
            }
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    // ============================== Utilities =======================================

    private inline fun <reified T> Response.parseJson(): T {
        val peek = peekBody(MAX_PEEK).string().trimStart()
        if (peek.isEmpty() || peek.startsWith("<")) {
            close()
            throw IOException(NON_JSON_MESSAGE)
        }
        return parseAs<T>()
    }

    companion object {
        private const val STABLE_POLLS = 2
        private val WEBVIEW_TIMEOUT = 10.seconds
        private const val CDN_HOST = "cdn.toonlivre.net"
        private const val PROXY_HOST = "slightly-free-mayfly.edgecompute.app"
        private val PAGE_NUMBER_REGEX = Regex("""[-_](\d+)\.[^.]+$""")
        private val PAGE_DERIVE_REGEX = Regex("""^(.*[-_])(\d+)(\.[a-zA-Z0-9]+)$""")

        private const val ALTERNATIVE_TITLE_PREF = "alternativeTitlePref"
        private const val MAX_PEEK = 1024L
        private const val NON_JSON_MESSAGE =
            "Resposta não-JSON (Cloudflare ou header desatualizado). Abra a fonte na WebView do app e tente de novo."
        private const val READER_VERIFICATION_REQUIRED = "Reader verification required"

        private const val SORT_POPULAR = "popular"
        private const val SORT_RELEASE = "release"
        private const val SORT_UPDATED = "updated"
        private const val SORT_RATING = "rating"
        private const val SORT_TITLE = "title"
        private const val DIRECTION_DESC = "desc"
        private const val DIRECTION_ASC = "asc"
    }

    private fun derivePages(
        firstUrl: String,
        mangaId: String,
        chapterNumber: String,
        pageCount: Int,
    ): List<Page> {
        if (pageCount <= 1) {
            return listOf(firstUrl).toPageList(mangaId, chapterNumber)
        }
        val match = PAGE_DERIVE_REGEX.find(firstUrl) ?: return listOf(firstUrl).toPageList(mangaId, chapterNumber)
        val prefix = match.groupValues[1]
        val digits = match.groupValues[2]
        val ext = match.groupValues[3]
        val pad = digits.length.coerceAtLeast(2)
        val urls = (1..pageCount).map { i ->
            "$prefix${i.toString().padStart(pad, '0')}$ext"
        }
        return urls.toPageList(mangaId, chapterNumber)
    }

    private fun isAllowedHost(host: String): Boolean = host.isEmpty() ||
        host == "toonlivre.net" ||
        host.endsWith(".toonlivre.net") ||
        host == "edgecompute.app" ||
        host.endsWith(".edgecompute.app") ||
        host == "challenges.cloudflare.com" ||
        host.endsWith(".cloudflare.com") ||
        host.endsWith(".googleapis.com") ||
        host.endsWith(".gstatic.com")

    private fun hookFetchScript(bridgeName: String) =
        """
        (() => {
            if (window.__tl_hooked) return;
            window.__tl_hooked = true;

            const postMessage = (msg) => {
                try {
                    if (window['$bridgeName'] && window['$bridgeName'].post) {
                        window['$bridgeName'].post(JSON.stringify(msg));
                    }
                } catch(e) {}
            };

            const fetchAllPages = async (firstHandle, maxCount) => {
                try {
                    let cur = firstHandle;
                    const count = maxCount || cur.pageCount || 200;
                    const urls = [];
                    while (cur && cur.token && urls.length < count) {
                        const pRes = await window.fetch('/api/reader/p/' + encodeURIComponent(cur.token), { cache: 'no-store' });
                        if (!pRes.ok) break;
                        const pData = await pRes.json();
                        if (pData && pData.imageUrl) {
                            urls.push(pData.imageUrl);
                            cur = pData.nextHandle;
                        } else {
                            break;
                        }
                    }
                    if (urls.length > 0) {
                        postMessage({ type: 'PAGES', pages: urls });
                    }
                } catch(e) {
                    console.log('__tl_fetch_all_err:' + e);
                }
            };

            const origFetch = window.fetch;
            if (origFetch) {
                window.fetch = async function(...args) {
                    const u = typeof args[0] === 'string' ? args[0] : (args[0] && args[0].url);
                    const urlStr = String(u || '');
                    const isAccess = urlStr.indexOf('/api/reader/chapter/access') !== -1;
                    const res = await origFetch.apply(this, args);

                    if (isAccess) {
                        try {
                            const clone = res.clone();
                            clone.json().then(async data => {
                                if (!data) return;
                                if (data.interactivePending) {
                                    postMessage({ error: 'interactivePending' });
                                    return;
                                }

                                const pages = data.chapter && data.chapter.pages;
                                if (Array.isArray(pages) && pages.length > 0) {
                                    postMessage({ type: 'PAGES', pages: pages });
                                    return;
                                }

                                if (data.protectedPages && data.firstHandle && data.firstHandle.token) {
                                    const expectedCount = data.pageCount || (data.chapter && data.chapter.pageCount) || data.firstHandle.pageCount || 200;
                                    await fetchAllPages(data.firstHandle, expectedCount);
                                    return;
                                }

                                if (data.grant && data.grant.token && data.chapter && data.chapter.pageCount) {
                                    const mId = data.chapter.mangaId || '';
                                    const cId = data.chapter.id || '';
                                    const count = data.chapter.pageCount;
                                    const grantToken = data.grant.token;
                                    const urls = [];
                                    for (let p = 1; p <= count; p++) {
                                        try {
                                            const pRes = await origFetch('/api/reader/chapter/' + encodeURIComponent(mId) + '/' + encodeURIComponent(cId) + '/page/' + p, {
                                                headers: { 'x-tl-reader-grant': grantToken }
                                            });
                                            if (!pRes.ok) break;
                                            const pData = await pRes.json();
                                            if (pData && pData.imageUrl) urls.push(pData.imageUrl);
                                        } catch(e) { break; }
                                    }
                                    if (urls.length > 0) {
                                        postMessage({ type: 'PAGES', pages: urls });
                                        return;
                                    }
                                }
                            }).catch(() => {});
                        } catch(e) {}
                    }
                    return res;
                };
            }
        })();
        """.trimIndent()

    private fun collectImageUrlsScript(bridgeName: String) =
        """
        (() => {
            const urls = new Set();
            document.querySelectorAll('img').forEach((image) => {
                [image.currentSrc, image.src, image.dataset.src].forEach((url) => {
                    if (url) urls.add(url);
                });
            });
            if (window.performance && performance.getEntriesByType) {
                performance.getEntriesByType('resource').forEach((entry) => urls.add(entry.name));
            }
            try {
                if (window['$bridgeName'] && window['$bridgeName'].post) {
                    window['$bridgeName'].post(JSON.stringify(Array.from(urls)));
                }
            } catch(e) {}
        })();
        """.trimIndent()

    private fun String.toValidImageUrl(): String? {
        val url = toHttpUrlOrNull() ?: return null
        if (!url.isHttps) return null
        val host = url.host
        if (host == CDN_HOST || host.endsWith("edgecompute.app")) return this
        val queryUrl = url.queryParameter("url")?.toHttpUrlOrNull()
        if (queryUrl != null && queryUrl.isHttps && (queryUrl.host == CDN_HOST || queryUrl.host.endsWith("edgecompute.app"))) {
            return queryUrl.toString()
        }
        return null
    }

    private fun String.isChapterImage(mangaId: String, chapterNumber: String): Boolean {
        val url = toHttpUrlOrNull() ?: return false
        val host = url.host
        if (host.endsWith("edgecompute.app")) {
            return url.pathSegments.size >= 2 && url.pathSegments[0] == "reader" && url.pathSegments[1] == "image"
        }
        val pathSegments = url.pathSegments
        return pathSegments.size >= 4 &&
            pathSegments[0] == "obras" &&
            (mangaId.isEmpty() || pathSegments[1] == mangaId) &&
            pathSegments[2] == chapterNumber &&
            pathSegments[3].isNotEmpty()
    }

    private fun Collection<String>.toPageList(mangaId: String, chapterNumber: String): List<Page> = synchronized(this) {
        val validUrls = asSequence()
            .mapNotNull { it.toValidImageUrl() }
            .filter { it.isChapterImage(mangaId, chapterNumber) }
            .distinct()
            .toList()

        val hasNumericPages = validUrls.any { it.pageNumber() != null }
        val sortedUrls = if (hasNumericPages) {
            validUrls.sortedWith(
                compareBy<String>({ it.pageNumber() ?: Int.MAX_VALUE }, { it }),
            )
        } else {
            validUrls
        }
        sortedUrls.mapIndexed { index, imageUrl -> Page(index, url = imageUrl, imageUrl = imageUrl) }
    }

    private fun String.pageNumber(): Int? = toHttpUrl().pathSegments.lastOrNull()
        ?.let(PAGE_NUMBER_REGEX::find)
        ?.groupValues
        ?.get(1)
        ?.toIntOrNull()
}
