package eu.kanade.tachiyomi.extension.pt.shiraiscans

import android.app.Dialog
import android.content.Context
import android.util.Base64
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
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class ShiraiScans :
    HttpSource(),
    ConfigurableSource {

    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .addInterceptor { chain ->
            CookieManager.getInstance().flush()
            val cookie = CookieManager.getInstance().getCookie(baseUrl)
            val request = if (cookie.isNullOrBlank()) {
                chain.request()
            } else {
                chain.request().newBuilder().header("Cookie", cookie).build()
            }
            val response = chain.proceed(request)
            response
        }
        .addInterceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()

            // 1. Handle image format fallbacks
            if (url.endsWith(IMAGE_SUFFIX)) {
                val imageBaseUrl = url.removeSuffix(IMAGE_SUFFIX)
                var lastResponse: Response? = null

                for (ext in EXTENSIONS_FALLBACK) {
                    val newUrl = imageBaseUrl + ext
                    val newRequest = request.newBuilder().url(newUrl).build()

                    try {
                        val response = chain.proceed(newRequest)
                        if (response.isSuccessful) {
                            return@addInterceptor response
                        }
                        lastResponse?.close()
                        lastResponse = response
                    } catch (e: Exception) {
                        lastResponse?.close()
                        lastResponse = null
                        if (ext == EXTENSIONS_FALLBACK.last()) throw e
                    }
                }
                return@addInterceptor lastResponse ?: throw IOException("Failed to fetch image")
            }

            // 2. Decode the Base64 Obfuscated HTML
            val response = chain.proceed(request)
            val contentType = response.body.contentType()

            if (contentType?.type == "text" && contentType.subtype.contains("html")) {
                val html = response.body.string()
                val match = B64_REGEX.find(html)

                if (match != null) {
                    val b64Str = match.groupValues[1]
                    val decodedHtml = try {
                        String(Base64.decode(b64Str, Base64.DEFAULT))
                    } catch (e: Exception) {
                        html
                    }
                    val newBody = decodedHtml.toResponseBody(contentType)
                    return@addInterceptor response.newBuilder().body(newBody).build()
                }

                val newBody = html.toResponseBody(contentType)
                return@addInterceptor response.newBuilder().body(newBody).build()
            }

            response
        }
        .build()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val preferences = screen.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        lateinit var action: Preference
        lateinit var status: Preference
        lateinit var logout: Preference

        fun updateUi() {
            val connected = preferences.getBoolean(PREF_CONNECTED, false)
            action.title = if (connected) "Abrir Shirai Scans" else "Entrar na conta"
            action.summary = if (connected) "Abrir o site da Shirai Scans." else "Abrir o login oficial da Shirai Scans."
            status.summary = if (connected) "Conectado" else "Não conectado"
            setVisible(logout, connected)
        }

        action = newPreference(screen.context).apply {
            setOnPreferenceClickListener {
                showLoginWebView(screen.context) { updateUi() }
                true
            }
        }
        screen.addPreference(action)

        status = newPreference(screen.context).apply {
            title = "Sessão da Shirai Scans"
            setSelectable(this, false)
        }
        screen.addPreference(status)

        logout = newPreference(screen.context).apply {
            title = "Sair da conta"
            summary = "Remover a sessão da Shirai Scans neste aplicativo."
            setOnPreferenceClickListener {
                clearSession(screen.context)
                updateUi()
                true
            }
        }
        screen.addPreference(logout)

        updateUi()
    }

    private fun newPreference(context: android.content.Context): Preference = runCatching {
        Preference::class.java.getConstructor(android.content.Context::class.java).newInstance(context)
    }.getOrElse { Preference() }

    private fun clearSession(context: Context) {
        CookieManager.getInstance().getCookie(baseUrl).orEmpty().split(';')
            .mapNotNull { it.trim().substringBefore('=').takeIf(String::isNotBlank) }
            .distinct().forEach { name ->
                CookieManager.getInstance().setCookie(baseUrl, "$name=; Max-Age=0; Path=/")
                CookieManager.getInstance().setCookie(baseUrl, "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/")
            }
        CookieManager.getInstance().flush()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(PREF_CONNECTED, false)
            .apply()
    }

    private fun setVisible(preference: Preference, visible: Boolean) {
        runCatching {
            preference::class.java.methods.firstOrNull { it.name == "setVisible" }
                ?.invoke(preference, visible)
        }
    }

    private fun setSelectable(preference: Preference, selectable: Boolean) {
        runCatching {
            preference::class.java.methods.firstOrNull { it.name == "setSelectable" }
                ?.invoke(preference, selectable)
        }
    }

    private fun showLoginWebView(context: android.content.Context, onConnected: () -> Unit) {
        val dialog = Dialog(context)
        val webView = WebView(context)
        var firstPage = true
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                CookieManager.getInstance().flush()
                if (firstPage) {
                    firstPage = false
                    view?.evaluateJavascript("if (typeof toggleLoginPopup === 'function') { toggleLoginPopup(); }", null)
                }
                view?.evaluateJavascript(
                    "Boolean(document.querySelector(\"[href*='logout'], .btn-logout, .profile-trigger, .profile-menu\"))",
                ) { result ->
                    if (result == "true") {
                        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                            .putBoolean(PREF_CONNECTED, true)
                            .apply()
                        onConnected()
                        dialog.dismiss()
                    }
                }
            }
        }
        dialog.setContentView(webView)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.setOnShowListener {
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        dialog.setOnDismissListener {
            webView.destroy()
        }
        dialog.show()
        webView.loadUrl(baseUrl)
    }

    private val dateFormat by lazy {
        SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR"))
    }

    // ============================== Popular ==============================

    override fun popularMangaRequest(page: Int): Request {
        val offset = (page - 1) * 15
        return GET("$baseUrl/biblioteca.php?ajax=true&genero=todos&q=&offset=$offset", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val cards = document.select(".library-card")

        val mangas = cards.map {
            SManga.create().apply {
                val onClick = it.attr("onclick")
                url = "/" + onClick.substringAfter("href='").substringBefore("'")
                title = it.selectFirst(".library-title")!!.text()
                thumbnail_url = it.selectFirst(".library-cover")?.absUrl("src")
            }
        }

        return MangasPage(mangas, cards.size == 15)
    }

    // ============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl, headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()

        val mangas = document.select("section.atualizacoes .manga-card, section#secao-atualizacoes .manga-card").map {
            SManga.create().apply {
                val onClick = it.attr("onclick")
                url = "/" + onClick.substringAfter("href='").substringBefore("'")
                title = it.selectFirst(".manga-title")!!.text()
                thumbnail_url = it.selectFirst(".manga-cover")?.absUrl("src")
            }
        }

        return MangasPage(mangas, false)
    }

    // ============================== Search ===============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val genre = filters.firstInstanceOrNull<GenreFilter>()?.toUriPart() ?: "todos"
        val offset = (page - 1) * 15

        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("biblioteca.php")
            addQueryParameter("ajax", "true")
            addQueryParameter("genero", genre)
            addQueryParameter("q", query)
            addQueryParameter("offset", offset.toString())
        }.build()

        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // ============================== Details ==============================

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()

        return SManga.create().apply {
            title = document.selectFirst(".obra-titulo")!!.text()
            thumbnail_url = document.selectFirst(".obra-capa-grande")?.absUrl("src")
            author = document.selectFirst(".info-linha:contains(Autor) span:last-child")?.text()?.takeIf { it != "?" }
            artist = document.selectFirst(".info-linha:contains(Artista) span:last-child")?.text()?.takeIf { it != "?" }
            description = document.selectFirst(".obra-sinopse")?.text()
            genre = document.select(".obra-generos .genero-badge").joinToString { it.text().removePrefix("#") }

            val statusText = document.selectFirst(".info-linha:contains(Status) span:last-child")?.text()
            status = when {
                statusText == null -> SManga.UNKNOWN
                statusText.contains("Lançamento", true) -> SManga.ONGOING
                statusText.contains("Completo", true) -> SManga.COMPLETED
                statusText.contains("Hiato", true) -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
        }
    }

    // ============================= Chapters ==============================

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()

        return document.select(".lista-capitulos .capitulo-item").map {
            SChapter.create().apply {
                url = "/" + it.attr("href")
                name = it.selectFirst(".capitulo-title")!!.text().replace("NOVO", "").trim()
                date_upload = it.selectFirst(".capitulo-date")?.text()?.let { dateStr ->
                    dateFormat.tryParse(dateStr)
                } ?: 0L
            }
        }
    }

    // =============================== Pages ===============================

    override fun pageListParse(response: Response): List<Page> {
        val body = response.body.string()
        val document = org.jsoup.Jsoup.parse(body)
        val script = document.selectFirst("script:containsData(const pagesData =)")?.data()
        if (script == null) {
            if (body.contains("Login necessário", true) || body.contains("Conteúdo Protegido", true)) {
                throw Exception("Login necessário")
            }
            throw Exception("pagesData não encontrado")
        }
        val pages = parsePagesData(script)
            .sortedWith(compareBy({ it.page ?: Int.MAX_VALUE }, { it.index ?: Int.MAX_VALUE }))
        if (pages.isEmpty()) throw Exception("pagesData vazio")

        return pages.mapIndexed { i, page ->
            Page(i, imageUrl = page.url.replace("&amp;", "&"))
        }
    }

    private fun parsePagesData(script: String): List<Dto> = runCatching {
        val jsonString = script.substringAfter("const pagesData = ").substringBefore(";")
        jsonString.parseAs<List<Dto>>()
    }.getOrDefault(emptyList())

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================== Filters ==============================

    override fun getFilterList() = FilterList(
        GenreFilter(),
    )

    companion object {
        private const val PREFS_NAME = "shiraiscans_ui"
        private const val PREF_CONNECTED = "connected"
        private const val IMAGE_SUFFIX = ".shirai"
        private val EXTENSIONS_FALLBACK = listOf(".webp", ".jpg", ".png")
        private val B64_REGEX = """var\s+b64\s*=\s*['"]([A-Za-z0-9+/=\s]+)['"]""".toRegex()
    }
}
