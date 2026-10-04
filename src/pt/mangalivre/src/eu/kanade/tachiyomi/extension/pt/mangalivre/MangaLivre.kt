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
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.WebViewTimeoutException
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.runWebView
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayInputStream
import java.io.IOException
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
            val isCdn = req.url.host == CDN_HOST
            val pageIdx = req.header("X-Page-Index")
            val t0 = SystemClock.elapsedRealtime()
            val resp = chain.proceed(req)
            val t1 = SystemClock.elapsedRealtime()
            if (isCdn && pageIdx != null) {
                if (pageIdx == "0") {
                    Log.d("ToonLivrePerf", "first_image_received page=0 code=${resp.code} duration=${t1 - t0}ms t=$t1")
                    Handler(Looper.getMainLooper()).post {
                        Choreographer.getInstance().postFrameCallback {
                            val tVisible = SystemClock.elapsedRealtime()
                            Log.d("ToonLivrePerf", "first_page_visible page=0 t=$tVisible")
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

    private fun clearToonLivreCookies() {
        Log.d("ToonLivrePerf", "clearing_cookies t=${SystemClock.elapsedRealtime()}")
        val cm = CookieManager.getInstance()
        cm.removeAllCookies(null)
        cm.flush()
    }

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
        var ref = chapterUrl.fragment?.let {
            runCatching { it.parseAs<ChapterReferenceDto>() }.getOrNull()
        }
        val chapterNumber = chapterUrl.pathSegments.last { it.isNotEmpty() }
        val slug = chapterUrl.pathSegments.firstOrNull { it.isNotEmpty() && it != chapterNumber }.orEmpty()

        if (ref == null && slug.isNotEmpty()) {
            ref = fetchChapterReference(slug, chapterNumber)
        }

        val pages = getPageListWithWebView(chapter, ref, chapterNumber)
        if (pages.isNotEmpty()) {
            pageListCache[cacheKey] = pages
        }
        Log.d("ToonLivrePerf", "page_list_ready count=${pages.size} t=${SystemClock.elapsedRealtime()}")
        return pages
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
        chapter: SChapter,
        ref: ChapterReferenceDto?,
        chapterNumber: String,
    ): List<Page> {
        val chapterUrl = "$baseUrl${chapter.url}".toHttpUrl()
        val mangaId = ref?.mangaId.orEmpty()
        val expectedCount = ref?.pageCount
        val readerUrl = chapterUrl.newBuilder().fragment(null).build().toString()
        val imageUrls = Collections.synchronizedSet(LinkedHashSet<String>())
        val bridgeName = (1..(10..20).random())
            .map { (('a'..'z') + ('A'..'Z')).random() }
            .joinToString("")
        val collectImageUrlsScript = collectImageUrlsScript(bridgeName)
        val hookFetchScript = hookFetchScript(bridgeName)

        val emptyImageBytes = ByteArray(0)
        val emptyTextBytes = ByteArray(0)

        val emptyTextHeaders = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, POST, OPTIONS, HEAD",
            "Access-Control-Allow-Headers" to "*",
        )

        fun createEmptyImageResponse(): WebResourceResponse = WebResourceResponse("image/webp", "UTF-8", ByteArrayInputStream(emptyImageBytes))

        fun createEmptyTextResponse(): WebResourceResponse = WebResourceResponse("text/plain", "UTF-8", 200, "OK", emptyTextHeaders, ByteArrayInputStream(emptyTextBytes))

        fun create404Response(): WebResourceResponse = WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyTextHeaders, ByteArrayInputStream(emptyTextBytes))

        Log.d("ToonLivrePerf", "webview_create_start url=$readerUrl t=${SystemClock.elapsedRealtime()}")
        prewarmCdnConnection()

        try {
            return runWebView(timeout = WEBVIEW_TIMEOUT) {
                var previousCount = 0
                var stablePolls = 0
                var turnstileStarted = false
                var chapterAccessStarted = false

                val dynamicPageCount = AtomicInteger(-1)

                javaScriptEnabled = true
                domStorageEnabled = true

                fun tryResolveDerived(firstCdnUrl: String): Boolean {
                    val count = expectedCount ?: dynamicPageCount.get().takeIf { it > 0 }
                    if (count != null && count > 0) {
                        val resolvedMangaId = mangaId.ifEmpty {
                            firstCdnUrl.toHttpUrlOrNull()?.pathSegments?.getOrNull(1).orEmpty()
                        }
                        val derived = derivePages(firstCdnUrl, resolvedMangaId, chapterNumber, count)
                        if (derived.isNotEmpty()) {
                            Log.d("ToonLivrePerf", "chapter_access_done derived_count=${derived.size} t=${SystemClock.elapsedRealtime()}")
                            resolve(derived)
                            return true
                        }
                    }
                    return false
                }

                fun collect(rawUrl: String) {
                    val imageUrl = rawUrl.toCdnImageUrl() ?: return
                    if (!imageUrl.isChapterImage(mangaId, chapterNumber)) return
                    if (tryResolveDerived(imageUrl)) return
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
                    Log.d("ToonLivreReq", "REQ host=$host url=${urlStr.take(120)}")

                    // 1. Chapter images / proxy: derive & resolve immediately
                    val cdnUrl = urlStr.toCdnImageUrl()
                    if (cdnUrl != null) {
                        if (cdnUrl.isChapterImage(mangaId, chapterNumber)) {
                            Log.d("ToonLivrePerf", "seen_chapter_image: ${cdnUrl.take(80)}")
                            if (!tryResolveDerived(cdnUrl)) {
                                collect(cdnUrl)
                            }
                        }
                        return@interceptRequest createEmptyImageResponse()
                    }

                    // 2. Cloudflare Turnstile / challenge platform: ALWAYS allow all resources
                    if (host.contains("challenges.cloudflare.com") || host.contains("cloudflare.com")) {
                        if (!turnstileStarted && host.contains("challenges.cloudflare.com")) {
                            turnstileStarted = true
                            Log.d("ToonLivrePerf", "turnstile_start t=${SystemClock.elapsedRealtime()}")
                        }
                        return@interceptRequest null
                    }

                    // 3. Cloudflare Interactive fallback detection: fail-fast!
                    if (urlStr.contains("/api/reader/turnstile/interactive")) {
                        Log.w("ToonLivrePerf", "detected_interactive_turnstile t=${SystemClock.elapsedRealtime()}")
                        clearToonLivreCookies()
                        reject(IOException("Cloudflare solicitou verificação interativa."))
                        return@interceptRequest createEmptyTextResponse()
                    }

                    // 4. Chapter access timing
                    if (urlStr.contains("/api/reader/chapter/access")) {
                        if (!chapterAccessStarted) {
                            chapterAccessStarted = true
                            Log.d("ToonLivrePerf", "turnstile_done t=${SystemClock.elapsedRealtime()}")
                            Log.d("ToonLivrePerf", "chapter_access_start t=${SystemClock.elapsedRealtime()}")
                        }
                        return@interceptRequest null
                    }

                    // 5. Block CDN ads
                    if (host == CDN_HOST && (urlStr.endsWith(".js") || urlStr.contains(".js?"))) {
                        return@interceptRequest createEmptyTextResponse()
                    }

                    // 6. Block third-party ad networks, trackers
                    if (!isAllowedHost(host) || urlStr.contains("ptag.js") || urlStr.contains("pubadx") ||
                        urlStr.contains("googletagmanager") || urlStr.contains("cloudflareinsights") ||
                        urlStr.contains("whos.amung.us")
                    ) {
                        return@interceptRequest createEmptyTextResponse()
                    }

                    // 7. Fonts: return 404 so browser uses system font without OTS corruption
                    if (urlStr.endsWith(".woff2") || urlStr.endsWith(".ttf") || urlStr.endsWith(".woff")) {
                        return@interceptRequest create404Response()
                    }

                    null
                }

                jsBridge(bridgeName) { payload ->
                    if (payload.contains("interactivePending")) {
                        Log.w("ToonLivrePerf", "detected_interactive_pending t=${SystemClock.elapsedRealtime()}")
                        clearToonLivreCookies()
                        return@jsBridge
                    }
                    if (payload.contains("\"pageCount\"")) {
                        val count = Regex(""""count":\s*(\d+)""").find(payload)?.groupValues?.get(1)?.toIntOrNull()
                        if (count != null && count > 0) {
                            dynamicPageCount.set(count)
                            Log.d("ToonLivrePerf", "captured_dynamic_pageCount count=$count t=${SystemClock.elapsedRealtime()}")
                        }
                    }
                    if (payload.contains("\"first_page\"")) {
                        val urlMatch = Regex(""""imageUrl":\s*"([^"]+)"""").find(payload)?.groupValues?.get(1)
                        val pcMatch = Regex(""""pageCount":\s*(\d+)""").find(payload)?.groupValues?.get(1)?.toIntOrNull()
                        if (pcMatch != null && pcMatch > 0) dynamicPageCount.set(pcMatch)
                        if (urlMatch != null) {
                            val cdnUrl = urlMatch.replace("\\/", "/").toCdnImageUrl()
                            if (cdnUrl != null && tryResolveDerived(cdnUrl)) {
                                return@jsBridge
                            }
                        }
                    }
                    val urls = runCatching { payload.parseAs<List<String>>() }.getOrNull()
                    if (urls != null) {
                        urls.forEach(::collect)
                        val count = expectedCount ?: dynamicPageCount.get().takeIf { it > 0 }
                        if (count != null && imageUrls.size >= count) {
                            Log.d("ToonLivrePerf", "chapter_access_done t=${SystemClock.elapsedRealtime()}")
                            resolve(imageUrls.toPageList(mangaId, chapterNumber))
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
            clearToonLivreCookies()
            throw error
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
        val imageHeaders = headers.newBuilder()
            .set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .set("Sec-Fetch-Dest", "image")
            .set("Sec-Fetch-Mode", "no-cors")
            .set("Sec-Fetch-Site", "cross-site")
            .set("X-Page-Index", page.index.toString())
            .set("Referer", "$baseUrl/")
            .build()
        return GET(page.imageUrl!!, imageHeaders)
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
        private val WEBVIEW_TIMEOUT = 25.seconds
        private const val CDN_HOST = "cdn.toonlivre.net"
        private const val PROXY_HOST = "slightly-free-mayfly.edgecompute.app"
        private val PAGE_NUMBER_REGEX = Regex("""[-_](\d+)\.[^.]+$""")
        private val PAGE_DERIVE_REGEX = Regex("""^(.*[-_])(\d+)(\.[a-zA-Z0-9]+)$""")

        private const val ALTERNATIVE_TITLE_PREF = "alternativeTitlePref"
        private const val MAX_PEEK = 1024L
        private const val NON_JSON_MESSAGE =
            "Resposta não-JSON (Cloudflare ou header desatualizado). Abra a fonte na WebView do app e tente de novo."

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
        host == "challenges.cloudflare.com" ||
        host.endsWith(".cloudflare.com")

    private fun hookFetchScript(bridgeName: String) =
        """
        (() => {
            if (window.__tl_hooked) return;
            window.__tl_hooked = true;
            const origFetch = window.fetch;
            if (!origFetch) return;
            window.fetch = async function(...args) {
                const u = typeof args[0] === 'string' ? args[0] : (args[0] && args[0].url);
                const urlStr = String(u || '');
                const isAccess = urlStr.indexOf('/api/reader/chapter/access') !== -1;
                const isPage = urlStr.indexOf('/api/reader/p/') !== -1;
                if (isAccess) console.log('__tl_access_calling');
                const res = await origFetch.apply(this, args);
                try {
                    if (isAccess) {
                        console.log('__tl_access_code:' + res.status);
                        const clone = res.clone();
                        clone.text().then(body => {
                            try {
                                const data = JSON.parse(body);
                                if (data && data.interactivePending) {
                                    window['$bridgeName'].post(JSON.stringify({ error: 'interactivePending' }));
                                    return;
                                }
                                const pc = data && (data.pageCount || (data.chapter && data.chapter.pageCount));
                                if (pc) {
                                    window['$bridgeName'].post(JSON.stringify({ pageCount: true, count: pc }));
                                }
                                const pages = data && data.chapter && data.chapter.pages;
                                if (Array.isArray(pages) && pages.length > 0) {
                                    window['$bridgeName'].post(JSON.stringify(pages));
                                }
                            } catch(e) {}
                        }).catch(err => {
                            console.log('__tl_access_text_err:' + err);
                        });
                    } else if (isPage) {
                        const clone = res.clone();
                        clone.text().then(body => {
                            try {
                                const data = JSON.parse(body);
                                if (data && data.imageUrl) {
                                    window['$bridgeName'].post(JSON.stringify({
                                        first_page: true,
                                        imageUrl: data.imageUrl,
                                        pageCount: data.pageCount || (data.nextHandle && data.nextHandle.pageCount) || null
                                    }));
                                }
                            } catch(e) {}
                        }).catch(err => {});
                    }
                } catch(e) {
                    console.log('__tl_access_catch:' + e);
                }
                return res;
            };
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
            $bridgeName.post(JSON.stringify(Array.from(urls)));
        })();
        """.trimIndent()

    private fun String.toCdnImageUrl(): String? {
        val url = toHttpUrlOrNull() ?: return null
        val candidate = when (url.host) {
            CDN_HOST -> url
            PROXY_HOST -> url.queryParameter("url")?.toHttpUrlOrNull()
            else -> null
        } ?: return null

        return candidate.takeIf { it.isHttps && it.host == CDN_HOST }?.toString()
    }

    private fun String.isChapterImage(mangaId: String, chapterNumber: String): Boolean {
        val pathSegments = toHttpUrlOrNull()?.pathSegments ?: return false
        return pathSegments.size >= 4 &&
            pathSegments[0] == "obras" &&
            (mangaId.isEmpty() || pathSegments[1] == mangaId) &&
            pathSegments[2] == chapterNumber &&
            pathSegments[3].isNotEmpty()
    }

    private fun Collection<String>.toPageList(mangaId: String, chapterNumber: String): List<Page> = synchronized(this) {
        val sortedUrls = asSequence()
            .mapNotNull { it.toCdnImageUrl() }
            .filter { it.isChapterImage(mangaId, chapterNumber) }
            .distinct()
            .sortedWith(
                compareBy<String>({ it.pageNumber() ?: Int.MAX_VALUE }, { it }),
            )
            .toList()
        sortedUrls.mapIndexed { index, imageUrl -> Page(index, url = imageUrl, imageUrl = imageUrl) }
    }

    private fun String.pageNumber(): Int? = toHttpUrl().pathSegments.lastOrNull()
        ?.let(PAGE_NUMBER_REGEX::find)
        ?.groupValues
        ?.get(1)
        ?.toIntOrNull()
}
