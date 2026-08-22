package eu.kanade.tachiyomi.extension.pt.valkyuri

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
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
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import java.io.IOException
import java.util.concurrent.Executors

@Source
abstract class ValkYuri :
    HttpSource(),
    ConfigurableSource {

    override val supportsLatest = true

    private val apiUrl = "https://nexus.valkyuri.com/api"
    private val siteUrl = "https://valkyuri.com"
    private val preferences by getPreferencesLazy()

    @Volatile private var sessionValidated = false

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor { chain ->
            syncSession()
            if (!hasAuthCookie()) throw IOException(LOGIN_MESSAGE)
            val response = chain.proceed(chain.request())
            if (response.code == 401 || response.code == 403) {
                sessionValidated = false
                response.close()
                throw IOException("Acesso não autorizado. Faça login com Discord e confirme que está no servidor oficial da ValkYuri.")
            }
            response
        }.build()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        lateinit var action: Preference
        lateinit var status: Preference
        lateinit var clear: Preference

        fun update() {
            val connected = sessionValidated
            action.title = if (connected) "Abrir ValkYuri" else "Login obrigatório"
            action.summary = if (connected) "Abrir o site da ValkYuri." else "Faça login com Discord pelo WebView e esteja no servidor oficial."
            status.summary = if (connected) "Logado" else "Não logado"
            setVisible(clear, connected)
        }

        action = preference(screen.context).apply {
            key = PREF_LOGIN
            setOnPreferenceClickListener {
                showLoginWebView(screen.context, ::update)
                true
            }
        }
        status = preference(screen.context).apply {
            key = PREF_STATUS
            title = "Sessão da ValkYuri"
            setSelectable(this, false)
        }
        clear = preference(screen.context).apply {
            key = PREF_CLEAR
            title = "Limpar sessão"
            summary = "Remove somente a sessão da ValkYuri."
            setOnPreferenceClickListener {
                AlertDialog.Builder(screen.context).setTitle("Limpar sessão?")
                    .setMessage("Isso removerá somente a sessão da ValkYuri neste aplicativo.")
                    .setPositiveButton("Limpar") { _, _ ->
                        clearSession()
                        update()
                    }
                    .setNegativeButton("Cancelar", null).show()
                true
            }
        }
        screen.addPreference(action)
        screen.addPreference(status)
        screen.addPreference(clear)
        update()
        validateSessionAsync { update() }
    }

    private fun preference(context: Context): Preference = runCatching {
        Preference::class.java.getConstructor(Context::class.java).newInstance(context)
    }.getOrElse { Preference() }

    private fun setVisible(preference: Preference, visible: Boolean) {
        runCatching { preference.javaClass.methods.firstOrNull { it.name == "setVisible" }?.invoke(preference, visible) }
    }

    private fun setSelectable(preference: Preference, selectable: Boolean) {
        runCatching { preference.javaClass.methods.firstOrNull { it.name == "setSelectable" }?.invoke(preference, selectable) }
    }

    private fun authCookieNames() = listOf("laravel_session", "laravel-session", "valkiryescan_session", "valkiryescan-session", "valk_session", "valk-session", "valk_auth")

    private fun syncSession(): Boolean {
        CookieManager.getInstance().flush()
        val values = authCookieNames().mapNotNull { name ->
            val value = listOf(siteUrl, apiUrl).asSequence().flatMap { url ->
                CookieManager.getInstance().getCookie(url).orEmpty().split(';').asSequence()
            }.map { it.trim() }.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')
            value?.takeIf { it.isNotBlank() }?.let { name to it }
        }
        if (values.isEmpty()) return false
        listOf(siteUrl, apiUrl).forEach { url ->
            val host = url.toHttpUrl().host
            val cookies = values.mapNotNull { (name, value) -> Cookie.parse(url.toHttpUrl(), "$name=$value; Domain=$host; Path=/") }
            network.client.cookieJar.saveFromResponse(url.toHttpUrl(), cookies)
        }
        return true
    }

    private fun hasAuthCookie(): Boolean = network.client.cookieJar.loadForRequest(apiUrl.toHttpUrl()).any { it.name in authCookieNames() && it.value.isNotBlank() }

    private fun validateSession(): Boolean = runCatching {
        syncSession()
        client.newCall(GET("$apiUrl/auth/me", headers)).execute().use { it.isSuccessful }
    }.getOrDefault(false).also { sessionValidated = it }

    private fun validateSessionAsync(onResult: (Boolean) -> Unit) = Executors.newSingleThreadExecutor().execute {
        val result = validateSession()
        Handler(Looper.getMainLooper()).post { onResult(result) }
    }

    private fun clearSession() {
        val manager = CookieManager.getInstance()
        val names = authCookieNames()
        listOf(siteUrl, apiUrl).forEach { url ->
            names.forEach { name ->
                manager.setCookie(url, "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/")
                val cookie = Cookie.Builder().name(name).value("").domain(url.toHttpUrl().host).path("/").expiresAt(1L).build()
                network.client.cookieJar.saveFromResponse(url.toHttpUrl(), listOf(cookie))
            }
        }
        manager.flush()
        sessionValidated = false
    }

    private fun showLoginWebView(context: Context, onImported: () -> Unit) {
        val dialog = Dialog(context)
        val webView = WebView(context)
        val handler = Handler(Looper.getMainLooper())
        var closed = false
        fun finish() {
            if (!closed) {
                closed = true
                handler.removeCallbacksAndMessages(null)
                webView.stopLoading()
                dialog.dismiss()
                webView.destroy()
                onImported()
            }
        }
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (url.orEmpty().startsWith(siteUrl) && validateSession()) finish()
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

    override fun headersBuilder() = super.headersBuilder()
        .add("Accept", "application/json")
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    override fun fetchPopularManga(page: Int): Observable<MangasPage> = if (sessionValidated) super.fetchPopularManga(page) else Observable.just(loginCardPage())

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = if (sessionValidated) super.fetchLatestUpdates(page) else Observable.just(loginCardPage())

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = if (sessionValidated) super.fetchSearchManga(page, query, filters) else Observable.just(loginCardPage())

    private fun loginCardPage() = MangasPage(
        listOf(
            SManga.create().apply {
                title = "🔐 Login obrigatório"
                url = LOGIN_REQUIRED_URL
                description = "A ValkYuri exige login com Discord e participação no servidor oficial. Faça login pelo WebView da extensão. O acesso é validado pelo próprio sistema da ValkYuri."
            },
        ),
        false,
    )

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = if (manga.url == LOGIN_REQUIRED_URL) Observable.just(manga) else super.fetchMangaDetails(manga)

    override fun popularMangaRequest(page: Int): Request {
        val url = discoveryUrl(POPULAR_SECTION, SECTION_LIMIT)
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage = response.use {
        it.parseAs<HomeDiscoveryResponseDto>().toMangasPage(POPULAR_SECTION)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$apiUrl/releases/latest".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("per_page", PAGE_SIZE.toString())
            .build()

        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = response.use {
        it.parseAs<LatestReleasesResponseDto>().toMangasPage()
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val normalizedQuery = query.trim()
        val url = if (normalizedQuery.isNotEmpty()) {
            "$apiUrl/search/mangas".toHttpUrl().newBuilder()
                .addQueryParameter("q", normalizedQuery)
                .addQueryParameter("page", page.toString())
                .addQueryParameter("per_page", PAGE_SIZE.toString())
                .build()
        } else {
            val section = filters.firstInstanceOrNull<SectionFilter>()?.selected ?: POPULAR_SECTION
            if (section == CATALOG_SECTION) {
                val sort = filters.firstInstanceOrNull<CatalogSortFilter>()?.selected ?: "latest"
                catalogUrl(sort, page)
            } else {
                discoveryUrl(section, SECTION_LIMIT)
            }
        }

        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = response.use {
        if (it.request.url.encodedPath.endsWith("/home/discovery")) {
            val section = it.request.url.queryParameter("section") ?: POPULAR_SECTION
            it.parseAs<HomeDiscoveryResponseDto>().toMangasPage(section)
        } else {
            it.parseAs<MangaListResponseDto>().toMangasPage()
        }
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/media/${manga.url}"

    override fun mangaDetailsRequest(manga: SManga): Request {
        val url = "$apiUrl/mangas".toHttpUrl().newBuilder()
            .addPathSegment(manga.url)
            .build()

        return GET(url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga = response.use {
        it.parseAs<MangaDetailsResponseDto>().toSManga()
    }

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> = response.use {
        it.parseAs<MangaDetailsResponseDto>().toChapterList()
    }

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl${chapter.url}"

    override fun pageListRequest(chapter: SChapter): Request {
        val chapterUrl = getChapterUrl(chapter).toHttpUrl()
        val pathSegments = chapterUrl.pathSegments
        val mangaSlug = pathSegments.getOrNull(1).orEmpty()
        val chapterNumber = pathSegments.getOrNull(2).orEmpty()

        val url = "$apiUrl/mangas".toHttpUrl().newBuilder()
            .addPathSegment(mangaSlug)
            .addPathSegment("chapters")
            .addPathSegment(chapterNumber)
            .build()

        return GET(url, headers)
    }

    override fun pageListParse(response: Response): List<Page> = response.use {
        it.parseAs<ChapterDetailsResponseDto>().toPages()
    }

    override fun imageRequest(page: Page): Request {
        val imageUrl = page.imageUrl ?: page.url
        return GET(imageUrl, headers)
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun getFilterList() = FilterList(
        SectionFilter(),
        CatalogSortFilter(),
    )

    private fun catalogUrl(sort: String, page: Int): HttpUrl = "$apiUrl/mangas".toHttpUrl().newBuilder()
        .addQueryParameter("sort", sort)
        .addQueryParameter("page", page.toString())
        .addQueryParameter("per_page", PAGE_SIZE.toString())
        .build()

    private fun discoveryUrl(section: String, limit: Int): HttpUrl = "$apiUrl/home/discovery".toHttpUrl().newBuilder()
        .addQueryParameter("limit", limit.toString())
        .addQueryParameter("section", section)
        .build()

    companion object {
        private const val LOGIN_MESSAGE = "Login obrigatório. Faça login com Discord e confirme que está no servidor oficial da ValkYuri."
        private const val LOGIN_REQUIRED_URL = "/__valkyuri_login_required__"
        private const val PREF_LOGIN = "valkyuri_login"
        private const val PREF_STATUS = "valkyuri_status"
        private const val PREF_CLEAR = "valkyuri_clear"
        private const val PAGE_SIZE = 24
        private const val SECTION_LIMIT = 50
        private const val POPULAR_SECTION = "popular_week"
    }
}
