package eu.kanade.tachiyomi.extension.pt.corujatoon

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
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
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.util.concurrent.Executors

@Source
abstract class CorujaToon :
    KeiSource(),
    ConfigurableSource {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(2)

    private val authExecutor = Executors.newSingleThreadExecutor()

    override suspend fun getPopularManga(page: Int): MangasPage = getSeries(page, sort = "popular")

    override suspend fun getLatestUpdates(page: Int): MangasPage = getSeries(page, sort = "recent")

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val genre = filters.firstInstanceOrNull<GenreFilter>()?.selectedValue.orEmpty()
        val type = filters.firstInstanceOrNull<TypeFilter>()?.selectedValue.orEmpty()
        val status = filters.firstInstanceOrNull<StatusFilter>()?.selectedValue.orEmpty()
        return getSeries(page, query, "recent", genre, type, status)
    }

    private suspend fun getSeries(
        page: Int,
        query: String = "",
        sort: String,
        genre: String = "",
        type: String = "",
        status: String = "",
    ): MangasPage {
        val url = "$baseUrl/api/series/list".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("sort", sort)
            .apply {
                query.trim().takeIf(String::isNotEmpty)?.let { addQueryParameter("search", it) }
                genre.takeIf(String::isNotEmpty)?.let { addQueryParameter("genre", it) }
                type.takeIf(String::isNotEmpty)?.let { addQueryParameter("type", it) }
                status.takeIf(String::isNotEmpty)?.let { addQueryParameter("status", it) }
            }
            .build()

        val result = client.get(url).parseAs<SeriesListDto>()
        return MangasPage(result.series.map(SeriesDto::toSManga), result.pagination.page < result.pagination.totalPages)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.pathSegments.firstOrNull() != "series") return null
        val slug = url.pathSegments.getOrNull(1) ?: return null
        val id = url.queryParameter("sourceId") ?: return null
        return SManga.create().apply {
            this.url = "$slug|$id"
            title = slug.replace('-', ' ').replaceFirstChar(Char::uppercase)
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        if (!fetchDetails && !fetchChapters) return SMangaUpdate(manga, chapters)

        val parsed = parseSeriesPage(client.get(getMangaUrl(manga)).asJsoup(), manga)
        return SMangaUpdate(
            if (fetchDetails) parsed.details else manga,
            if (fetchChapters) parsed.chapters else chapters,
        )
    }

    override fun getMangaUrl(manga: SManga): String {
        val (slug, id) = manga.url.split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        return "$baseUrl/series/$slug".toHttpUrl().newBuilder()
            .addQueryParameter("sourceId", id)
            .build()
            .toString()
    }

    override fun getChapterUrl(chapter: SChapter): String {
        val slug = chapter.memo["slug"]?.toString()?.trim('"') ?: throw Exception("Atualize a lista de capítulos")
        val number = chapter.memo["number"]?.toString()?.trim('"') ?: throw Exception("Atualize a lista de capítulos")
        return "$baseUrl/series/$slug/capitulo/$number"
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        syncWebViewCookies()
        lateinit var action: Preference
        lateinit var status: Preference
        lateinit var logout: Preference

        fun updateUi(loggedIn: Boolean = hasSessionCookie()) {
            action.title = if (loggedIn) "Abrir Coruja Toon" else "Entrar na Coruja Toon"
            action.summary = if (loggedIn) "Sessão encontrada; abrir o site." else "Necessário para abrir capítulos."
            status.summary = if (loggedIn) "Conectado" else "Não conectado"
            setPreferenceVisible(logout, loggedIn)
        }

        action = createPreference(screen.context).apply {
            key = "coruja_toon_login"
            setOnPreferenceClickListener {
                showLoginWebView(screen.context, ::updateUi)
                true
            }
        }
        status = createPreference(screen.context).apply {
            key = "coruja_toon_session_status"
            title = "Sessão da Coruja Toon"
            setPreferenceSelectable(this, false)
        }
        logout = createPreference(screen.context).apply {
            key = "coruja_toon_logout"
            title = "Sair da conta"
            summary = "Remove a sessão salva neste aplicativo."
            setOnPreferenceClickListener {
                AlertDialog.Builder(screen.context)
                    .setTitle("Sair da Coruja Toon?")
                    .setMessage("A sessão será removida somente deste aplicativo.")
                    .setPositiveButton("Sair") { _, _ ->
                        clearSession()
                        updateUi()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
                true
            }
        }
        screen.addPreference(action)
        screen.addPreference(status)
        screen.addPreference(logout)
        updateUi()
        authExecutor.execute {
            val valid = validateSession()
            Handler(Looper.getMainLooper()).post { updateUi(valid) }
        }
    }

    private fun createPreference(context: Context): Preference = runCatching {
        Preference::class.java.getConstructor(Context::class.java).newInstance(context)
    }.getOrElse { Preference() }

    private fun setPreferenceVisible(preference: Preference, visible: Boolean) {
        runCatching {
            preference::class.java.methods.firstOrNull { method ->
                method.name == "setVisible" && method.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType))
            }?.invoke(preference, visible)
        }
    }

    private fun setPreferenceSelectable(preference: Preference, selectable: Boolean) {
        runCatching {
            preference::class.java.methods.firstOrNull { method ->
                method.name == "setSelectable" && method.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType))
            }?.invoke(preference, selectable)
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val id = chapter.memo["id"]?.toString()?.trim('"') ?: throw Exception("Atualize a lista de capítulos")
        val chapterUrl = getChapterUrl(chapter)
        val slug = chapter.memo["slug"]?.toString()?.trim('"') ?: throw Exception("Atualize a lista de capítulos")
        val seriesId = chapter.memo["seriesId"]?.toString()?.trim('"').orEmpty()
        val seriesUrl = if (seriesId.isNotBlank()) {
            "$baseUrl/series/$slug?sourceId=$seriesId"
        } else {
            "$baseUrl/series/$slug"
        }
        syncWebViewCookies()
        val documentPages = fetchChapterDocumentPages(chapterUrl, seriesUrl)
        if (documentPages.isNotEmpty()) {
            return documentPages.mapIndexed { index, url ->
                Page(index, chapterUrl, imageUrl = url)
            }
        }

        val requestHeaders = headers.newBuilder()
            .removeAll("Origin")
            .set("Accept", "application/json")
            .set("Referer", seriesUrl)
            .build()
        client.newCall(GET("$baseUrl/api/chapters/$id", requestHeaders)).execute().use { response ->
            if (!response.isSuccessful) {
                if (response.code == 404) {
                    val sessionValid = validateSession()
                    throw IOException(
                        if (sessionValid) {
                            "A Coruja recusou o capítulo (HTTP 404) mesmo com a sessão válida. O servidor não liberou este ID."
                        } else {
                            "A Coruja exige login para abrir capítulos. Abra as configurações da fonte e toque em Entrar na Coruja Toon."
                        },
                    )
                }
                throw IOException("Coruja Toon: falha ao carregar páginas (HTTP ${response.code}).")
            }
            val parsed = response.parseAs<ChapterResponseDto>()
            require(parsed.chapter.pages.isNotEmpty()) { "O servidor não retornou páginas para este capítulo." }
            return parsed.chapter.pages.mapIndexed { index, url -> Page(index, chapterUrl, imageUrl = url) }
        }
    }

    private fun fetchChapterDocumentPages(chapterUrl: String, seriesUrl: String): List<String> = runCatching {
        val requestHeaders = headers.newBuilder()
            .removeAll("Origin")
            .set("Accept", "text/html,application/xhtml+xml")
            .set("Referer", seriesUrl)
            .build()
        client.newCall(GET(chapterUrl, requestHeaders)).execute().use { response ->
            if (!response.isSuccessful) return@use emptyList()
            val body = response.body.string()
            parseChapterPageImages(Jsoup.parse(body), chapterUrl.toHttpUrl())
        }
    }.getOrDefault(emptyList())

    override fun imageRequest(page: Page): Request = Request.Builder()
        .url(page.imageUrl!!)
        .headers(
            Headers.Builder().apply {
                headers["User-Agent"]?.let { set("User-Agent", it) }
                set("Accept", "image/*")
                set("Referer", page.url)
            }.build(),
        )
        .get()
        .build()

    private fun syncWebViewCookies() {
        val url = baseUrl.toHttpUrl()
        val cookies = CookieManager.getInstance().getCookie(baseUrl).orEmpty()
            .split(';')
            .mapNotNull { part -> Cookie.parse(url, part.trim()) }
        if (cookies.isNotEmpty()) client.cookieJar.saveFromResponse(url, cookies)
        CookieManager.getInstance().flush()
    }

    private fun hasSessionCookie(): Boolean = client.cookieJar.loadForRequest(baseUrl.toHttpUrl())
        .any { isAuthSessionCookie(it.name) && it.value.isNotBlank() }

    private fun validateSession(): Boolean = runCatching {
        val requestHeaders = headers.newBuilder().set("Accept", "application/json").build()
        client.newCall(GET("$baseUrl/api/auth/session", requestHeaders)).execute().use { response ->
            val valid = response.isSuccessful && runCatching {
                response.parseAs<JsonObject>().containsKey("user")
            }.getOrDefault(false)
            valid
        }
    }.getOrDefault(false)

    private fun clearSession() {
        val url = baseUrl.toHttpUrl()
        client.cookieJar.loadForRequest(url)
            .filter { isAuthCookie(it.name) }
            .forEach { cookie ->
                val expired = Cookie.Builder()
                    .name(cookie.name)
                    .value("")
                    .hostOnlyDomain(url.host)
                    .path(cookie.path)
                    .expiresAt(1L)
                    .build()
                client.cookieJar.saveFromResponse(url, listOf(expired))
            }
        CookieManager.getInstance().getCookie(baseUrl).orEmpty()
            .split(';')
            .map { it.trim().substringBefore('=') }
            .filter(::isAuthCookie)
            .distinct()
            .forEach { name ->
                CookieManager.getInstance().setCookie(baseUrl, "$name=; Max-Age=0; Path=/; Secure")
            }
        CookieManager.getInstance().flush()
    }

    private fun showLoginWebView(context: Context, onSessionChanged: () -> Unit) {
        val dialog = Dialog(context)
        val webView = WebView(context)
        val cookieManager = CookieManager.getInstance()
        val handler = Handler(Looper.getMainLooper())
        var checking = false
        var closed = false

        fun checkSession() {
            if (checking || closed) return
            checking = true
            syncWebViewCookies()
            authExecutor.execute {
                val valid = validateSession()
                handler.post {
                    checking = false
                    if (valid && !closed) {
                        onSessionChanged()
                        closed = true
                        dialog.dismiss()
                    } else if (!closed) {
                        handler.postDelayed({ checkSession() }, SESSION_POLL_MS)
                    }
                }
            }
        }

        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)
        webView.setBackgroundColor(Color.WHITE)
        webView.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.setSupportMultipleWindows(false)
        webView.settings.javaScriptCanOpenWindowsAutomatically = false
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                checkSession()
            }
        }
        dialog.setContentView(webView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        dialog.setOnDismissListener {
            closed = true
            handler.removeCallbacksAndMessages(null)
            webView.stopLoading()
            webView.destroy()
        }
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.WHITE))
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        webView.loadUrl("$baseUrl/login")
        handler.postDelayed({ checkSession() }, SESSION_POLL_MS)
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        GenreFilter(),
        TypeFilter(),
        StatusFilter(),
    )

    private companion object {
        const val PAGE_SIZE = 24
        const val SESSION_POLL_MS = 1_000L
        val SESSION_COOKIE_NAMES = setOf(
            "next-auth.session-token",
            "__Secure-next-auth.session-token",
            "__Host-next-auth.session-token",
            "authjs.session-token",
            "__Secure-authjs.session-token",
            "__Host-authjs.session-token",
        )

        fun isAuthSessionCookie(name: String): Boolean = name in SESSION_COOKIE_NAMES

        fun isAuthCookie(name: String): Boolean = isAuthSessionCookie(name) ||
            name.contains("next-auth", ignoreCase = true) || name.contains("authjs", ignoreCase = true)
    }
}
