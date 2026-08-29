package eu.kanade.tachiyomi.extension.pt.kuromangas

import android.util.Log
import android.webkit.CookieManager
import androidx.preference.EditTextPreference
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
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import kotlinx.serialization.json.JsonObject
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Source
abstract class KuroMangas :
    HttpSource(),
    ConfigurableSource {

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    private val apiUrl = "$baseUrl/api"

    private val cdnUrl = "https://cdn.kuromangas.com"

    private val authLock = Any()

    @Volatile
    private var authSession: AuthSession? = null

    private val authClient by lazy {
        network.client.newBuilder()
            .cookieJar(CookieJar.NO_COOKIES)
            .build()
    }

    private val decryptor by lazy {
        KuroMangasDecryptor(baseUrl, authClient, ::authenticateRequest, ::relogin)
    }

    override val client: OkHttpClient by lazy {

        authClient.newBuilder()
            .apply {
                addInterceptor { chain ->
                    val session = getValidSession() ?: throw IOException(LOGIN_REQUIRED_MESSAGE)
                    return@addInterceptor chain.proceed(chain.request().withAuth(session))
                }

                addInterceptor(decryptor.vSecureInterceptor())
            }
            .rateLimit(2)
            .build()
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Accept", "application/json, text/plain, */*")
        .add("Accept-Language", "pt-BR,pt;q=0.8,en-US;q=0.5,en;q=0.3")
        .add("Referer", "$baseUrl/catalogo")
        .add("Sec-Fetch-Dest", "empty")
        .add("Sec-Fetch-Mode", "cors")
        .add("Sec-Fetch-Site", "same-origin")

    private val dateFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    // ============================= Popular ================================

    override fun popularMangaRequest(page: Int): Request {
        val url = "$apiUrl/mangas".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", PAGE_LIMIT.toString())
            .addQueryParameter("sort", "view_count")
            .addQueryParameter("order", "DESC")
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val result = response.parseAs<MangaListResponse>()
        val mangas = result.data.map { it.toSManga(cdnUrl) }
        return MangasPage(mangas, result.pagination.hasNextPage())
    }

    // ============================= Latest =================================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$apiUrl/chapters/recent".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", PAGE_LIMIT.toString())
            .addQueryParameter("days", "30")
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val result = response.parseAs<LatestResponse>()
        val mangas = result.data.map { it.toSManga(cdnUrl) }
        return MangasPage(mangas, result.pagination.hasNextPage())
    }

    // ============================= Search =================================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$apiUrl/mangas".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", PAGE_LIMIT.toString())

        if (query.isNotBlank()) {
            url.addQueryParameter("search", query)
        }

        filters.filterIsInstance<SortFilter>().firstOrNull()?.let { filter ->
            url.addQueryParameter("sort", filter.selectedSort)
            url.addQueryParameter("order", filter.selectedOrder)
        } ?: run {
            url.addQueryParameter("sort", "created_at")
            url.addQueryParameter("order", "DESC")
        }

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val result = response.parseAs<MangaListResponse>()
        val mangas = result.data.map { it.toSManga(cdnUrl) }
        return MangasPage(mangas, result.pagination.hasNextPage())
    }

    // ============================= Details ================================

    override fun mangaDetailsRequest(manga: SManga): Request {
        val mangaId = manga.url.substringAfterLast("/")
        return GET("$apiUrl/mangas/$mangaId", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val result = response.parseAs<MangaDetailsResponse>()
        return result.manga.toSManga(cdnUrl)
    }

    // ============================= Chapters ===============================

    override fun chapterListRequest(manga: SManga): Request {
        val mangaId = manga.url.substringAfterLast("/")
        return GET("$apiUrl/mangas/$mangaId", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val result = response.parseAs<MangaDetailsResponse>()
        val mangaId = result.manga.id
        return result.chapters
            .map { it.toSChapter(mangaId, dateFormat) }
            .sortedByDescending { it.chapter_number }
    }

    // ============================= Pages ==================================

    override fun pageListRequest(chapter: SChapter): Request {
        val chapterId = chapter.url.substringAfterLast("/")
        return GET("$apiUrl/chapters/$chapterId", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val chapterResponse = response.parseAs<ChapterPagesResponse>()
        return chapterResponse.pages.mapIndexed { index, pageUrl ->
            val fixedUrl = pageUrl.replaceFirst("^/uploads/".toRegex(), "/")
            val imageUrl = if (fixedUrl.startsWith("http")) fixedUrl else "$cdnUrl$fixedUrl"
            Page(index, imageUrl = imageUrl)
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request {
        val newHeaders = headersBuilder()
            .set("Referer", "$baseUrl/")
            .build()
        return GET(page.imageUrl!!, newHeaders)
    }

    // ============================= Utils ==================================

    override fun getMangaUrl(manga: SManga): String {
        val mangaId = manga.url.substringAfterLast("/")
        return "$baseUrl/manga/$mangaId"
    }

    override fun getChapterUrl(chapter: SChapter): String {
        // chapter.url format: /chapter/{mangaId}/{chapterId}
        val parts = chapter.url.removePrefix("/chapter/").split("/")
        val mangaId = parts.getOrNull(0) ?: ""
        val chapterId = parts.getOrNull(1) ?: ""
        return "$baseUrl/reader/$mangaId/$chapterId"
    }

    // ============================= Auth ===================================

    private fun getValidSession(): AuthSession? = synchronized(authLock) {
        val webViewSession = getWebViewSession()
        if (webViewSession != null) {
            if (validateSession(webViewSession)) {
                authSession = webViewSession
                Log.d(LOG_TAG, "KURO_AUTH_SOURCE=webview")
                return@synchronized webViewSession
            }
            removeWebViewSession()
            authSession = null
        }

        loginWithPreferences()?.also {
            authSession = it
            Log.d(LOG_TAG, "KURO_AUTH_SOURCE=password")
            return@synchronized it
        }

        Log.d(LOG_TAG, "KURO_AUTH_SOURCE=none")
        null
    }

    private fun relogin(): Boolean = synchronized(authLock) {
        authSession = null
        getValidSession() != null
    }

    private fun validateSession(session: AuthSession): Boolean {
        val request = GET("$apiUrl/users/me/profile", headers).withAuth(session)
        return authClient.newCall(request).execute().use { response ->
            Log.d(LOG_TAG, "KURO_AUTH_CHECK_STATUS=${response.code}")
            when {
                response.isSuccessful -> runCatching {
                    val body = response.parseAs<JsonObject>()
                    "_v_secure" in body || "profile" in body
                }.getOrDefault(false)
                response.code == 401 || response.code == 403 -> false
                else -> throw IOException("Falha ao validar a sessão da KuroMangas (${response.code}).")
            }
        }
    }

    private fun getWebViewSession(): AuthSession? {
        val cookieManager = CookieManager.getInstance()
        cookieManager.flush()

        val cookieHeaders = listOf("https://kuromangas.com", baseUrl)
            .distinct()
            .asSequence()
            .mapNotNull(cookieManager::getCookie)
        val sessionCookie = cookieHeaders.mapNotNull { extractCookie(it, SESSION_COOKIE) }.firstOrNull()
        val clientToken = cookieHeaders.mapNotNull { extractCookie(it, CLIENT_TOKEN_COOKIE) }.firstOrNull()
        val session = if (sessionCookie != null && clientToken != null) {
            AuthSession(sessionCookie, clientToken)
        } else {
            null
        }
        Log.d(LOG_TAG, "KURO_WEBVIEW_COOKIE_FOUND=${session != null}")
        return session
    }

    private fun extractCookie(cookieHeader: String, name: String): String? = cookieHeader
        .split(';')
        .asSequence()
        .map(String::trim)
        .firstOrNull { it.substringBefore('=') == name }
        ?.substringAfter('=', "")
        ?.takeIf(String::isNotEmpty)

    // Implicit set-cookie: kuro_session + _kn
    private fun loginWithPreferences(): AuthSession? {
        val email = preferences.getString(PREF_EMAIL, "") ?: ""
        val password = preferences.getString(PREF_PASSWORD, "") ?: ""
        if (email.isEmpty() || password.isEmpty()) return null

        val requestBody = LoginRequestDto(email, password, rememberMe = true).toJsonRequestBody()
        val request = POST("$apiUrl/auth/login", headers, requestBody)
        val newSession = runCatching {
            authClient.newCall(request).execute().use { response ->
                val responseCookies = response.headers("Set-Cookie")
                    .mapNotNull { Cookie.parse(response.request.url, it) }
                val sessionCookie = responseCookies.firstOrNull { it.name == SESSION_COOKIE && it.value.isNotEmpty() }?.value
                val clientToken = responseCookies.firstOrNull { it.name == CLIENT_TOKEN_COOKIE && it.value.isNotEmpty() }?.value
                val hasJsonBody = runCatching {
                    response.parseAs<JsonObject>()
                    true
                }.getOrDefault(false)
                if (
                    response.isSuccessful &&
                    hasJsonBody &&
                    sessionCookie != null &&
                    clientToken != null
                ) {
                    AuthSession(sessionCookie, clientToken)
                } else {
                    null
                }
            }
        }.getOrNull() ?: return null

        if (!validateSession(newSession)) return null

        CookieManager.getInstance().apply {
            setCookie(baseUrl, "$SESSION_COOKIE=${newSession.sessionCookie}; Path=/; Secure; HttpOnly")
            setCookie(baseUrl, "$CLIENT_TOKEN_COOKIE=${newSession.clientToken}; Path=/; Secure")
            flush()
        }
        return newSession
    }

    private fun removeWebViewSession() {
        CookieManager.getInstance().apply {
            setCookie(baseUrl, "$SESSION_COOKIE=; Max-Age=0; Path=/; Secure")
            setCookie(baseUrl, "$SESSION_COOKIE=; Max-Age=0; Domain=${baseUrl.toHttpUrl().host}; Path=/; Secure")
            setCookie(baseUrl, "$CLIENT_TOKEN_COOKIE=; Max-Age=0; Path=/; Secure")
            setCookie(baseUrl, "$CLIENT_TOKEN_COOKIE=; Max-Age=0; Domain=${baseUrl.toHttpUrl().host}; Path=/; Secure")
            flush()
        }
    }

    private fun authenticateRequest(request: Request): Request = authSession?.let(request::withAuth) ?: request

    // ============================= Preferences ============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val warning = "⚠️ Os dados inseridos nesta seção serão usados somente para realizar o login na fonte"
        val message = "Insira %s para prosseguir com o acesso aos recursos disponíveis na fonte"

        EditTextPreference(screen.context).apply {
            key = PREF_EMAIL
            title = "📧 Email"
            summary = "Email de acesso"
            dialogMessage = buildString {
                appendLine(message.format("seu email"))
                append("\n$warning")
            }
            setDefaultValue("")
        }.let(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_PASSWORD
            title = "🔑 Senha"
            summary = "Senha de acesso"
            dialogMessage = buildString {
                appendLine(message.format("sua senha"))
                append("\n$warning")
            }
            setDefaultValue("")
        }.let(screen::addPreference)
    }

    override fun getFilterList() = getFilters()

    companion object {
        private const val PAGE_LIMIT = 24
        private const val API_HOST = "beta.kuromangas.com"
        private const val PREF_EMAIL = "kuromangas_email"
        private const val PREF_PASSWORD = "kuromangas_password"
        private const val SESSION_COOKIE = "kuro_session"
        private const val CLIENT_TOKEN_COOKIE = "_kn"
        private const val LOG_TAG = "KuroMangas"
        private const val LOGIN_REQUIRED_MESSAGE = "Faça login no WebView ou insira email e senha nas configurações e tente novamente."
    }
}

private class AuthSession(
    val sessionCookie: String,
    val clientToken: String,
)

private fun Request.withAuth(session: AuthSession): Request {
    val cookies = header("Cookie").orEmpty()
        .split(';')
        .map(String::trim)
        .filter {
            it.isNotEmpty() && it.substringBefore('=') !in setOf("kuro_session", "_kn")
        }
        .plus("kuro_session=${session.sessionCookie}")
        .plus("_kn=${session.clientToken}")
        .joinToString("; ")
    return newBuilder()
        .header("Cookie", cookies)
        .header("X-Client-Token", session.clientToken)
        .build()
}
