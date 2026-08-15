package eu.kanade.tachiyomi.extension.pt.inkscan

import android.app.Dialog
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
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
import keiyoushi.utils.getLocalStorage
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

@Source
abstract class InkScan :
    HttpSource(),
    ConfigurableSource {

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()
    private val auth = InkScanAuth()

    private val apiUrl = "https://delicate-hill-05c1inkscan.inkscann.workers.dev"
    private val apiHost = apiUrl.toHttpUrl().host

    init {
        debug("source_created version=1.4.3 baseUrl=$baseUrl workerUrl=$apiUrl")
        prefDebug("source_created class=${this::class.java.name}")
        prefDebug("source_id=$id")
        prefDebug("configurable_source=${this is ConfigurableSource}")
    }

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor { chain -> auth.intercept(chain) }
        .rateLimit(3, 1.seconds) { it.host == apiHost }
        .build()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        prefDebug("setup_entered=true")
        prefDebug("source_class=${this::class.java.name}")
        prefDebug("source_id=$id")
        prefDebug("screen_class=${screen::class.java.name}")
        prefDebug("context_class=${screen.context::class.java.name}")
        var addedCount = 0
        prefDebug("initial_count=unavailable_in_extension_api")
        debug("settings_open session=${auth.hasSession()}")
        preferences.edit()
            .remove(LEGACY_PREF_EMAIL)
            .remove(LEGACY_PREF_PASSWORD)
            .apply()

        lateinit var actionPref: Preference
        lateinit var sessionStatus: Preference
        lateinit var clearSession: Preference

        fun updateUiState() {
            val loggedIn = auth.hasSession()
            if (loggedIn) {
                actionPref.title = "Abrir Ink Scan"
                actionPref.summary = "Abre o site da Ink Scan."
                sessionStatus.summary = "Conectado"
                setPreferenceVisible(clearSession, true)
            } else {
                actionPref.title = "Entrar na Ink Scan"
                actionPref.summary = "Abre o login oficial da Ink Scan."
                sessionStatus.summary = "Não conectado"
                setPreferenceVisible(clearSession, false)
            }
        }

        actionPref = createPreference(screen.context).apply {
            key = PREF_HOW_TO_LOGIN
            title = if (auth.hasSession()) "Abrir Ink Scan" else "Entrar na Ink Scan"
            summary = if (auth.hasSession()) "Abre o site da Ink Scan." else "Abre o login oficial da Ink Scan."
            setOnPreferenceClickListener {
                loginDebug("preference_clicked")
                loginDebug("preference_click_consumed=true")
                prefDebug("login_preference_clicked")
                showLoginWebView(screen.context) {
                    updateUiState()
                }
                true
            }
        }
        setPreferenceSelectable(actionPref, true)
        prefDebugSnapshot("HOW_TO_LOGIN", actionPref, "before_add")
        val actionPrefAdded = screen.addPreference(actionPref)
        if (actionPrefAdded) addedCount++
        prefDebug("added=HOW_TO_LOGIN count=$addedCount result=$actionPrefAdded")
        prefDebugSnapshot("HOW_TO_LOGIN", actionPref, "after_add")

        sessionStatus = createPreference(screen.context).apply {
            key = PREF_SESSION_STATUS
            title = "Sessão da Ink Scan"
            summary = if (auth.hasSession()) "Conectado" else "Não conectado"
            setOnPreferenceClickListener { true }
        }
        setPreferenceSelectable(sessionStatus, false)
        prefDebugSnapshot("SESSION_STATUS", sessionStatus, "before_add")
        val sessionStatusAdded = screen.addPreference(sessionStatus)
        if (sessionStatusAdded) addedCount++
        prefDebug("added=SESSION_STATUS count=$addedCount result=$sessionStatusAdded")
        prefDebugSnapshot("SESSION_STATUS", sessionStatus, "after_add")

        clearSession = createPreference(screen.context).apply {
            key = PREF_CLEAR_SESSION
            title = "Sair da conta"
            summary = "Remove a sessão salva da Ink Scan neste aplicativo."
            setOnPreferenceClickListener {
                showLogoutConfirmation(screen.context) {
                    preferences.edit()
                        .remove(PREF_ACCESS_TOKEN)
                        .remove(PREF_REFRESH_TOKEN)
                        .remove(PREF_TOKEN_EXPIRES)
                        .apply()
                    debug("auth session_cleared")
                    updateUiState()
                }
                true
            }
        }
        setPreferenceSelectable(clearSession, true)
        setPreferenceVisible(clearSession, auth.hasSession())
        prefDebugSnapshot("CLEAR_SESSION", clearSession, "before_add")
        val clearSessionAdded = screen.addPreference(clearSession)
        if (clearSessionAdded) addedCount++
        prefDebug("added=CLEAR_SESSION count=$addedCount result=$clearSessionAdded")
        prefDebugSnapshot("CLEAR_SESSION", clearSession, "after_add")
        updateUiState()
        val foundHowToLogin = screen.findPreferenceDebugObject(PREF_HOW_TO_LOGIN)
        val foundSessionStatus = screen.findPreferenceDebugObject(PREF_SESSION_STATUS)
        val foundClearSession = screen.findPreferenceDebugObject(PREF_CLEAR_SESSION)
        prefDebug("find_how_to_login=${foundHowToLogin != null} found_identity=${foundHowToLogin?.let(System::identityHashCode)} same_instance=${foundHowToLogin === actionPref}")
        prefDebug("find_session_status=${foundSessionStatus != null} found_identity=${foundSessionStatus?.let(System::identityHashCode)} same_instance=${foundSessionStatus === sessionStatus}")
        prefDebug("find_clear_session=${foundClearSession != null} found_identity=${foundClearSession?.let(System::identityHashCode)} same_instance=${foundClearSession === clearSession}")
        prefDebug("setup_finished count=$addedCount")
    }

    private fun createPreference(context: android.content.Context): Preference = runCatching {
        Preference::class.java.getConstructor(android.content.Context::class.java).newInstance(context)
    }.getOrElse { Preference() }

    private fun setPreferenceVisible(preference: Preference, visible: Boolean) {
        runCatching {
            preference::class.java.methods.firstOrNull { method ->
                method.name == "setVisible" && method.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType))
            }?.invoke(preference, visible)
        }.onFailure {
            prefDebug("set_visible_failed class=${preference::class.java.name}")
        }
    }

    private fun showLogoutConfirmation(context: android.content.Context, onConfirmed: () -> Unit) {
        android.app.AlertDialog.Builder(context)
            .setTitle("Sair da Ink Scan?")
            .setMessage("Isso removerá a sessão salva neste aplicativo.")
            .setPositiveButton("Sair") { _, _ ->
                onConfirmed()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showLoginWebView(context: android.content.Context, onSessionImported: () -> Unit) {
        loginDebug("webview_opened")
        loginDebug("login_ui_opened")
        val dialog = Dialog(context)
        val webView = WebView(context)
        loginDebug("webview_created")
        val handler = Handler(Looper.getMainLooper())
        var finished = false
        var destroyed = false

        fun stopPolling() {
            handler.removeCallbacksAndMessages(null)
        }

        fun closeWebView(autoClosed: Boolean = false) {
            if (destroyed) return
            destroyed = true
            stopPolling()
            webView.stopLoading()
            if (dialog.isShowing) {
                dialog.dismiss()
            }
            if (autoClosed) {
                loginDebug("webview_auto_closed")
            }
            loginDebug("login_ui_closed")
            prefDebug("login_webview_closed")
            webView.destroy()
        }

        val rootLayout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val density = context.resources.displayMetrics.density
        val topBar = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val padH = (16 * density).toInt()
            val padV = (12 * density).toInt()
            setPadding(padH, padV, padH, padV)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        val backButton = android.widget.TextView(context).apply {
            text = "←"
            textSize = 22f
            val padR = (16 * density).toInt()
            setPadding(0, 0, padR, 0)
            setOnClickListener {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    closeWebView()
                }
            }
        }

        val titleView = android.widget.TextView(context).apply {
            text = "Ink Scan"
            textSize = 17f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val closeButton = android.widget.TextView(context).apply {
            text = "✕"
            textSize = 20f
            val padL = (16 * density).toInt()
            setPadding(padL, 0, 0, 0)
            setOnClickListener {
                closeWebView()
            }
        }

        topBar.addView(backButton)
        topBar.addView(titleView)
        topBar.addView(closeButton)

        val progressBar = android.widget.ProgressBar(
            context,
            null,
            android.R.attr.progressBarStyleHorizontal,
        ).apply {
            isIndeterminate = true
            layoutParams = android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (3 * density).toInt().coerceAtLeast(1),
            )
        }

        rootLayout.addView(topBar)
        rootLayout.addView(progressBar)
        rootLayout.addView(
            webView,
            android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        lateinit var checkSession: () -> Unit
        checkSession = {
            if (!finished && !destroyed && dialog.isShowing) {
                loginDebug("session_check")
                val js = """
                    (function() {
                        try {
                            var d = localStorage.getItem('sb-delicate-hill-05c1inkscan-auth-token');
                            if (d && d !== 'null') return 'delicate:::' + d;
                            var s = localStorage.getItem('sb-sjybfvyoznmtxmjhycoj-auth-token');
                            if (s && s !== 'null') return 'sjy:::' + s;
                        } catch (e) {}
                        return null;
                    })()
                """.trimIndent()
                webView.evaluateJavascript(js) { result ->
                    if (finished || destroyed) return@evaluateJavascript
                    val payload = runCatching { result.parseAs<String?>() }.getOrNull()
                    if (payload.isNullOrBlank() || payload == "null") {
                        loginDebug("session_found=false")
                        if (!finished && !destroyed && dialog.isShowing) {
                            handler.postDelayed({ checkSession() }, 1_000L)
                        }
                        return@evaluateJavascript
                    }
                    loginDebug("session_found")
                    loginDebug("session_found=true")
                    val keyLabel = payload.substringBefore(":::")
                    val jsonContent = payload.substringAfter(":::")
                    val access = auth.saveStorageSession(jsonContent, keyLabel)
                    val parseSuccess = access != null
                    loginDebug("session_parse_success=$parseSuccess")
                    loginDebug("session_persisted=$parseSuccess")
                    if (parseSuccess) {
                        finished = true
                        loginDebug("session_import_success")
                        onSessionImported()
                        closeWebView(autoClosed = true)
                    } else {
                        if (!finished && !destroyed && dialog.isShowing) {
                            handler.postDelayed({ checkSession() }, 1_000L)
                        }
                    }
                }
            }
        }

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                progressBar.visibility = if (newProgress >= 100) android.view.View.GONE else android.view.View.VISIBLE
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = android.view.View.GONE
                loginDebug("page_loaded")
                stopPolling()
                checkSession()
            }
        }

        dialog.setContentView(rootLayout)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.setOnShowListener {
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
                if (webView.canGoBack()) {
                    webView.goBack()
                    return@setOnKeyListener true
                }
            }
            false
        }
        dialog.setOnDismissListener {
            if (!destroyed) {
                destroyed = true
                stopPolling()
                webView.stopLoading()
                loginDebug("login_ui_closed")
                prefDebug("login_webview_closed")
                webView.destroy()
            }
        }
        dialog.show()
        webView.loadUrl(baseUrl)
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Accept", "application/json, text/plain, */*")
        .set("Origin", baseUrl)
        .set("Referer", "$baseUrl/")

    private val restHeaders: Headers by lazy {
        apiHeadersBuilder()
            .set("accept-profile", "public")
            .set("Prefer", "count=exact")
            .build()
    }

    private val rpcHeaders: Headers by lazy {
        apiHeadersBuilder()
            .set("content-profile", "public")
            .build()
    }

    private val functionHeaders: Headers by lazy {
        apiHeadersBuilder().build()
    }

    // ============================= Popular ================================

    override fun fetchPopularManga(page: Int): Observable<MangasPage> = if (!auth.sessionAvailable()) {
        debug("LOGIN_CARD operation=POPULAR")
        Observable.just(loginCardPage())
    } else {
        super.fetchPopularManga(page)
    }

    override fun popularMangaRequest(page: Int): Request = worksRequest(page, POPULAR_SORT, FilterList())

    override fun popularMangaParse(response: Response): MangasPage = response.toWorksPage()

    // ============================= Latest =================================

    override fun latestUpdatesRequest(page: Int): Request = worksRequest(page, LATEST_SORT, FilterList())

    override fun latestUpdatesParse(response: Response): MangasPage = response.toWorksPage()

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> {
        if (!auth.sessionAvailable()) {
            debug("LOGIN_CARD operation=LATEST")
            return Observable.just(loginCardPage())
        }
        return Observable.fromCallable {
            client.newCall(latestChaptersRequest()).execute().use { response ->
                debug("response operation=LATEST http=${response.code}")
                if (!response.isSuccessful) {
                    throw IOException(if (response.code == 401) loginMessage() else "Falha ao carregar atualizações: HTTP ${response.code}")
                }
                val latest = response.parseAs<List<LatestChapterDto>>()
                    .asSequence()
                    .filter { it.work != null }
                    .distinctBy { it.work!!.id() }
                    .map { it.work!!.toSManga() }
                    .toList()
                debug("response operation=LATEST items=${latest.size}")
                if (latest.isEmpty() && !auth.hasSession()) throw IOException(loginMessage())
                val offset = (page - 1) * PAGE_SIZE
                MangasPage(latest.drop(offset).take(PAGE_SIZE), latest.size > offset + PAGE_SIZE)
            }
        }
    }

    // ============================= Search =================================

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        if (!auth.sessionAvailable()) {
            debug("LOGIN_CARD operation=SEARCH")
            return Observable.just(loginCardPage())
        }
        if (query.isBlank()) {
            return super.fetchSearchManga(page, query, filters)
        }

        return Observable.fromCallable {
            val ids = client.newCall(searchIdsRequest(query)).execute().use { response ->
                if (response.code == 401) throw IOException(loginMessage())
                if (!response.isSuccessful) throw IOException("Falha na busca: HTTP ${response.code}")
                response.parseAs<List<SearchResultDto>>().also {
                    debug("response operation=SEARCH http=${response.code} items=${it.size}")
                    if (it.isEmpty() && !auth.hasSession()) throw IOException(loginMessage())
                }.map { it.id() }
            }

            if (ids.isEmpty()) {
                return@fromCallable MangasPage(emptyList(), false)
            }

            val works = client.newCall(worksByIdsRequest(ids, filters)).execute().use { response ->
                if (response.code == 401) throw IOException(loginMessage())
                if (!response.isSuccessful) throw IOException("Falha ao carregar resultados: HTTP ${response.code}")
                response.parseAs<List<WorkDto>>().also { debug("response operation=SEARCH results_items=${it.size}") }
            }

            val orderedWorks = ids.mapNotNull { id -> works.firstOrNull { it.id() == id } }
            val offset = (page - 1) * PAGE_SIZE
            val pageItems = orderedWorks.drop(offset).take(PAGE_SIZE)

            MangasPage(
                pageItems.map { it.toSManga() },
                orderedWorks.size > offset + PAGE_SIZE,
            )
        }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val sort = filters.sortValue().ifEmpty { LATEST_SORT }
        return worksRequest(page, sort, filters)
    }

    override fun searchMangaParse(response: Response): MangasPage = response.toWorksPage()

    // ============================= Details ================================

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = if (manga.url == LOGIN_REQUIRED_URL) {
        Observable.just(loginCardManga())
    } else {
        super.fetchMangaDetails(manga)
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        auth.ensureSession()
        val url = "$apiUrl/rest/v1/obras".toHttpUrl().newBuilder()
            .addQueryParameter("select", DETAILS_SELECT)
            .addQueryParameter("id", "eq.${manga.workId()}")
            .build()

        return GET(url, restHeaders)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        debug("response operation=DETAILS http=${response.code}")
        if (response.code == 401) throw IOException(loginMessage())
        if (!response.isSuccessful) throw IOException("Falha ao carregar detalhes: HTTP ${response.code}")
        if (!auth.hasSession()) throw IOException(loginMessage())
        val works = try {
            response.parseAs<List<WorkDto>>()
        } catch (error: Throwable) {
            debugError("DETAILS", error)
            throw error
        }
        debug("response operation=DETAILS body_empty=${works.isEmpty()} parse_success=true")
        return works.firstOrNull()?.toSManga(initialized = true)
            ?: throw IOException("A resposta da Ink Scan não contém os detalhes da obra.")
    }

    // ============================= Chapters ===============================

    override fun chapterListRequest(manga: SManga): Request {
        auth.ensureSession()
        val url = "$apiUrl/rest/v1/capitulos".toHttpUrl().newBuilder()
            .addQueryParameter("select", CHAPTERS_SELECT)
            .addQueryParameter("obra_id", "eq.${manga.workId()}")
            .addQueryParameter("order", "numero.asc")
            .build()

        return GET(url, restHeaders)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        debug("response operation=CHAPTERS http=${response.code}")
        if (response.code == 401) throw IOException(loginMessage())
        if (!response.isSuccessful) throw IOException("Falha ao carregar capítulos: HTTP ${response.code}")
        val workId = response.request.url.queryParameter("obra_id")?.removePrefix("eq.").orEmpty()
        if (!auth.hasSession()) throw IOException(loginMessage())
        val chapters = try {
            response.parseAs<List<ChapterDto>>()
        } catch (error: Throwable) {
            debugError("CHAPTERS", error)
            throw error
        }
        debug("response operation=CHAPTERS items=${chapters.size}")
        return chapters
            .map { it.toSChapter(workId) }
            .sortedWith(compareByDescending<SChapter> { it.chapter_number }.thenByDescending { it.date_upload })
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        if (manga.url == LOGIN_REQUIRED_URL) return Observable.just(emptyList())

        return Observable.fromCallable {
            val chapters = buildList {
                var offset = 0
                while (true) {
                    val response = client.newCall(chapterListRequest(manga, offset)).execute()
                    val page = response.use {
                        debug("response operation=CHAPTERS http=${it.code}")
                        if (!it.isSuccessful) {
                            throw IOException(if (it.code == 401) loginMessage() else "Falha ao carregar capítulos: HTTP ${it.code}")
                        }
                        it.parseAs<List<ChapterDto>>().also { chapters -> debug("response operation=CHAPTERS items=${chapters.size}") }
                    }
                    if (page.isEmpty()) break
                    addAll(page.map { it.toSChapter(manga.workId()) })
                    if (page.size < CHAPTER_PAGE_SIZE) break
                    offset += CHAPTER_PAGE_SIZE
                }
            }
            chapters
                .distinctBy { it.url }
                .sortedWith(compareByDescending<SChapter> { it.chapter_number }.thenByDescending { it.date_upload })
        }
    }

    // ============================= Pages ==================================

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = Observable.fromCallable {
        val chapterUrl = "$baseUrl${chapter.url}".toHttpUrl()
        val workId = chapterUrl.pathSegments[1]
        val chapterId = chapterUrl.queryParameter("id") ?: throw IOException("ID do capitulo ausente")

        val folder = client.newCall(folderRequest(workId)).execute().use { response ->
            debug("response operation=READER http=${response.code}")
            if (!response.isSuccessful) {
                throw IOException("Falha ao carregar a obra: HTTP ${response.code}")
            }
            response.parseAs<List<FolderDto>>().first()
        }

        client.newCall(chapterPagesRequest(chapterId)).execute().use { response ->
            debug("response operation=READER http=${response.code}")
            if (!response.isSuccessful) {
                throw IOException("Falha ao carregar as páginas: HTTP ${response.code}")
            }
            val pages = response.parseAs<ChapterPagesDto>().toPageList(folder)
            debug("response operation=READER pages=${pages.size}")
            pages
        }
    }

    override fun pageListRequest(chapter: SChapter): Request = throw UnsupportedOperationException()

    override fun pageListParse(response: Response): List<Page> = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request {
        val imageUrl = page.imageUrl ?: throw IOException("URL da imagem ausente")
        return GET(imageUrl, imageHeaders)
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================= Filters ================================

    override fun getFilterList(): FilterList = getFilters()

    // ============================= Utils ==================================

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl${chapter.url.substringBefore("?")}"

    private fun worksRequest(page: Int, sort: String, filters: FilterList): Request {
        auth.ensureSession()
        val url = "$apiUrl/rest/v1/obras".toHttpUrl().newBuilder()
            .addQueryParameter("select", CATALOG_SELECT)
            .addQueryParameter("or", "(is_acervo_b.is.null,is_acervo_b.eq.false)")
            .addQueryParameter("order", sort)
            .addQueryParameter("offset", ((page - 1) * PAGE_SIZE).toString())
            .addQueryParameter("limit", PAGE_SIZE.toString())

        url.applyFilters(filters)

        return GET(url.build(), restHeaders)
    }

    private fun chapterListRequest(manga: SManga, offset: Int): Request {
        auth.ensureSession()
        val url = "$apiUrl/rest/v1/capitulos".toHttpUrl().newBuilder()
            .addQueryParameter("select", CHAPTERS_SELECT)
            .addQueryParameter("obra_id", "eq.${manga.workId()}")
            .addQueryParameter("order", "numero.asc")
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("limit", CHAPTER_PAGE_SIZE.toString())
            .build()

        return GET(url, restHeaders)
    }

    private fun latestChaptersRequest(): Request {
        auth.ensureSession()
        val url = "$apiUrl/rest/v1/capitulos".toHttpUrl().newBuilder()
            .addQueryParameter("select", LATEST_SELECT)
            .addQueryParameter("created_at", "gte.${latestSince()}")
            .addQueryParameter("order", "created_at.desc")
            .addQueryParameter("limit", LATEST_LIMIT.toString())
            .build()

        return GET(url, restHeaders)
    }

    private fun latestSince(): String = java.time.Instant.now()
        .minusSeconds(LATEST_WINDOW_SECONDS)
        .toString()

    private fun worksByIdsRequest(ids: List<String>, filters: FilterList): Request {
        auth.ensureSession()
        val url = "$apiUrl/rest/v1/obras".toHttpUrl().newBuilder()
            .addQueryParameter("select", CATALOG_SELECT)
            .addQueryParameter("id", "in.(${ids.joinToString()})")
            .addQueryParameter("or", "(is_acervo_b.is.null,is_acervo_b.eq.false)")

        url.applyFilters(filters)

        return GET(url.build(), restHeaders)
    }

    private fun searchIdsRequest(query: String): Request {
        auth.ensureSession()
        val payload = SearchRequestDto(
            searchTerm = query.trim(),
            maxResults = SEARCH_LIMIT,
            archiveBOnly = false,
        ).toJsonRequestBody()

        return POST("$apiUrl/rest/v1/rpc/fuzzy_search_obras", rpcHeaders, payload)
    }

    private fun folderRequest(workId: String): Request {
        auth.ensureSession()
        val url = "$apiUrl/rest/v1/obras".toHttpUrl().newBuilder()
            .addQueryParameter("select", FOLDER_SELECT)
            .addQueryParameter("id", "eq.$workId")
            .build()

        return GET(url, restHeaders)
    }

    private fun chapterPagesRequest(chapterId: String): Request {
        auth.ensureSession()
        val payload = ChapterRequestDto(chapterId).toJsonRequestBody()
        return POST("$apiUrl/functions/v1/get-chapter", functionHeaders, payload)
    }

    private fun HttpUrl.Builder.applyFilters(filters: FilterList) {
        filters.formatValue().takeIf { it.isNotEmpty() }?.let {
            addQueryParameter("formato", "in.($it)")
        }

        filters.statusValue().takeIf { it.isNotEmpty() }?.let {
            addQueryParameter("status", "eq.$it")
        }

        filters.chapterRange()?.let { (min, max) ->
            min?.let { addQueryParameter("total_capitulos", "gte.$it") }
            max?.let { addQueryParameter("total_capitulos", "lte.$it") }
        }

        filters.selectedTags().takeIf { it.isNotEmpty() }?.let { tags ->
            val selected = tags.joinToString(prefix = "{", postfix = "}")
            addQueryParameter("or", "(tags.cs.$selected,generos.cs.$selected)")
        }
    }

    private fun Response.toWorksPage(): MangasPage {
        debug("response operation=CATALOG http=$code")
        if (code == 401) throw IOException(loginMessage())
        if (!isSuccessful) throw IOException("Falha ao carregar catálogo: HTTP $code")
        val page = request.url.queryParameter("offset")?.toIntOrNull()?.div(PAGE_SIZE)?.plus(1) ?: 1
        val works = parseAs<List<WorkDto>>()
        debug("response operation=CATALOG items=${works.size}")
        if (works.isEmpty() && !auth.hasSession()) throw IOException(loginMessage())
        val total = header("content-range")?.substringAfter("/")?.toIntOrNull()
        val hasNextPage = total?.let { page * PAGE_SIZE < it } ?: (works.size == PAGE_SIZE)

        return MangasPage(works.map { it.toSManga() }, hasNextPage)
    }

    private fun loginMessage() = "Login necessário. Abra o WebView da Ink Scan pelo ícone do globo, entre na sua conta e tente novamente."

    private fun loginCardPage(): MangasPage = MangasPage(listOf(loginCardManga()), false)

    private fun loginCardManga(): SManga = SManga.create().apply {
        title = "🔐 Login necessário"
        url = LOGIN_REQUIRED_URL
        description = "Abra o WebView da Ink Scan pelo ícone do globo, entre normalmente na sua conta e depois volte e atualize a fonte."
    }

    private fun debug(message: String) = Log.d(DEBUG_TAG, "INKSCAN_DEBUG $message")

    private fun loginDebug(message: String) = Log.d(LOGIN_DEBUG_TAG, "INKSCAN_LOGIN_DEBUG $message")

    private fun prefDebug(message: String) = Log.d(PREF_DEBUG_TAG, "INKSCAN_PREF_DEBUG $message")

    private fun prefDebugSnapshot(label: String, preference: Preference, phase: String) {
        prefDebug("pref=$label phase=$phase class=${preference::class.java.name}")
        prefDebug("pref=$label key=${preference.key}")
        prefDebug("pref=$label title_present=${!preference.title.isNullOrBlank()} title=${preference.title}")
        prefDebug("pref=$label summary_present=${!preference.summary.isNullOrBlank()}")
        prefDebug("pref=$label visible=${preference.optionalBooleanProperty("isVisible")}")
        prefDebug("pref=$label enabled=${preference.optionalBooleanProperty("isEnabled")}")
        prefDebug("pref=$label selectable=${preference.optionalBooleanProperty("isSelectable")}")
        prefDebug("pref=$label order=${preference.optionalProperty("getOrder")}")
        prefDebug("pref=$label layout=${preference.optionalProperty("getLayoutResource")}")
        prefDebug("pref=$label widget_layout=${preference.optionalProperty("getWidgetLayoutResource")}")
        prefDebug("pref=$label identity=${System.identityHashCode(preference)}")
    }

    private fun setPreferenceSelectable(preference: Preference, selectable: Boolean) {
        runCatching {
            preference::class.java.methods.firstOrNull { method ->
                method.name == "setSelectable" && method.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType))
            }?.invoke(preference, selectable)
        }.onFailure {
            prefDebug("set_selectable_failed class=${preference::class.java.name}")
        }
    }

    private fun Preference.optionalBooleanProperty(name: String): String = optionalProperty(name) ?: "unavailable"

    private fun Preference.optionalProperty(name: String): String? = runCatching {
        javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }
            ?.invoke(this)
            ?.toString()
    }.getOrNull()

    private fun PreferenceScreen.findPreferenceDebugObject(key: String): Preference? = runCatching {
        javaClass.methods.firstOrNull { method ->
            method.name == "findPreference" && method.parameterTypes.contentEquals(arrayOf(String::class.java))
        }?.invoke(this, key) as? Preference
    }.getOrNull()

    private fun debugError(operation: String, error: Throwable) {
        val safeMessage = error.message.orEmpty()
            .replace(Regex("(?i)(access_token|refresh_token|authorization|password|captcha_token)[^,\\s}]*"), "<redacted>")
            .take(160)
        debug("error operation=$operation type=${error::class.java.simpleName} message=$safeMessage")
    }

    private fun operationFor(url: HttpUrl): String = when {
        url.host != apiHost -> "READER"
        url.encodedPath.contains("fuzzy_search_obras") -> "SEARCH"
        url.encodedPath.contains("get-chapter") -> "READER"
        url.encodedPath.endsWith("/capitulos") && url.queryParameter("select")?.contains("obras!inner") == true -> "LATEST"
        url.encodedPath.endsWith("/capitulos") -> "CHAPTERS"
        url.encodedPath.endsWith("/obras") && url.queryParameter("id")?.startsWith("eq.") == true -> "DETAILS"
        url.encodedPath.endsWith("/obras") -> "CATALOG"
        else -> "OTHER"
    }

    private fun SManga.workId(): String = "$baseUrl$url".toHttpUrl().pathSegments[1]

    private fun apiHeadersBuilder(): Headers.Builder = headersBuilder()
        .set("apikey", API_KEY)
        .set("Authorization", "Bearer $API_KEY")
        .set("x-client-info", CLIENT_INFO)

    private val imageHeaders: Headers by lazy {
        headersBuilder()
            .set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .set("Referer", "$baseUrl/")
            .build()
    }

    companion object {
        private const val PAGE_SIZE = 24
        private const val CHAPTER_PAGE_SIZE = 1000
        private const val LATEST_LIMIT = 1000
        private const val LATEST_WINDOW_SECONDS = 54 * 60 * 60L
        private const val TOKEN_REFRESH_MARGIN = 60_000L
        private const val SEARCH_LIMIT = 120
        private const val POPULAR_SORT = "total_views.desc"
        private const val LATEST_SORT = "created_at.desc"
        private const val CLIENT_INFO = "supabase-js-web/2.99.3"
        private const val API_KEY =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9." +
                "eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InNqeWJmdnlvem5tdHhtamh5Y29qIiwicm9sZSI6ImFub24iLCJpYXQiOjE3Njk1NTI3MTIsImV4cCI6MjA4NTEyODcxMn0." +
                "0nWTir-WVr83QrPoIj8GbSt2Tuu3QZONA_TMzyZ8Ljc"
        private const val CATALOG_SELECT =
            "id,titulo,capa_url,tipo,formato,status,generos,tags,total_views,total_curtidas,total_capitulos,updated_at,created_at"
        private const val DETAILS_SELECT =
            "id,titulo,capa_url,descricao,status,tipo,formato,generos,tags,autor,artista,titulos_alternativos,updated_at,created_at,is_acervo_b"
        private const val CHAPTERS_SELECT = "id,numero,titulo,created_at"
        private const val LATEST_SELECT = "obra_id,numero,created_at,obras!inner(id,titulo,capa_url,generos,tags,formato,updated_at,is_acervo_b)"
        private const val FOLDER_SELECT = "id,titulo,capa_url,tipo,slug,pasta_s3,is_acervo_b"
        private const val PREF_ACCESS_TOKEN = "inkscan_access_token"
        private const val PREF_REFRESH_TOKEN = "inkscan_refresh_token"
        private const val PREF_TOKEN_EXPIRES = "inkscan_token_expires"
        private const val LEGACY_PREF_EMAIL = "inkscan_auth_email"
        private const val LEGACY_PREF_PASSWORD = "inkscan_auth_password"
        private const val KEY_DELICATE = "sb-delicate-hill-05c1inkscan-auth-token"
        private const val KEY_SJY = "sb-sjybfvyoznmtxmjhycoj-auth-token"
        private val STORAGE_KEYS = listOf(
            KEY_DELICATE,
            KEY_SJY,
        )
        private const val LOG_TAG = "InkScanAuth"
        private const val DEBUG_TAG = "InkScanDebug"
        private const val LOGIN_DEBUG_TAG = "InkScanLoginDebug"
        private const val PREF_DEBUG_TAG = "InkScanPrefDebug"
        private const val LOGIN_REQUIRED_URL = "__inkscan_login_required__"
        private const val PREF_HOW_TO_LOGIN = "inkscan_how_to_login"
        private const val PREF_SESSION_STATUS = "inkscan_session_status"
        private const val PREF_CLEAR_SESSION = "inkscan_clear_session"
    }

    private inner class InkScanAuth {
        private val authClient = network.client
        private val lock = Any()

        fun intercept(chain: okhttp3.Interceptor.Chain): Response {
            val operation = operationFor(chain.request().url)
            val token = synchronized(lock) { validAccessToken() }
            val request = chain.request().newBuilder().apply {
                if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
            }.build()
            debug("request operation=$operation url=${request.url} bearer=${!token.isNullOrBlank()}")
            val response = chain.proceed(request)
            debug("response operation=$operation http=${response.code}")
            if (response.code != 401) return response

            response.close()
            val refreshed = synchronized(lock) {
                clearStoredSession()
                importWebViewSession()
            }
            if (refreshed.isNullOrBlank()) return chain.proceed(request)
            debug("auth retry_after_401=true")
            return chain.proceed(request.newBuilder().header("Authorization", "Bearer $refreshed").build())
        }

        fun hasSession(): Boolean = preferences.getString(PREF_ACCESS_TOKEN, null).orEmpty().isNotBlank()

        fun sessionAvailable(): Boolean = synchronized(lock) { validAccessToken() != null }

        fun importFromWebView(): Boolean = synchronized(lock) {
            importWebViewSession() != null
        }

        fun saveStorageSession(jsonContent: String, keyLabel: String): String? = synchronized(lock) {
            val now = System.currentTimeMillis()
            val session = runCatching { jsonContent.parseAs<AuthStorageDto>() }
                .onFailure { debugError("LOCALSTORAGE_PARSE", it) }
                .getOrNull()
            val access = session?.accessToken
            val expiresAt = session?.expiresAt?.times(1000L)
                ?: (now + (session?.expiresIn ?: 3600L) * 1000L)
            val usable = !access.isNullOrBlank() && expiresAt > now
            debug("localstorage key=$keyLabel parsed=${session != null}")
            debug("localstorage key=$keyLabel access_present=${!access.isNullOrBlank()}")
            debug("localstorage key=$keyLabel refresh_present=${!session?.refreshToken.isNullOrBlank()}")
            debug("localstorage key=$keyLabel expired=${expiresAt <= now}")
            if (!usable) return null
            preferences.edit()
                .putString(PREF_ACCESS_TOKEN, access)
                .putString(PREF_REFRESH_TOKEN, session.refreshToken)
                .putLong(PREF_TOKEN_EXPIRES, expiresAt)
                .apply()
            debug("auth bearer_available=true")
            access
        }

        fun ensureSession() {
            val token = synchronized(lock) { validAccessToken() }
            if (token.isNullOrBlank()) {
                debug("LOGIN_REQUIRED")
                throw IOException(loginMessage())
            }
            debug("auth bearer_available=true")
        }

        private fun validAccessToken(): String? {
            val access = preferences.getString(PREF_ACCESS_TOKEN, null)
            val expires = preferences.getLong(PREF_TOKEN_EXPIRES, 0L)
            val present = !access.isNullOrBlank()
            val valid = present && expires > System.currentTimeMillis() + TOKEN_REFRESH_MARGIN
            debug("auth persisted_access_token=$present")
            debug("auth persisted_refresh_token=${!preferences.getString(PREF_REFRESH_TOKEN, null).isNullOrBlank()}")
            debug("auth persisted_expiry=${expires > 0L}")
            debug("auth persistent_session_valid=$valid")
            if (valid) {
                debug("auth selected_session=PERSISTED")
                debug("auth bearer_available=true")
                return access
            }
            val refreshPresent = !preferences.getString(PREF_REFRESH_TOKEN, null).isNullOrBlank()
            debug("refresh needed=${!valid}")
            debug("refresh attempted=$refreshPresent")
            if (refreshPresent) {
                val refreshed = refreshStoredToken()
                debug("refresh success=${!refreshed.isNullOrBlank()}")
                refreshed?.let { return it }
            }
            clearStoredSession()
            debug("auth selected_session=NONE")
            return importWebViewSession()
        }

        private fun importWebViewSession(): String? {
            debug("localstorage import_start")
            debug("localstorage origin=https://inkscann.live")
            val imported = STORAGE_KEYS.asSequence()
                .mapNotNull { key ->
                    val value = runCatching { runBlocking { getLocalStorage(baseUrl, key) } }
                        .onFailure { debugError("LOCALSTORAGE", it) }
                        .getOrNull()
                    val label = if (key.startsWith("sb-sjy")) "sjy" else "delicate"
                    if (value.isNullOrBlank()) {
                        debug("localstorage key=$label found=false")
                        return@mapNotNull null
                    }
                    debug("localstorage key=$label found=true")
                    val access = saveStorageSession(value, label)
                    if (access != null) key to access else null
                }
                .firstOrNull()
            val selectedKey = imported?.first ?: run {
                debug("auth selected_session=NONE")
                return null
            }
            debug("auth selected_session=${if (selectedKey.startsWith("sb-sjy")) "SJY" else "DELICATE"}")
            return imported.second
        }

        private fun refreshStoredToken(): String? {
            val refresh = preferences.getString(PREF_REFRESH_TOKEN, null)
            if (refresh.isNullOrBlank()) return null
            return runCatching {
                authenticate(
                    POST(
                        "$apiUrl/auth/v1/token?grant_type=refresh_token",
                        authHeaders,
                        AuthRequestDto(refreshToken = refresh).toJsonRequestBody(),
                    ),
                )
            }.onFailure { debugError("REFRESH", it) }.getOrNull()
        }

        private fun clearStoredSession() {
            preferences.edit()
                .remove(PREF_ACCESS_TOKEN)
                .remove(PREF_REFRESH_TOKEN)
                .remove(PREF_TOKEN_EXPIRES)
                .apply()
        }

        private fun authenticate(request: Request): String {
            authClient.newCall(request).execute().use { response ->
                debug("refresh http=${response.code}")
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val auth = response.parseAs<AuthResponseDto>()
                val access = auth.accessToken ?: throw IOException("Token de acesso ausente")
                preferences.edit()
                    .putString(PREF_ACCESS_TOKEN, access)
                    .putString(PREF_REFRESH_TOKEN, auth.refreshToken)
                    .putLong(PREF_TOKEN_EXPIRES, auth.expiresAtMillis())
                    .apply()
                debug("refresh success=true")
                return access
            }
        }

        private fun AuthResponseDto.expiresAtMillis(): Long = expiresAt?.let {
            if (it < 100_000_000_000L) it * 1000L else it
        } ?: (System.currentTimeMillis() + (expiresIn ?: 3600L) * 1000L)

        private val authHeaders: Headers = Headers.Builder()
            .set("apikey", API_KEY)
            .set("x-client-info", CLIENT_INFO)
            .set("Content-Type", "application/json")
            .set("Accept", "application/json")
            .build()
    }
}
