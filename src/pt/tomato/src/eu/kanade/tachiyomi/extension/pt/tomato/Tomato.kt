package eu.kanade.tachiyomi.extension.pt.tomato

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
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
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.toJsonRequestBody
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Tomato :
    KeiSource(),
    ConfigurableSource {
    private val preferences: SharedPreferences by getPreferencesLazy()
    private val preferencesReady by lazy { migratePreferences() }
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val sessionLock = Any()
    private val serverConfigLock = Any()

    @Volatile private var configuredApiUrl = PROD_API_URL

    @Volatile private var officialAppVersion = COMPATIBLE_APP_VERSION

    @Volatile private var captchaRequired = true

    @Volatile private var serverConfigLoaded = false

    @Volatile private var validatedToken: String? = null

    override fun OkHttpClient.Builder.configureClient() = rateLimit(3, 1.seconds).addInterceptor { chain ->
        val request = chain.request().withOfficialClientHeaders(officialAppVersion)
        val response = chain.proceed(request)
        response.decodeContentEncoding()
    }

    override fun Headers.Builder.configureHeaders() = set("User-Agent", "tomato-android").set("Accept", "*/*")

    private val userToken: String?
        get() {
            preferencesReady
            return preferences.getString(PREF_TOKEN, null)?.trim()?.removePrefix("Bearer ")?.takeIf(String::isNotEmpty)
        }
    private val userName: String?
        get() {
            preferencesReady
            return preferences.getString(PREF_USER_NAME, null)?.trim()?.takeIf(String::isNotEmpty)
        }

    private fun apiHeaders(token: String = requireValidToken()) = headers.withBearer(token)

    private fun requireValidToken(): String {
        val token = userToken ?: error("Login necessário. Abra as configurações da extensão Tomato e entre com sua conta.")
        if (validatedToken == token) return token
        return synchronized(sessionLock) {
            if (validatedToken != token) check(validateSession(token)) { "Sua sessão da Tomato expirou. Entre novamente." }
            validatedToken = token
            token
        }
    }

    private fun markSessionValid(token: String) {
        validatedToken = token.trim().removePrefix("Bearer ").takeIf(String::isNotEmpty)
    }

    private fun migratePreferences() {
        val current = preferences.getString(PREF_TOKEN, null)?.trim()?.takeIf(String::isNotEmpty)
        val legacy = preferences.getString(LEGACY_TOKEN, null)?.trim()?.removePrefix("Bearer ")?.takeIf(String::isNotEmpty)
        preferences.edit().apply {
            if (current == null && legacy != null) putString(PREF_TOKEN, legacy)
            remove(LEGACY_TOKEN)
            remove(SAVED_EMAIL)
            remove(SAVED_PASSWORD)
            remove(SAVED_USERNAME)
            apply()
        }
    }

    private fun refreshServerConfig() {
        if (serverConfigLoaded) return
        synchronized(serverConfigLock) {
            if (serverConfigLoaded) return
            val response = client.newCall(POST("$PROD_API_URL/checkupdate/", headers.withNativeAuthHeaders(), CheckUpdateRequestDto(COMPATIBLE_APP_VERSION).toJsonRequestBody())).execute().use {
                it.requireSuccess().parseAs<CheckUpdateResponseDto>()
            }
            if (response.statusCode != 4) throw IOException("A Tomato não aceitou a configuração inicial.")
            configuredApiUrl = PROD_API_URL
            officialAppVersion = response.serverVersion?.takeIf(String::isNotEmpty) ?: COMPATIBLE_APP_VERSION
            captchaRequired = response.requireCaptcha ?: true
            serverConfigLoaded = true
        }
    }

    private fun validateSession(token: String): Boolean {
        refreshServerConfig()
        val result = client.newCall(POST("$configuredApiUrl/tokenlogin/", headers.withNativeAuthHeaders(), TokenLoginRequestDto(token, Auth.deviceFingerprint).toJsonRequestBody())).execute().use { response ->
            if (response.code == 401 || response.code == 403) null else response.requireSuccess().parseAs<TokenLoginResponseDto>()
        }
        if (result?.statusCode != 4) {
            clearSession()
            return false
        }
        result.userName?.let { preferences.edit().putString(PREF_USER_NAME, it).apply() }
        return true
    }

    private fun clearSession() {
        validatedToken = null
        preferences.edit().remove(PREF_TOKEN).remove(PREF_USER_NAME).apply()
    }

    private fun prepareAuthentication(context: Context, next: (Boolean) -> Unit) {
        Thread {
            val result = runCatching {
                refreshServerConfig()
                captchaRequired
            }
            handler.post { result.onSuccess(next).onFailure { Toast.makeText(context, "Não foi possível iniciar a autenticação: ${it.message}", Toast.LENGTH_LONG).show() } }
        }.start()
    }

    private fun performLogin(email: String, pass: String, captcha: String, context: Context, status: Preference) {
        val request = POST("$configuredApiUrl/login/", headers.withNativeAuthHeaders(), LoginRequestDto(email, pass, captcha, Auth.deviceFingerprint).toJsonRequestBody())
        client.newCall(request).enqueue(authCallback(context, status, false))
    }

    private fun performRegister(username: String, email: String, pass: String, captcha: String, context: Context, status: Preference) {
        val request = POST("$configuredApiUrl/register/", headers.withNativeAuthHeaders(), RegisterRequestDto(username, email, pass, captcha, Auth.deviceFingerprint).toJsonRequestBody())
        client.newCall(request).enqueue(authCallback(context, status, true))
    }

    private fun authCallback(context: Context, status: Preference, registering: Boolean) = object : okhttp3.Callback {
        override fun onFailure(call: okhttp3.Call, error: IOException) {
            handler.post { Toast.makeText(context, "Erro de conexão: ${error.message}", Toast.LENGTH_LONG).show() }
        }
        override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
            val result = response.use { runCatching { it.body.string().parseAs<AuthResponseDto>() }.getOrNull() }
            handler.post {
                if (result?.statusCode == 4 && !result.token.isNullOrBlank()) {
                    preferences.edit().putString(PREF_TOKEN, result.token).putString(PREF_USER_NAME, result.userName ?: if (registering) "Usuário" else "Usuário").apply()
                    markSessionValid(result.token)
                    status.summary = "Conectado como: ${result.userName ?: "Usuário"}"
                    Toast.makeText(context, if (registering) "Conta criada e conectada com sucesso!" else "Login realizado com sucesso!", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, result?.message ?: "Falha na autenticação (Código: ${result?.statusCode})", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun validateSavedSession(context: Context, status: Preference) {
        val token = userToken ?: return
        Thread {
            val result = runCatching { validateSession(token) }
            handler.post { result.onSuccess { if (it) status.summary = "Conectado como: ${userName ?: "Usuário"}" else status.summary = "Sessão expirada — faça login novamente" }.onFailure { status.summary = "Sessão expirada — faça login novamente" } }
        }.start()
    }

    override suspend fun getPopularManga(page: Int) = catalog(page, 2)
    override suspend fun getLatestUpdates(page: Int) = catalog(page, 1)
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (userToken == null) return loginRequiredPage()
        val token = requireValidToken()
        val tags = filters.filterIsInstance<CategoryFilter>().firstOrNull()?.selectedNames?.takeIf { it.isNotEmpty() }
        val result = client.post(SEARCH_URL, apiHeaders(token), SearchRequestDto(token, query.trim(), page - 1, tags).toJsonRequestBody()).parseAs<SearchResponseDto>().result.filter { it.type == "manga" }
        return MangasPage(result.map(SearchMangaDto::toSManga), result.size >= 50)
    }
    override suspend fun getMangaByUrl(url: okhttp3.HttpUrl): SManga? = url.pathSegments.takeIf { it.size == 3 && it[0] == "v2" && it[1] == "manga" }?.getOrNull(2)?.toLongOrNull()?.let { id -> fetchMangaUpdate(SManga.create().apply { this.url = mangaUrl(id) }, emptyList(), true, false).manga }
    override suspend fun fetchMangaUpdate(manga: SManga, chapters: List<SChapter>, fetchDetails: Boolean, fetchChapters: Boolean): SMangaUpdate {
        if (manga.url == LOGIN_REQUIRED_URL) return SMangaUpdate(manga, emptyList())
        val id = manga.url.substringAfterLast('/').toLongOrNull() ?: error("ID de mangá Tomato inválido")
        val token = requireValidToken()
        val details = client.post(DETAILS_URL, apiHeaders(token), MangaIdRequestDto(id, token).toJsonRequestBody()).parseAs<MangaDetailsResponseDto>().details
        val updated = manga.apply {
            if (fetchDetails) {
                title = details.name
                thumbnail_url = details.cover
                description = details.description
                genre = details.genre
                author = details.author
                status = details.status.toStatus()
                initialized = true
            }
        }
        val result = if (fetchChapters) client.get("$API_URL/manga/chapters/query/$id", apiHeaders(token)).parseAs<ChaptersResponseDto>().data.map(ChapterDto::toSChapter) else chapters
        return SMangaUpdate(updated, result)
    }
    override suspend fun getPageList(chapter: SChapter): List<Page> = client.get("$API_URL/manga/pages/query/${chapter.url.substringAfterLast('/')}", apiHeaders()).parseAs<PagesResponseDto>().data.mapIndexed { i, item -> Page(i, imageUrl = item.pageUrl) }
    override fun imageRequest(page: Page): Request = super.imageRequest(page).newBuilder().header("Accept", "image/webp,image/png,image/jpeg,*/*;q=0.8").build()
    override val supportsFilterFetching = true
    override suspend fun fetchFilterData(): JsonElement = client.get("$API_URL/v2/content/categories", apiHeaders()).parseAs<CategoriesResponseDto>().categories.map(CategoryDto::name).toJsonElement()
    override fun getFilterList(data: JsonElement?) = getFilters(data)
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        preferencesReady
        val context = screen.context
        val status = createPreference(context).apply {
            key = PREF_ACCOUNT_STATUS
            title = "Status da Conta"
            summary = if (userToken != null) "Conectado como: ${userName ?: "Usuário"}" else "Não conectado — use a ação de login abaixo"
            setEnabled(false)
        }.also(screen::addPreference)
        EditTextPreference(context).apply {
            key = PREF_TOKEN
            title = "Token de Autenticação (Manual)"
            summary = "Toque para colar um token Bearer diretamente"
            setOnPreferenceChangeListener { _, value ->
                val token = (value as? String)?.trim()?.removePrefix("Bearer ")?.takeIf(String::isNotEmpty)
                validatedToken = null
                if (token != null) {
                    preferences.edit().putString(PREF_TOKEN, token).apply()
                    validateSavedSession(context, status)
                } else {
                    clearSession()
                }
                false
            }
        }.also(screen::addPreference)
        if (userToken != null) validateSavedSession(context, status)
        ListPreference(context).apply {
            key = PREF_AUTH_ACTION
            title = "Ação"
            summary = "Toque para escolher uma ação"
            entries = arrayOf("<Selecione uma Ação>", "Fazer Login", "Registrar nova Conta", "Sair da conta")
            entryValues = arrayOf("none", "login", "register", "logout")
            setDefaultValue("none")
            setOnPreferenceChangeListener { _, value ->
                when (value as? String) {
                    "login" -> Auth.showLoginInputDialog(context) { email, pass -> prepareAuthentication(context) { captcha -> if (captcha) Auth.showCaptchaDialog(context, handler) { performLogin(email, pass, it, context, status) } else performLogin(email, pass, "", context, status) } }
                    "register" -> Auth.showRegisterInputDialog(context) { username, email, pass -> prepareAuthentication(context) { captcha -> if (captcha) Auth.showCaptchaDialog(context, handler) { performRegister(username, email, pass, it, context, status) } else performRegister(username, email, pass, "", context, status) } }
                    "logout" -> {
                        clearSession()
                        status.summary = "Não conectado — use a ação de login abaixo"
                        Toast.makeText(context, "Desconectado com sucesso", Toast.LENGTH_SHORT).show()
                    }
                }
                false
            }
        }.also(screen::addPreference)
    }
    override fun getMangaUrl(manga: SManga) = "$baseUrl${manga.url}"
    override fun getChapterUrl(chapter: SChapter) = "$API_URL/manga/pages/query/${chapter.url.substringAfterLast('/')}"
    private suspend fun catalog(page: Int, type: Int): MangasPage {
        if (userToken == null) return loginRequiredPage()
        if (page != 1) return MangasPage(emptyList(), false)
        val section = client.get("$API_URL/v2/manga/feed", apiHeaders()).parseAs<FeedResponseDto>().data.firstOrNull { it.type == type } ?: error("Seção manga não encontrada")
        return MangasPage(section.data.map(FeedMangaDto::toSManga), false)
    }
    private fun mangaUrl(id: Long) = "/v2/manga/$id"
    private fun loginRequiredPage() = MangasPage(
        listOf(
            SManga.create().apply {
                url = LOGIN_REQUIRED_URL
                title = "🔐 Login necessário"
                description = "Entre na sua conta Tomato pelas configurações da extensão para acessar o catálogo."
            },
        ),
        false,
    )
    private fun createPreference(context: Context): Preference = runCatching {
        Preference::class.java.getConstructor(Context::class.java).newInstance(context)
    }.getOrElse { Preference() }
    private companion object {
        const val COMPATIBLE_APP_VERSION = "1.4.3"
        const val PREF_TOKEN = "pref_user_token"
        const val PREF_USER_NAME = "pref_user_name"
        const val PREF_ACCOUNT_STATUS = "pref_account_status"
        const val PREF_AUTH_ACTION = "pref_auth_action"
        const val LEGACY_TOKEN = "tomato_official_session_token_v1"
        const val SAVED_EMAIL = "pref_saved_email"
        const val SAVED_PASSWORD = "pref_saved_password"
        const val SAVED_USERNAME = "pref_saved_username"
        const val API_URL = "https://edge.betomato.com"
        const val SEARCH_URL = "$API_URL/v2/content/search"
        const val DETAILS_URL = "$API_URL/manga/query/"
        const val LOGIN_REQUIRED_URL = "/login-required"
    }
}
private fun String?.toStatus() = when (this?.lowercase()) {
    "ongoing" -> SManga.ONGOING
    "completed" -> SManga.COMPLETED
    "hiatus" -> SManga.ON_HIATUS
    "cancelled" -> SManga.CANCELLED
    else -> SManga.UNKNOWN
}
