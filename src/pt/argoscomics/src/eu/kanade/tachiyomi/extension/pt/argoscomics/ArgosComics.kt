package eu.kanade.tachiyomi.extension.pt.argoscomics

import android.app.AlertDialog
import android.app.Dialog
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
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.ConfigurableSource
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
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.extractNextJsRsc
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.toJsonRequestBody
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds

@Source
abstract class ArgosComics :
    KeiSource(),
    ConfigurableSource {

    private val preferences by getPreferencesLazy()
    private val authExecutor = Executors.newSingleThreadExecutor()

    override fun OkHttpClient.Builder.configureClient() = apply {
        rateLimit(3, 2.seconds)
    }

    private val rscHeaders
        get() = headersBuilder().set("rsc", "1").build()

    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {
        lateinit var action: androidx.preference.Preference
        lateinit var status: androidx.preference.Preference
        lateinit var logout: androidx.preference.Preference

        fun update(valid: Boolean) {
            action.title = if (valid) "Abrir Argos Comics" else "Conectar à Argos Comics"
            action.summary = if (valid) "Abre o site da Argos Comics." else "Abre o login oficial da Argos Comics."
            status.summary = if (valid) "Conectado" else "Não conectado"
            setPreferenceVisible(logout, valid)
        }

        action = createPreference(screen.context).apply {
            key = "argos_comics_connect"
            setOnPreferenceClickListener {
                showLoginWebView(screen.context) { update(validateSession()) }
                true
            }
        }
        status = createPreference(screen.context).apply {
            key = "argos_comics_status"
            title = "Sessão da Argos Comics"
            setPreferenceSelectable(this, false)
        }
        logout = createPreference(screen.context).apply {
            key = "argos_comics_logout"
            title = "Sair da conta"
            summary = "Remove a sessão da Argos Comics salva neste aplicativo."
            setOnPreferenceClickListener {
                AlertDialog.Builder(screen.context).setTitle("Sair da conta?")
                    .setMessage("Isso removerá somente a sessão da Argos Comics.")
                    .setPositiveButton("Sair") { _, _ ->
                        clearSession()
                        update(false)
                    }
                    .setNegativeButton("Cancelar", null).show()
                true
            }
        }
        screen.addPreference(action)
        screen.addPreference(status)
        screen.addPreference(logout)
        update(false)
        authExecutor.execute {
            val valid = validateSession()
            Handler(Looper.getMainLooper()).post { update(valid) }
        }
    }

    private fun createPreference(context: android.content.Context): Preference = runCatching {
        Preference::class.java.getConstructor(android.content.Context::class.java).newInstance(context)
    }.getOrElse { Preference() }

    private fun setPreferenceVisible(preference: Preference, visible: Boolean) {
        runCatching {
            preference::class.java.methods.firstOrNull { it.name == "setVisible" }?.invoke(preference, visible)
        }
    }

    private fun setPreferenceSelectable(preference: Preference, selectable: Boolean) {
        runCatching {
            preference::class.java.methods.firstOrNull { it.name == "setSelectable" }?.invoke(preference, selectable)
        }
    }

    private fun sessionCookies(): List<Cookie> {
        val url = baseUrl.toHttpUrl()
        val raw = CookieManager.getInstance().getCookie(baseUrl)
        val cookies = raw?.split(';').orEmpty()
            .mapNotNull { part -> Cookie.parse(url, "${part.trim()}; Domain=${url.host}; Path=/") }
        Log.d(
            DEBUG_TAG,
            "stage=AUTH_WEB_COOKIES cookieNames=${cookies.map { it.name }.distinct().sorted()} " +
                "hasAccessToken=${cookies.any { it.name == "access_token" }} " +
                "domains=${cookies.map { it.domain }.distinct().sorted()} paths=${cookies.map { it.path }.distinct().sorted()}",
        )
        return cookies
    }

    private fun importSession(): Boolean {
        val cookies = sessionCookies()
        if (cookies.isEmpty()) {
            Log.d(DEBUG_TAG, "stage=AUTH_SYNC webCookieNames=[] clientCookieNames=${clientCookieNames()} webHasAccessToken=false clientHasAccessToken=${clientHasAccessToken()}")
            return false
        }
        client.cookieJar.saveFromResponse(baseUrl.toHttpUrl(), cookies)
        CookieManager.getInstance().flush()
        val clientNames = clientCookieNames()
        val clientHasToken = clientHasAccessToken()
        Log.d(
            DEBUG_TAG,
            "stage=AUTH_SYNC webCookieNames=${cookies.map { it.name }.distinct().sorted()} " +
                "clientCookieNames=$clientNames webHasAccessToken=${cookies.any { it.name == "access_token" }} " +
                "clientHasAccessToken=$clientHasToken",
        )
        return clientHasToken
    }

    private fun validateSession(): Boolean {
        if (!importSession()) {
            Log.d(DEBUG_TAG, "stage=AUTH_CONNECTED validated=false reason=access_token_not_available")
            return false
        }
        Log.d(DEBUG_TAG, "stage=AUTH_CONNECTED validated=true reason=access_token_synced_after_login")
        return true
    }

    private fun clientCookieNames(): List<String> = client.cookieJar.loadForRequest(baseUrl.toHttpUrl())
        .map { it.name }.distinct().sorted()

    private fun clientHasAccessToken(): Boolean = clientCookieNames().contains("access_token")

    private fun clearSession() {
        val url = baseUrl.toHttpUrl()
        sessionCookies().forEach { cookie ->
            val expired = Cookie.Builder().name(cookie.name).value("").domain(cookie.domain).path(cookie.path)
                .expiresAt(1L).build()
            client.cookieJar.saveFromResponse(url, listOf(expired))
            CookieManager.getInstance().setCookie(baseUrl, "${cookie.name}=; Max-Age=0; Domain=${cookie.domain}; Path=${cookie.path}")
        }
        CookieManager.getInstance().flush()
        preferences.edit().clear().apply()
    }

    private fun showLoginWebView(context: android.content.Context, onValidated: () -> Unit) {
        val dialog = Dialog(context)
        val webView = WebView(context)
        var sessionHandled = false
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
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? = if (isAuthAdHost(request?.url?.host.orEmpty())) {
                WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
            } else {
                null
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = isAuthAdHost(request?.url?.host.orEmpty())

            override fun onPageFinished(view: WebView?, url: String?) {
                Log.d(DEBUG_TAG, "stage=AUTH_WEBVIEW_URL currentUrl=$url")
                view?.evaluateJavascript(AUTH_CLEANUP_SCRIPT, null)
                val path = url?.let { runCatching { it.toHttpUrl().encodedPath }.getOrNull() }
                if (url?.startsWith(baseUrl) == true && path != "/login") {
                    authExecutor.execute {
                        if (!sessionHandled && validateSession()) {
                            Handler(Looper.getMainLooper()).post {
                                if (!sessionHandled) {
                                    sessionHandled = true
                                    CookieManager.getInstance().flush()
                                    Log.d(DEBUG_TAG, "stage=AUTH_CONNECTED validated=true closingWebView=true")
                                    onValidated()
                                    dialog.dismiss()
                                }
                            }
                        }
                    }
                }
            }
        }
        dialog.setContentView(webView)
        dialog.setOnDismissListener { webView.destroy() }
        dialog.show()
        webView.loadUrl("$baseUrl/login")
    }

    private fun isAuthAdHost(host: String): Boolean = host == "platform.pubadx.one" ||
        host.endsWith(".pubadx.one") ||
        host == "static.cloudflareinsights.com" ||
        host == "googletagmanager.com" ||
        host.endsWith(".googletagmanager.com")

    private fun debugResponse(stage: String, response: okhttp3.Response) {
        val redirects = generateSequence(response.priorResponse) { it.priorResponse }.count()
        val clientCookies = client.cookieJar.loadForRequest(response.request.url).map { it.name }.distinct().sorted()
        val webCookies = CookieManager.getInstance().getCookie(response.request.url.toString())
            ?.split(';')
            ?.mapNotNull { it.substringBefore('=').trim().takeIf(String::isNotEmpty) }
            ?.distinct()
            ?.sorted()
            .orEmpty()
        Log.d(
            DEBUG_TAG,
            "stage=$stage url=${response.request.url} status=${response.code} " +
                "redirects=$redirects clientCookieNames=$clientCookies webCookieNames=$webCookies",
        )
    }

    // ======================== Popular =============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("projetos")
            .addQueryParameter("page", page.toString())
            .build()
        return client.get(url, rscHeaders).extractNextJs<MangasListDto>()!!.toMangasPage()
    }

    // ======================== Latest =============================

    override suspend fun getLatestUpdates(page: Int): MangasPage = client.get(baseUrl, rscHeaders).extractNextJs<LatestMangas>()!!.toMangasPage()

    // ======================== Search =============================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val searchHeaders = headers.newBuilder()
            .set("Next-Action", SEARCH_TOKEN)
            .build()
        val payload = listOf(query).toJsonRequestBody()
        val dto = client.post(baseUrl, searchHeaders, payload).extractNextJs<List<MangaDto>>() ?: emptyList()
        return MangasPage(dto.map(MangaDto::toSManga), false)
    }

    // ======================== Details + Chapters =============================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val detailsDeferred = async {
            if (fetchDetails) {
                val url = getMangaUrl(manga)
                val payload = url.toHttpUrl().pathSegments.toJsonRequestBody()
                val detailsHeaders = headers.newBuilder()
                    .set("Next-Action", DETAILS_TOKEN)
                    .build()
                client.post(url, detailsHeaders, payload).also { debugResponse("DETAILS", it) }
                    .extractNextJs<MangaDetailsDto>()!!.toSManga()
            } else {
                manga
            }
        }

        val chaptersDeferred = async {
            if (fetchChapters) {
                val url = getMangaUrl(manga)
                val payload = url.toHttpUrl().pathSegments.toJsonRequestBody()
                val chaptersHeaders = headers.newBuilder()
                    .set("Next-Action", CHAPTERS_TOKEN)
                    .build()
                val response = client.post(url, chaptersHeaders, payload)
                debugResponse("CHAPTERS", response)
                val pathSegment = url.substringAfter(baseUrl)
                response.extractNextJs<VolumeChapterDto>()!!.toChapterList(pathSegment)
            } else {
                chapters
            }
        }

        SMangaUpdate(detailsDeferred.await(), chaptersDeferred.await())
    }

    // ======================== Pages =============================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        if (!validateSession()) throw IOException("🔐 Login necessário")
        val storedChapterUrl = chapter.url
        val url = normalizeChapterUrl(getChapterUrl(chapter))
        val segments = url.toHttpUrl().pathSegments
        val payload = listOf(segments.first(), segments.last()).toJsonRequestBody()
        val requestHeaders = headers.newBuilder()
            .set("Next-Action", READER_TOKEN)
            .build()
        Log.d(DEBUG_TAG, "stage=PAGES_CURRENT_ACTION_REQUEST url=$url nextAction=$READER_TOKEN arguments=project:${segments.first()},chapter:${segments.last()}")
        val response = client.newCall(POST(url, requestHeaders, payload)).execute()
        return response.use {
            val body = it.body.string()
            Log.d(DEBUG_TAG, "stage=PAGES_CURRENT_ACTION_RESPONSE status=${it.code} contentType=${it.header("Content-Type")} bodyLength=${body.length} containsSupabase=${body.contains("supabase.aniargos.com", true)} containsPages=${body.contains("pages", true)} containsPhoto=${body.contains("photo", true)}")
            if (!it.isSuccessful) throw IOException("Falha ao carregar páginas: HTTP ${it.code}")
            val contentType = it.header("Content-Type").orEmpty()
            if (contentType.startsWith("application/json", ignoreCase = true) &&
                body.trim().let { value -> value.isEmpty() || value == "[]" || value == "{}" || value == "null" }
            ) {
                clearSession()
                throw IOException("🔐 Login necessário")
            }
            runCatching { body.extractNextJsRsc<PagesDto>()?.toPageList() }
                .getOrNull()
                ?.also { pages -> Log.d(DEBUG_TAG, "stage=PAGES_CURRENT_ACTION_RESPONSE pages=${pages.size}") }
                ?.takeIf { pages -> pages.isNotEmpty() }
                ?: throw IOException("Resposta do Reader sem páginas.")
        }
    }

    private fun normalizeChapterUrl(url: String): String {
        val parsed = url.toHttpUrl()
        val segments = parsed.pathSegments
        val chapterIndex = segments.indexOfLast { it.equals("capitulo", ignoreCase = true) }
        if (chapterIndex < 0 || chapterIndex == segments.lastIndex) return url
        val number = segments.last()
        val normalizedNumber = number.removeSuffix(".0")
        if (number == normalizedNumber) return url
        return parsed.newBuilder()
            .removePathSegment(segments.lastIndex)
            .addPathSegment(normalizedNumber)
            .build()
            .toString()
    }

    companion object {
        private const val SEARCH_TOKEN = "409ae74984efeccf922164e02e3bcd60d8b0107638"
        private const val CHAPTERS_TOKEN = "606c13e60309ce062fade63ac2f1cc68bbc5dc25f4"
        private const val DETAILS_TOKEN = "60e89cb5963d6bb1b61383872fbfb4cc2726925dd8"
        private const val DEBUG_TAG = "ARGOS_COMICS_DEBUG"
        private const val READER_TOKEN = "6062e8559136ee33cc337e5520fb09950c3dced65e"
        private val AUTH_CLEANUP_SCRIPT = """
            (() => {
              if (window.__argosAuthCleanupInstalled) return;
              window.__argosAuthCleanupInstalled = true;
              const removeAds = () => {
                document.querySelectorAll(
                  'iframe[src*="pubadx.one"], iframe[src*="googletagmanager.com"], [src*="platform.pubadx.one"], [class*="pubadx"], [id*="pubadx"], [data-ad-network="pubadx"], [data-ad-provider="pubadx"], [data-ad-network="PubADX"]'
                ).forEach((element) => element.remove());
                document.querySelectorAll('a[href*="pubadx.one"], [onclick*="pubadx.one"]').forEach((element) => element.remove());
              };
              removeAds();
              if (document.documentElement) {
                new MutationObserver(removeAds).observe(document.documentElement, { childList: true, subtree: true });
              }
              window.open = (url) => {
                if (!url) return null;
                try {
                  const target = new URL(url, location.href);
                  if (target.hostname === 'platform.pubadx.one' || target.hostname.endsWith('.pubadx.one')) return null;
                  location.href = target.href;
                  return window;
                } catch (_) {
                  return null;
                }
              };
              document.addEventListener('click', (event) => {
                const link = event.target.closest && event.target.closest('a[href]');
                if (!link) return;
                try {
                  const target = new URL(link.href, location.href);
                  if (target.hostname === 'platform.pubadx.one' || target.hostname.endsWith('.pubadx.one')) {
                    event.preventDefault();
                    event.stopImmediatePropagation();
                  }
                } catch (_) {}
              }, true);
            })();
        """.trimIndent()
    }
}
