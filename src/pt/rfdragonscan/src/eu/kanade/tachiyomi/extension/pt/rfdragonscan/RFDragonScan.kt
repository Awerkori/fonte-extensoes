package eu.kanade.tachiyomi.extension.pt.rfdragonscan

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.extractNextJsRsc
import kotlinx.serialization.json.JsonObject
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Source
abstract class RFDragonScan :
    HttpSource(),
    ConfigurableSource {

    override val supportsLatest = false

    private var authConnectPreference: Preference? = null
    private var authStatusPreference: Preference? = null
    private var authLogoutPreference: Preference? = null

    override val client = network.client.newBuilder()
        .addInterceptor(::loginInterceptor)
        .addInterceptor(::migrationInterceptor)
        .addInterceptor(::responseDebugInterceptor)
        .rateLimit(2)
        .build()

    private val apiHeaders by lazy {
        headersBuilder().add("Rsc", "1").build()
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    // ============================== Popular ==============================

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/projetos?page=$page", apiHeaders)

    override fun popularMangaParse(response: Response): MangasPage {
        val dto = response.body.string().extractNextJsRsc<ProjectsPageDto>()
            ?: return MangasPage(emptyList(), false)

        val mangas = dto.projects.map { it.toSManga() }

        return MangasPage(mangas, dto.pagination?.hasNextPage == true)
    }

    // ============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException()

    // ============================== Search ===============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/projetos".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())

        if (query.isNotBlank()) {
            url.addQueryParameter("term", query)
        }

        return GET(url.build(), apiHeaders)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // ============================== Details ==============================

    override fun getMangaUrl(manga: SManga): String {
        if (!UUID_REGEX.matches(manga.url)) {
            return "$baseUrl/projetos?term=${manga.url.trim('/').split('/').last()}"
        }
        return baseUrl + manga.url
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        if (!UUID_REGEX.matches(manga.url)) {
            return GET("$baseUrl/migrate${manga.url}", apiHeaders)
        }
        val pathSegments = manga.url.trim('/').split('/').filter { it.isNotEmpty() }
        val mangaId = pathSegments[0]
        val mangaSlug = pathSegments[1]

        val payload = "[\"$mangaId\",\"$mangaSlug\"]"
        val requestBody = payload.toRequestBody("text/plain;charset=UTF-8".toMediaType())

        val stateTree = """["",{"children":[["projectId","$mangaId","d"],{"children":[["linkId","$mangaSlug","d"],{"children":["__PAGE__",{},null,null]},null,null]}]},null,null,true]"""

        return POST(
            baseUrl + manga.url,
            actionHeaders("60e89cb5963d6bb1b61383872fbfb4cc2726925dd8", baseUrl + manga.url, stateTree),
            requestBody,
        ).also { Log.d(DEBUG_TAG, "stage=DETAILS_REQUEST url=${it.url} method=${it.method} nextAction=${it.header("next-action")} payloadLength=${payload.length}") }
    }

    override fun mangaDetailsParse(response: Response): SManga {
        debugResponse("DETAILS", response)
        val dto = response.body.string().extractNextJsRsc<MangaDetailsDto> {
            it is JsonObject && "synopsis" in it && "title" in it
        } ?: throw IOException("Manga details not found")

        return dto.toSManga()
    }

    // ============================= Chapters ==============================

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    override fun chapterListRequest(manga: SManga): Request {
        if (!UUID_REGEX.matches(manga.url)) {
            return GET("$baseUrl/migrate-chapters${manga.url}", apiHeaders)
        }
        val pathSegments = manga.url.trim('/').split('/').filter { it.isNotEmpty() }
        val mangaId = pathSegments[0]
        val mangaSlug = pathSegments[1]

        val payload = "[\"$mangaId\",\"$mangaSlug\"]"
        val requestBody = payload.toRequestBody("text/plain;charset=UTF-8".toMediaType())

        val stateTree = """["",{"children":[["projectId","$mangaId","d"],{"children":[["linkId","$mangaSlug","d"],{"children":["__PAGE__",{},null,null]},null,null]}]},null,null,true]"""

        return POST(
            baseUrl + manga.url,
            actionHeaders("606c13e60309ce062fade63ac2f1cc68bbc5dc25f4", baseUrl + manga.url, stateTree),
            requestBody,
        ).also { Log.d(DEBUG_TAG, "stage=CHAPTERS_REQUEST url=${it.url} method=${it.method} nextAction=${it.header("next-action")} payloadLength=${payload.length}") }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        debugResponse("CHAPTERS", response)
        val seasonList = response.body.string().extractNextJsRsc<SeasonListDto> {
            it is JsonObject && "groups" in it
        } ?: throw IOException("Chapters not found")

        val pathSegments = response.request.url.pathSegments.filter { it.isNotEmpty() }
        val mangaId = pathSegments[pathSegments.size - 2]
        val mangaSlug = pathSegments.last()

        val chapters = mutableListOf<SChapter>()

        seasonList.groups?.forEach { group ->
            group.chapters?.forEach { ch ->
                if (ch.isUpcoming == true || ch.hasRestriction == true) {
                    return@forEach
                }
                chapters.add(ch.toSChapter(mangaId, mangaSlug, dateFormat))
            }
        }

        return chapters.sortedByDescending {
            it.name.substringAfter("Capítulo ").toFloatOrNull() ?: 0f
        }
    }

    // =============================== Pages ===============================

    override fun pageListRequest(chapter: SChapter): Request {
        val pathSegments = chapter.url.trim('/').split('/').filter { it.isNotEmpty() }
        val mangaId = pathSegments[0]
        val mangaSlug = pathSegments[1]
        val chapterTitle = pathSegments[3]

        val payload = "[\"$mangaId\",\"$chapterTitle\"]"
        val requestBody = payload.toRequestBody("text/plain;charset=UTF-8".toMediaType())

        val stateTree = """["",{"children":[["projectId","$mangaId","d"],{"children":[["linkId","$mangaSlug","d"],{"children":["capitulo",{"children":[["chapterId","$chapterTitle","d"],{"children":["__PAGE__",{},null,null]}]}]}]}]},null,null,true]"""

        return POST(
            baseUrl + chapter.url,
            actionHeaders("6062e8559136ee33cc337e5520fb09950c3dced65e", baseUrl + chapter.url, stateTree),
            requestBody,
        ).also { Log.d(DEBUG_TAG, "stage=PAGES_REQUEST chapterUrl=${chapter.url} url=${it.url} method=${it.method} nextAction=${it.header("next-action")} payloadLength=${payload.length}") }
    }

    override fun pageListParse(response: Response): List<Page> {
        debugResponse("READER", response)
        val dto = response.body.string().extractNextJsRsc<PagesDto> {
            it is JsonObject && "pages" in it
        } ?: throw IOException("Pages not found")

        return dto.toPages()
    }

    private fun debugResponse(stage: String, response: Response) {
        val redirects = generateSequence(response.priorResponse) { it.priorResponse }.count()
        val request = response.request
        val action = request.header("next-action") ?: request.header("Next-Action") ?: "<none>"
        val cookies = client.cookieJar.loadForRequest(request.url).map { it.name }.distinct().sorted()
        Log.d(
            DEBUG_TAG,
            "stage=$stage url=${request.url} method=${request.method} nextAction=$action " +
                "status=${response.code} contentType=${response.header("Content-Type")} bodyLength=${response.peekBody(256 * 1024).bytes().size} redirects=$redirects cookieNames=$cookies",
        )
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    // ============================== Filters ==============================

    // ============================= Utilities =============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val connect = createPreference(screen.context).apply {
            key = "rf_dragon_connect"
            title = if (hasAccessToken()) "Abrir RF Dragon Scan" else "Conectar à RF Dragon Scan"
            summary = "Abre o site oficial da RF Dragon Scan."
            setOnPreferenceClickListener {
                showLoginWebView(screen.context) { updateAuthPreferences(screen) }
                true
            }
        }
        val status = createPreference(screen.context).apply {
            key = "rf_dragon_status"
            title = "Sessão da RF Dragon Scan"
            setPreferenceSelectable(this, false)
        }
        val logout = createPreference(screen.context).apply {
            key = "rf_dragon_logout"
            title = "Sair da conta"
            summary = "Remove somente a sessão da RF Dragon Scan."
            setOnPreferenceClickListener {
                AlertDialog.Builder(screen.context).setTitle("Sair da conta?")
                    .setMessage("Isso removerá somente a sessão da RF Dragon Scan.")
                    .setPositiveButton("Sair") { _, _ ->
                        clearSession()
                        updateAuthPreferences(screen)
                    }
                    .setNegativeButton("Cancelar", null).show()
                true
            }
        }
        screen.addPreference(connect)
        screen.addPreference(status)
        screen.addPreference(logout)
        authConnectPreference = connect
        authStatusPreference = status
        authLogoutPreference = logout
        fun refresh() {
            val connected = hasAccessToken()
            connect.title = if (connected) "Abrir RF Dragon Scan" else "Conectar à RF Dragon Scan"
            status.summary = if (connected) "Conectado" else "Não conectado"
            setPreferenceVisible(logout, connected)
        }
        refresh()
    }

    private fun updateAuthPreferences(screen: PreferenceScreen) {
        val connected = hasAccessToken()
        authConnectPreference?.title = if (connected) "Abrir RF Dragon Scan" else "Conectar à RF Dragon Scan"
        authStatusPreference?.summary = if (connected) "Conectado" else "Não conectado"
        authLogoutPreference?.let { setPreferenceVisible(it, connected) }
    }

    private fun createPreference(context: android.content.Context): Preference = runCatching {
        Preference::class.java.getConstructor(android.content.Context::class.java).newInstance(context)
    }.getOrElse { Preference() }

    private fun setPreferenceVisible(preference: Preference, visible: Boolean) {
        runCatching { preference::class.java.methods.firstOrNull { it.name == "setVisible" }?.invoke(preference, visible) }
    }

    private fun setPreferenceSelectable(preference: Preference, selectable: Boolean) {
        runCatching { preference::class.java.methods.firstOrNull { it.name == "setSelectable" }?.invoke(preference, selectable) }
    }

    private fun webCookies(): List<Cookie> = CookieManager.getInstance().getCookie(baseUrl).orEmpty()
        .split(';').mapNotNull { Cookie.parse(baseUrl.toHttpUrl(), "${it.trim()}; Domain=${baseUrl.toHttpUrl().host}; Path=/") }

    private fun hasAccessToken(): Boolean {
        val web = webCookies().any { it.name == "access_token" && it.value.isNotEmpty() }
        val client = client.cookieJar.loadForRequest(baseUrl.toHttpUrl()).any { it.name == "access_token" && it.value.isNotEmpty() }
        return web && client
    }

    private fun syncSession(): Boolean {
        val cookies = webCookies()
        client.cookieJar.saveFromResponse(baseUrl.toHttpUrl(), cookies)
        CookieManager.getInstance().flush()
        val valid = hasAccessToken()
        Log.d(DEBUG_TAG, "stage=AUTH_SYNC webCookieNames=${cookies.map { it.name }.distinct().sorted()} clientCookieNames=${client.cookieJar.loadForRequest(baseUrl.toHttpUrl()).map { it.name }.distinct().sorted()} valid=$valid")
        return valid
    }

    private fun clearSession() {
        CookieManager.getInstance().setCookie(baseUrl, "access_token=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/")
        CookieManager.getInstance().setCookie(baseUrl, "access_token=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Domain=rfdragonscan.net; Path=/")
        CookieManager.getInstance().flush()
        val jar = client.cookieJar
        val clearMethod = jar.javaClass.methods.firstOrNull { it.name == "removeAll" || (it.name == "clear" && it.parameterTypes.isEmpty()) }
        runCatching { clearMethod?.invoke(jar) }
        val webHasToken = webCookies().any { it.name == "access_token" && it.value.isNotEmpty() }
        val clientHasToken = client.cookieJar.loadForRequest(baseUrl.toHttpUrl()).any { it.name == "access_token" && it.value.isNotEmpty() }
        Log.d(DEBUG_TAG, "stage=AUTH_LOGOUT_RESULT webHasAccessToken=$webHasToken clientHasAccessToken=$clientHasToken statusUpdated=${authStatusPreference != null}")
    }

    private fun responseDebugInterceptor(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val path = response.request.url.encodedPath
        if (response.request.method == "POST" && response.request.url.host == baseUrl.toHttpUrl().host) {
            val stage = if (path.contains("/capitulo/")) "PAGES_RESPONSE" else "WORK_RESPONSE"
            Log.d(
                DEBUG_TAG,
                "stage=$stage status=${response.code} contentType=${response.header("Content-Type")} " +
                    "bodyLength=${response.peekBody(256 * 1024).bytes().size} finalUrl=${response.request.url}",
            )
        }
        return response
    }

    private fun showLoginWebView(context: android.content.Context, onValidated: () -> Unit) {
        val dialog = Dialog(context)
        val webView = WebView(context)
        val handler = Handler(Looper.getMainLooper())
        var handled = false
        lateinit var authPoll: Runnable
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.setSupportMultipleWindows(false)
        webView.settings.javaScriptCanOpenWindowsAutomatically = false
        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean = false
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (request?.method == "POST" && request.url.host == baseUrl.toHttpUrl().host) {
                    Log.d(DEBUG_TAG, "stage=WEBVIEW_ACTION url=${request.url} method=POST nextAction=${request.requestHeaders["Next-Action"] ?: request.requestHeaders["next-action"] ?: "<none>"} contentType=${request.requestHeaders["Content-Type"] ?: "<none>"} accept=${request.requestHeaders["Accept"] ?: "<none>"}")
                }
                return if (isAuthAdHost(request?.url?.host.orEmpty())) {
                    WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(ByteArray(0)))
                } else {
                    null
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = isAuthAdHost(request?.url?.host.orEmpty())

            override fun onPageFinished(view: WebView?, url: String?) {
                Log.d(DEBUG_TAG, "stage=AUTH_WEBVIEW_URL currentUrl=$url")
                view?.evaluateJavascript(AUTH_SCRIPT, null)
            }
        }
        dialog.setContentView(webView)
        authPoll = object : Runnable {
            override fun run() {
                if (handled || !webView.isAttachedToWindow) return
                val webHasToken = webCookies().any { it.name == "access_token" && it.value.isNotEmpty() }
                if (webHasToken && syncSession()) {
                    handled = true
                    handler.removeCallbacks(this)
                    CookieManager.getInstance().flush()
                    val clientHasToken = client.cookieJar.loadForRequest(baseUrl.toHttpUrl()).any { it.name == "access_token" && it.value.isNotEmpty() }
                    Log.d(DEBUG_TAG, "stage=AUTH_POLL webHasAccessToken=$webHasToken clientHasAccessToken=$clientHasToken")
                    onValidated()
                    val hostActivity = webView.context.findActivity()
                    Log.d(DEBUG_TAG, "stage=AUTH_CLOSE_TARGET activityClass=${hostActivity?.javaClass?.name ?: "<none>"} closeTarget=Dialog")
                    dialog.dismiss()
                    Log.d(DEBUG_TAG, "stage=AUTH_CLOSE activityFound=${hostActivity != null} finishCalled=false dialogDismissed=true")
                    return
                }
                handler.postDelayed(this, 400L)
            }
        }
        dialog.setOnDismissListener {
            handler.removeCallbacks(authPoll)
            webView.destroy()
        }
        dialog.show()
        webView.loadUrl("$baseUrl/login")
        handler.post(authPoll)
    }

    private fun isAuthAdHost(host: String): Boolean = host == "platform.pubadx.one" ||
        host.endsWith(".pubadx.one") ||
        host == "googletagmanager.com" ||
        host.endsWith(".googletagmanager.com")

    private fun Context.findActivity(): Activity? {
        var current: Context? = this
        while (current is ContextWrapper) {
            if (current is Activity) return current
            val next = current.baseContext
            if (next === current) break
            current = next
        }
        return current as? Activity
    }

    private var actionIdCache: String? = null

    private fun getActionId(): String {
        actionIdCache?.let { return it }

        val html = network.client.newCall(GET("$baseUrl/login", headers)).execute().use { it.body.string() }

        ACTION_ID_HTML_REGEX.find(html)?.let {
            val id = it.groupValues[1]
            actionIdCache = id
            return id
        }

        val chunkUrls = CHUNK_URL_REGEX.findAll(html)
            .map { it.groupValues[1] }
            .toList()

        for (url in chunkUrls) {
            try {
                network.client.newCall(GET(baseUrl + url, headers)).execute().use { res ->
                    val js = res.body.string()
                    if (js.contains("\"login\"")) {
                        val idMatch = ACTION_ID_JS_REGEX.find(js)
                        if (idMatch != null) {
                            val id = idMatch.groupValues[1]
                            actionIdCache = id
                            return id
                        }
                    }
                }
            } catch (_: Exception) {
                // Ignore and continue searching
            }
        }

        return "600165150b15a3870c9e076c863daec8d24748e458"
    }

    private fun actionHeaders(actionId: String, referer: String, stateTree: String): Headers {
        val encodedStateTree = java.net.URLEncoder.encode(stateTree, "UTF-8")
        return headersBuilder()
            .add("next-action", actionId)
            .add("next-router-state-tree", encodedStateTree)
            .add("Accept", "text/x-component")
            .add("Origin", baseUrl)
            .add("Referer", referer)
            .build()
    }

    private fun loginInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val cookies = client.cookieJar.loadForRequest(baseUrl.toHttpUrl())
        Log.d(DEBUG_TAG, "stage=AUTH request=${request.url} cookieNames=${cookies.map { it.name }.distinct().sorted()}")
        val isLoggedIn = cookies.any { it.name == "access_token" && it.value.isNotEmpty() }
        val public = request.url.pathSegments.firstOrNull() in setOf(null, "projetos", "login")
        if (!isLoggedIn && !public) throw IOException("🔐 Login necessário")
        val response = chain.proceed(request)
        if (response.code == 401 || response.code == 403) clearSession()
        return response
    }

    private fun migrationInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val firstSegment = request.url.pathSegments.firstOrNull()

        if (firstSegment == "migrate" || firstSegment == "migrate-chapters") {
            val oldPath = request.url.encodedPath
                .removePrefix("/migrate-chapters")
                .removePrefix("/migrate")
            val slug = oldPath.trim('/').split('/').last { it.isNotEmpty() }

            val searchUrl = "$baseUrl/projetos?term=$slug".toHttpUrl()
            val searchReq = GET(searchUrl, apiHeaders)
            val searchRes = chain.proceed(searchReq)

            val newUrlPath = searchRes.use { res ->
                val dto = res.body.string().extractNextJsRsc<ProjectsPageDto>()
                val project = dto?.projects?.firstOrNull { it.link == slug || it.title.contains(slug, ignoreCase = true) }
                    ?: throw IOException("Manga not found during migration")

                "/${project.id}/${project.link}"
            }

            val pathSegments = newUrlPath.trim('/').split('/')
            val mangaId = pathSegments[0]
            val mangaSlug = pathSegments[1]

            val actionId = if (firstSegment == "migrate") {
                "60e89cb5963d6bb1b61383872fbfb4cc2726925dd8"
            } else {
                "606c13e60309ce062fade63ac2f1cc68bbc5dc25f4"
            }

            val payload = "[\"$mangaId\",\"$mangaSlug\"]"
            val requestBody = payload.toRequestBody("text/plain;charset=UTF-8".toMediaType())

            val stateTree = """["",{"children":[["projectId","$mangaId","d"],{"children":[["linkId","$mangaSlug","d"],{"children":["__PAGE__",{},null,null]},null,null]}]},null,null,true]"""

            val actionRequest = POST(
                "$baseUrl$newUrlPath",
                actionHeaders(actionId, "$baseUrl$newUrlPath", stateTree),
                requestBody,
            )

            return chain.proceed(actionRequest)
        }

        return chain.proceed(request)
    }

    companion object {
        private const val DEBUG_TAG = "RF_DRAGON_DEBUG"

        private val UUID_REGEX = Regex("^/[0-9a-fA-F\\-]{36}/.*")

        private val AUTH_SCRIPT = """
            (() => {
              if (window.__rfDragonAuthClean) return;
              window.__rfDragonAuthClean = true;
              const clean = () => document.querySelectorAll(
                'iframe[src*="pubadx.one"], [src*="platform.pubadx.one"], [class*="pubadx"], [id*="pubadx"]'
              ).forEach((element) => element.remove());
              clean();
              if (document.documentElement) new MutationObserver(clean).observe(document.documentElement, {childList:true, subtree:true});
              window.open = (url) => {
                if (!url) return null;
                try { location.href = new URL(url, location.href).href; return window; } catch (_) { return null; }
              };
            })();
        """.trimIndent()

        private val ACTION_ID_HTML_REGEX = Regex("""name="\x24ACTION_ID_([a-f0-9]{40})"""")
        private val CHUNK_URL_REGEX = Regex("""src="(/_next/static/chunks/[^"]+\.js)"""")
        private val ACTION_ID_JS_REGEX = Regex("""createServerReference\("([a-f0-9]{40})",.*?,"login"\)""")
    }
}
