package eu.kanade.tachiyomi.extension.pt.argosscan

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

@Source
abstract class ArgosScan :
    HttpSource(),
    ConfigurableSource {

    private val apiUrl = "https://api.argoscomics.online"
    private val siteUrl = "https://argoscomics.online"
    private val preferences by getPreferencesLazy()

    @Volatile private var sessionValidated = false

    override val supportsLatest = true

    private val dateFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    private val authInterceptor = Interceptor { chain ->
        val request = chain.request()

        if (request.url.host.startsWith("api.")) {
            syncSession()
            val cookies = network.client.cookieJar.loadForRequest(request.url)
            val hasAuth = cookies.any { it.name == "session" && it.value.isNotEmpty() }

            if (!hasAuth) {
                throw IOException("Login necessário. Abra o WebView e faça login com o Discord para usar a extensão.")
            }
        }

        val response = chain.proceed(request)

        if (response.code == 401 || response.code == 403) {
            sessionValidated = false
            clearSession()
            throw IOException("Sessão expirada. Faça login novamente no WebView.")
        }

        response
    }

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor(authInterceptor)
        .build()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        lateinit var action: Preference
        lateinit var status: Preference
        lateinit var logout: Preference

        fun update() {
            val connected = hasSession()
            action.title = if (connected) "Abrir Argos Scan" else "Conectar à Argos Scan"
            action.summary = if (connected) "Abre o site da Argos Scan." else "Faça login com o Discord pelo site oficial."
            status.summary = if (connected) "Conectado" else "Não conectado"
            setPreferenceVisible(logout, connected)
        }

        action = createPreference(screen.context).apply {
            key = PREF_CONNECT
            setOnPreferenceClickListener {
                showLoginWebView(screen.context, ::update)
                true
            }
        }
        status = createPreference(screen.context).apply {
            key = PREF_STATUS
            title = "Sessão da Argos Scan"
            setOnPreferenceClickListener { true }
        }
        setPreferenceSelectable(status, false)
        logout = createPreference(screen.context).apply {
            key = PREF_LOGOUT
            title = "Sair da conta"
            summary = "Remove a sessão da Argos Scan salva neste aplicativo."
            setOnPreferenceClickListener {
                AlertDialog.Builder(screen.context)
                    .setTitle("Sair da conta?")
                    .setMessage("Isso removerá somente a sessão da Argos Scan neste aplicativo.")
                    .setPositiveButton("Sair") { _, _ ->
                        clearSession()
                        sessionValidated = false
                        update()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
                true
            }
        }
        screen.addPreference(action)
        screen.addPreference(status)
        screen.addPreference(logout)
        update()
        validateSessionAsync { update() }
    }

    private fun createPreference(context: Context): Preference = runCatching {
        Preference::class.java.getConstructor(Context::class.java).newInstance(context)
    }.getOrElse { Preference() }

    private fun setPreferenceVisible(preference: Preference, visible: Boolean) {
        preference::class.java.methods.firstOrNull { it.name == "setVisible" }?.invoke(preference, visible)
    }

    private fun setPreferenceSelectable(preference: Preference, selectable: Boolean) {
        preference::class.java.methods.firstOrNull { it.name == "setSelectable" }?.invoke(preference, selectable)
    }

    private fun cookieValue(url: String): String? = CookieManager.getInstance().getCookie(url)
        ?.split(';')?.asSequence()?.map { it.trim() }
        ?.firstOrNull { it.startsWith("session=") }
        ?.substringAfter('=')?.takeIf { it.isNotEmpty() }

    private fun syncSession(): Boolean {
        CookieManager.getInstance().flush()
        val value = cookieValue(siteUrl) ?: cookieValue(apiUrl) ?: return false
        listOf(siteUrl, apiUrl).forEach { url ->
            Cookie.parse(url.toHttpUrl(), "session=$value; Domain=${url.toHttpUrl().host}; Path=/")?.let {
                network.client.cookieJar.saveFromResponse(url.toHttpUrl(), listOf(it))
            }
        }
        CookieManager.getInstance().flush()
        return true
    }

    private fun hasSession(): Boolean = sessionValidated

    private fun validateSession(): Boolean {
        sessionValidated = false
        if (!syncSession()) return false
        return runCatching {
            client.newCall(GET("$apiUrl/projects", headers)).execute().use { response ->
                response.code in 200..299
            }
        }.getOrDefault(false).also { valid ->
            sessionValidated = valid
            if (!valid) clearSession()
        }
    }

    private fun validateSessionAsync(onResult: (Boolean) -> Unit) {
        Executors.newSingleThreadExecutor().execute {
            val valid = validateSession()
            Handler(Looper.getMainLooper()).post { onResult(valid) }
        }
    }

    private fun clearSession() {
        val manager = CookieManager.getInstance()
        listOf(siteUrl, apiUrl).forEach { url ->
            manager.setCookie(url, "session=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/")
            val httpUrl = url.toHttpUrl()
            val expired = Cookie.Builder().name("session").value("").domain(httpUrl.host).path("/").expiresAt(1L).build()
            network.client.cookieJar.saveFromResponse(httpUrl, listOf(expired))
        }
        manager.flush()
    }

    private fun showLoginWebView(context: Context, onImported: () -> Unit) {
        val dialog = Dialog(context)
        val webView = WebView(context)
        val handler = Handler(Looper.getMainLooper())
        var closed = false
        var visitedDiscord = false
        var validationStarted = false
        fun finish() {
            if (closed) return
            closed = true
            handler.removeCallbacksAndMessages(null)
            webView.stopLoading()
            if (dialog.isShowing) dialog.dismiss()
            webView.destroy()
            onImported()
        }
        fun validateReturn() {
            if (!visitedDiscord || validationStarted) return
            validationStarted = true
            validateSessionAsync { valid ->
                validationStarted = false
                if (valid) {
                    CookieManager.getInstance().flush()
                    finish()
                }
            }
        }
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                val host = Uri.parse(url).host
                if (host == "discord.com" || host == "www.discord.com") visitedDiscord = true
                if (host == "argoscomics.online" || host == "www.argoscomics.online") validateReturn()
            }
        }
        dialog.setContentView(webView, ViewGroup.LayoutParams(-1, -1))
        dialog.setOnDismissListener {
            if (!closed) {
                closed = true
                handler.removeCallbacksAndMessages(null)
                webView.destroy()
            }
        }
        dialog.show()
        webView.loadUrl("$siteUrl/login")
    }

    // ============================== Popular ===============================

    override fun fetchPopularManga(page: Int): Observable<MangasPage> = if (hasSession()) {
        super.fetchPopularManga(page)
    } else {
        Observable.just(loginCardPage())
    }

    override fun popularMangaRequest(page: Int): Request = GET("$apiUrl/projects", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        if (!hasSession()) return loginCardPage()
        val mangas = response.parseAs<ProjectResponseDto>().toSMangaList()
        return MangasPage(mangas, false)
    }

    // =============================== Latest ===============================

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = if (!hasSession()) {
        Observable.just(loginCardPage())
    } else {
        Observable.fromCallable { loadLatest(page) }
    }

    private fun loadLatest(page: Int): MangasPage {
        val projects = client.newCall(GET("$apiUrl/projects", headers)).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Falha ao buscar projetos.")
            response.parseAs<ProjectResponseDto>().allItems()
        }
            .filter { !it.type.equals("Novel", ignoreCase = true) }
            .sortedWith(compareBy<ProjectDto> { it.latestUpdateInstant() == null }.thenByDescending { it.latestUpdateInstant() })
        val offset = (page - 1) * LATEST_PAGE_SIZE
        val pageItems = projects.drop(offset).take(LATEST_PAGE_SIZE)
        val hasNext = projects.size > offset + LATEST_PAGE_SIZE
        return MangasPage(pageItems.map { it.toSManga() }, hasNext)
    }

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException("Latest uses projects endpoint.")

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // =============================== Search ===============================

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = if (hasSession()) {
        super.fetchSearchManga(page, query, filters)
    } else {
        Observable.just(loginCardPage())
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = GET("$apiUrl/projects#${query.trim()}", headers)

    override fun searchMangaParse(response: Response): MangasPage {
        if (!hasSession()) return loginCardPage()
        val query = response.request.url.fragment ?: ""
        val mangas = response.parseAs<ProjectResponseDto>().toSMangaList(query)
        return MangasPage(mangas, false)
    }

    // =========================== Manga Details ============================

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = if (manga.url == LOGIN_REQUIRED_URL) {
        Observable.just(loginCardManga())
    } else {
        super.fetchMangaDetails(manga)
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"

    override fun mangaDetailsRequest(manga: SManga): Request {
        if (manga.url == LOGIN_REQUIRED_URL) return GET("$baseUrl$LOGIN_REQUIRED_URL", headers)
        val slug = manga.url.substringAfterLast("/")
        return GET("$apiUrl/projects/slug/$slug", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga = if (response.request.url.encodedPath == LOGIN_REQUIRED_URL) loginCardManga() else response.parseAs<ProjectDto>().toSManga()

    // ============================== Chapters ==============================

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable {
        if (manga.url == LOGIN_REQUIRED_URL) return@fromCallable emptyList()
        val slug = manga.url.substringAfterLast("/")

        // 1. Fetch details first to extract the project ID
        val detailsReq = GET("$apiUrl/projects/slug/$slug", headers)
        val detailsRes = client.newCall(detailsReq).execute()

        if (!detailsRes.isSuccessful) {
            throw IOException("Falha ao buscar os detalhes do projeto.")
        }
        val projectDto = detailsRes.parseAs<ProjectDto>()

        // 2. Fetch the chapters using the required project_id
        val chaptersReq = GET("$apiUrl/chapters?kind=published&project_id=${projectDto.id}", headers)
        val chaptersRes = client.newCall(chaptersReq).execute()

        if (!chaptersRes.isSuccessful) {
            throw IOException("Falha ao buscar os capítulos.")
        }

        chaptersRes.parseAs<ChapterResponseDto>().toSChapterList(projectDto.id, dateFormat)
    }

    override fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException("Not used.")

    // =============================== Pages ================================

    override fun pageListRequest(chapter: SChapter): Request {
        val parts = chapter.url.split("|")
        val chapterId = parts[0]
        return GET("$apiUrl/chapters/$chapterId", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        if (response.code == 423) {
            val chapter = runCatching {
                response.peekBody(Long.MAX_VALUE).string().parseAs<ChapterDto>()
            }.getOrDefault(ChapterDto())
            throw IOException(chapter.vipMessage())
        }
        return response.parseAs<ChapterDto>().toPages()
    }

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = Observable.fromCallable {
        val chapterId = chapter.url.substringBefore("|")
        client.newCall(GET("$apiUrl/chapters/$chapterId", headers)).execute().use { response ->
            if (response.code == 423) {
                val dto = runCatching {
                    response.peekBody(Long.MAX_VALUE).string().parseAs<ChapterDto>()
                }.getOrDefault(ChapterDto())
                throw IOException(dto.vipMessage())
            }
            if (!response.isSuccessful) throw IOException("Falha ao carregar o capítulo (${response.code}).")
            val dto = response.parseAs<ChapterDto>()
            if (dto.isNovelText()) throw IOException(NOVEL_MESSAGE)
            dto.toPages()
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used.")

    private fun loginCardPage() = MangasPage(listOf(loginCardManga()), false)

    private fun loginCardManga() = SManga.create().apply {
        title = "🔐 Login necessário"
        url = LOGIN_REQUIRED_URL
        description = "A Argos Scan exige login com o Discord. Abra as configurações da fonte, toque em \"Conectar à Argos Scan\", faça o login e depois atualize a fonte."
    }

    private companion object {
        const val LATEST_PAGE_SIZE = 50
        const val LOGIN_REQUIRED_URL = "/__argos_login_required__"
        const val PREF_CONNECT = "argos_connect"
        const val PREF_STATUS = "argos_session_status"
        const val PREF_LOGOUT = "argos_logout"
        const val NOVEL_MESSAGE = "📖 Este conteúdo é uma novel. A extensão Argos Scan suporta apenas capítulos em imagem. Leia este capítulo pelo WebView/site oficial."
    }
}
