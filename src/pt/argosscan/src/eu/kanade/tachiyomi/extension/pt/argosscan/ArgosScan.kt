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
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.string
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.Executors

@Source
abstract class ArgosScan :
    KeiSource(),
    ConfigurableSource {

    private val apiUrl = "https://api.argoscomics.online"
    private val siteUrl = "https://argoscomics.online"
    private val preferences by getPreferencesLazy()

    @Volatile private var sessionValidated = false

    override val supportsLatest = true

    override fun OkHttpClient.Builder.configureClient() = addInterceptor { chain ->
        val request = chain.request()

        if (request.url.host.startsWith("api.")) {
            syncSession()
            val cookies = client.cookieJar.loadForRequest(request.url)
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
                client.cookieJar.saveFromResponse(url.toHttpUrl(), listOf(it))
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
            val response = client.newCall(okhttp3.Request.Builder().url("$apiUrl/projects").build()).execute()
            val valid = response.code in 200..299
            response.close()
            valid
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
            client.cookieJar.saveFromResponse(httpUrl, listOf(expired))
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

    override suspend fun getPopularManga(page: Int): MangasPage {
        if (!hasSession()) return loginCardPage()
        return client.get("$apiUrl/projects").parseAs<Projects>().toMangasPage()
    }

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        if (!hasSession()) return loginCardPage()
        val projects = client.get("$apiUrl/projects").parseAs<Projects>().items
            .filter { !it.type.equals("Novel", ignoreCase = true) }
            .sortedWith(compareBy<Project> { it.latestUpdateInstant() == null }.thenByDescending { it.latestUpdateInstant() })
        val offset = (page - 1) * LATEST_PAGE_SIZE
        val pageItems = projects.drop(offset).take(LATEST_PAGE_SIZE)
        val hasNext = projects.size > offset + LATEST_PAGE_SIZE
        return MangasPage(pageItems.map { it.toSManga() }, hasNext)
    }

    // =============================== Search ===============================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (!hasSession()) return loginCardPage()
        return client.get("$apiUrl/projects").parseAs<Projects>().toMangasPage(query)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val slug = url.pathSegments.getOrNull(1) ?: return null
        return fetchDetails(slug).toSManga()
    }

    // =========================== Manga Details ============================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        if (manga.url == LOGIN_REQUIRED_URL) {
            return SMangaUpdate(loginCardManga(), emptyList())
        }
        val slug = manga.url.substringAfterLast("/")
        val projectId = manga.memo["projectId"]?.string

        if (projectId == null) {
            val project = fetchDetails(slug)
            return SMangaUpdate(
                project.toSManga(),
                if (fetchChapters) fetchChapters(project.id) else chapters,
            )
        }
        return coroutineScope {
            val updatedManga = async {
                if (fetchDetails) fetchDetails(slug).toSManga() else manga
            }
            val updatedChapters = async {
                if (fetchChapters) fetchChapters(projectId) else chapters
            }
            SMangaUpdate(updatedManga.await(), updatedChapters.await())
        }
    }

    private suspend fun fetchDetails(slug: String): Project = client.get("$apiUrl/projects/slug/$slug").parseAs<Project>()

    private suspend fun fetchChapters(projectId: String): List<SChapter> = client.get("$apiUrl/chapters?kind=published&project_id=$projectId")
        .parseAs<Chapters>()
        .toSChapterList(projectId)

    // =============================== Pages ================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterId = chapter.url.substringBefore("|")
        val response = client.get("$apiUrl/chapters/$chapterId", ensureSuccess = false)
        if (response.code == 423) {
            val dto = runCatching {
                response.parseAs<Chapter>()
            }.getOrDefault(Chapter(id = chapterId))
            throw IOException(dto.vipMessage())
        }
        if (!response.isSuccessful) throw IOException("Falha ao carregar o capítulo (${response.code}).")
        val dto = response.parseAs<Chapter>()
        if (dto.isNovelText()) throw IOException(NOVEL_MESSAGE)
        return dto.toPages()
    }

    private fun loginCardPage() = MangasPage(listOf(loginCardManga()), false)

    private fun loginCardManga() = SManga.create().apply {
        title = "🔐 Login necessário"
        url = LOGIN_REQUIRED_URL
        description = "A Argos Scan exige login com o Discord. Abra as configurações da fonte, toque em \"Conectar à Argos Scan\", faça o login e depois atualize a fonte."
    }

    companion object {
        private const val LATEST_PAGE_SIZE = 50
        private const val LOGIN_REQUIRED_URL = "/__argos_login_required__"
        private const val PREF_CONNECT = "argos_connect"
        private const val PREF_STATUS = "argos_session_status"
        private const val PREF_LOGOUT = "argos_logout"
        private const val NOVEL_MESSAGE = "📖 Este conteúdo é uma novel. A extensão Argos Scan suporta apenas capítulos em imagem. Leia este capítulo pelo WebView/site oficial."
    }
}
