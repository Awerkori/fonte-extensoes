package eu.kanade.tachiyomi.extension.pt.inkscan

import android.util.Log
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

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor { chain -> auth.intercept(chain) }
        .rateLimit(3, 1.seconds) { it.host == apiHost }
        .build()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        preferences.edit()
            .remove(LEGACY_PREF_EMAIL)
            .remove(LEGACY_PREF_PASSWORD)
            .apply()
        Preference().apply {
            title = "Sessão da Ink Scan"
            summary = if (auth.hasSession()) "Conectado" else "Não conectado — faça login pelo WebView"
        }.let(screen::addPreference)

        Preference().apply {
            title = "Limpar sessão da Ink Scan"
            summary = "Remove os tokens salvos nesta extensão"
            setOnPreferenceClickListener {
                preferences.edit()
                    .remove(PREF_ACCESS_TOKEN)
                    .remove(PREF_REFRESH_TOKEN)
                    .remove(PREF_TOKEN_EXPIRES)
                    .apply()
                Log.d(LOG_TAG, "session cleared")
                true
            }
        }.let(screen::addPreference)
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

    override fun popularMangaRequest(page: Int): Request = worksRequest(page, POPULAR_SORT, FilterList())

    override fun popularMangaParse(response: Response): MangasPage = response.toWorksPage()

    // ============================= Latest =================================

    override fun latestUpdatesRequest(page: Int): Request = worksRequest(page, LATEST_SORT, FilterList())

    override fun latestUpdatesParse(response: Response): MangasPage = response.toWorksPage()

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = Observable.fromCallable {
        client.newCall(latestChaptersRequest()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Falha ao carregar atualizações: HTTP ${response.code}")
            }

            val latest = response.parseAs<List<LatestChapterDto>>()
                .asSequence()
                .filter { it.work != null }
                .distinctBy { it.work!!.id() }
                .map { it.work!!.toSManga() }
                .toList()
            Log.d(LOG_TAG, "latest records=${latest.size}")
            val offset = (page - 1) * PAGE_SIZE
            MangasPage(latest.drop(offset).take(PAGE_SIZE), latest.size > offset + PAGE_SIZE)
        }
    }

    // ============================= Search =================================

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        if (query.isBlank()) {
            return super.fetchSearchManga(page, query, filters)
        }

        return Observable.fromCallable {
            val ids = client.newCall(searchIdsRequest(query)).execute().use { response ->
                if (!auth.hasSession()) throw IOException(loginMessage())
                response.parseAs<List<SearchResultDto>>().also { Log.d(LOG_TAG, "search records=${it.size}") }.map { it.id() }
            }

            if (ids.isEmpty()) {
                return@fromCallable MangasPage(emptyList(), false)
            }

            val works = client.newCall(worksByIdsRequest(ids, filters)).execute().use { response ->
                response.parseAs<List<WorkDto>>()
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

    override fun mangaDetailsRequest(manga: SManga): Request {
        val url = "$apiUrl/rest/v1/obras".toHttpUrl().newBuilder()
            .addQueryParameter("select", DETAILS_SELECT)
            .addQueryParameter("id", "eq.${manga.workId()}")
            .build()

        return GET(url, restHeaders)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        if (!auth.hasSession()) throw IOException(loginMessage())
        return response.parseAs<List<WorkDto>>().firstOrNull()?.toSManga(initialized = true)
            ?: throw IOException("A resposta da Ink Scan não contém os detalhes da obra.")
    }

    // ============================= Chapters ===============================

    override fun chapterListRequest(manga: SManga): Request {
        val url = "$apiUrl/rest/v1/capitulos".toHttpUrl().newBuilder()
            .addQueryParameter("select", CHAPTERS_SELECT)
            .addQueryParameter("obra_id", "eq.${manga.workId()}")
            .addQueryParameter("order", "numero.asc")
            .build()

        return GET(url, restHeaders)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val workId = response.request.url.queryParameter("obra_id")?.removePrefix("eq.").orEmpty()
        return response.parseAs<List<ChapterDto>>()
            .map { it.toSChapter(workId) }
            .sortedWith(compareByDescending<SChapter> { it.chapter_number }.thenByDescending { it.date_upload })
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable {
        val chapters = buildList {
            var offset = 0
            while (true) {
                val response = client.newCall(chapterListRequest(manga, offset)).execute()
                val page = response.use {
                    if (!it.isSuccessful) {
                        throw IOException("Falha ao carregar capítulos: HTTP ${it.code}")
                    }
                    it.parseAs<List<ChapterDto>>()
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

    // ============================= Pages ==================================

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = Observable.fromCallable {
        val chapterUrl = "$baseUrl${chapter.url}".toHttpUrl()
        val workId = chapterUrl.pathSegments[1]
        val chapterId = chapterUrl.queryParameter("id") ?: throw IOException("ID do capitulo ausente")

        val folder = client.newCall(folderRequest(workId)).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Falha ao carregar a obra: HTTP ${response.code}")
            }
            response.parseAs<List<FolderDto>>().first()
        }

        client.newCall(chapterPagesRequest(chapterId)).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Falha ao carregar as páginas: HTTP ${response.code}")
            }
            response.parseAs<ChapterPagesDto>().toPageList(folder)
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
        val url = "$apiUrl/rest/v1/obras".toHttpUrl().newBuilder()
            .addQueryParameter("select", CATALOG_SELECT)
            .addQueryParameter("id", "in.(${ids.joinToString()})")
            .addQueryParameter("or", "(is_acervo_b.is.null,is_acervo_b.eq.false)")

        url.applyFilters(filters)

        return GET(url.build(), restHeaders)
    }

    private fun searchIdsRequest(query: String): Request {
        val payload = SearchRequestDto(
            searchTerm = query.trim(),
            maxResults = SEARCH_LIMIT,
            archiveBOnly = false,
        ).toJsonRequestBody()

        return POST("$apiUrl/rest/v1/rpc/fuzzy_search_obras", rpcHeaders, payload)
    }

    private fun folderRequest(workId: String): Request {
        val url = "$apiUrl/rest/v1/obras".toHttpUrl().newBuilder()
            .addQueryParameter("select", FOLDER_SELECT)
            .addQueryParameter("id", "eq.$workId")
            .build()

        return GET(url, restHeaders)
    }

    private fun chapterPagesRequest(chapterId: String): Request {
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
        if (!auth.hasSession()) throw IOException(loginMessage())
        val page = request.url.queryParameter("offset")?.toIntOrNull()?.div(PAGE_SIZE)?.plus(1) ?: 1
        val works = parseAs<List<WorkDto>>()
        Log.d(LOG_TAG, "catalog records=${works.size}")
        val total = header("content-range")?.substringAfter("/")?.toIntOrNull()
        val hasNextPage = total?.let { page * PAGE_SIZE < it } ?: (works.size == PAGE_SIZE)

        return MangasPage(works.map { it.toSManga() }, hasNextPage)
    }

    private fun loginMessage() = "É necessário entrar na sua conta Ink Scan pelo WebView antes de carregar o catálogo."

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
        private const val WEBVIEW_CHECK_INTERVAL = 60_000L
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
        private val STORAGE_KEYS = listOf(
            "sb-delicate-hill-05c1inkscan-auth-token",
            "sb-sjybfvyoznmtxmjhycoj-auth-token",
        )
        private const val LOG_TAG = "InkScanAuth"
    }

    private inner class InkScanAuth {
        private val authClient = network.client
        private val lock = Any()
        private var lastWebViewCheck = 0L

        fun intercept(chain: okhttp3.Interceptor.Chain): Response {
            val token = synchronized(lock) { validAccessToken() }
            val request = chain.request().newBuilder().apply {
                if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
            }.build()
            Log.d(LOG_TAG, "bearer applied=${!token.isNullOrBlank()}")
            val response = chain.proceed(request)
            Log.d(LOG_TAG, "api http=${response.code}")
            if (response.code != 401) return response

            response.close()
            val refreshed = synchronized(lock) {
                clearStoredSession()
                importWebViewSession()
            }
            if (refreshed.isNullOrBlank()) return chain.proceed(request)
            Log.d(LOG_TAG, "retrying request after session import")
            return chain.proceed(request.newBuilder().header("Authorization", "Bearer $refreshed").build())
        }

        fun hasSession(): Boolean = preferences.getString(PREF_ACCESS_TOKEN, null).orEmpty().isNotBlank()

        private fun validAccessToken(): String? {
            val access = preferences.getString(PREF_ACCESS_TOKEN, null)
            val expires = preferences.getLong(PREF_TOKEN_EXPIRES, 0L)
            if (!access.isNullOrBlank() && expires > System.currentTimeMillis() + TOKEN_REFRESH_MARGIN) return access
            refreshStoredToken()?.let { return it }
            clearStoredSession()
            return importWebViewSession()
        }

        private fun importWebViewSession(): String? {
            val now = System.currentTimeMillis()
            if (now - lastWebViewCheck < WEBVIEW_CHECK_INTERVAL) return null
            lastWebViewCheck = now
            val stored = STORAGE_KEYS.asSequence()
                .mapNotNull { key ->
                    val value = runCatching { runBlocking { getLocalStorage(baseUrl, key) } }.getOrNull()
                    if (!value.isNullOrBlank()) key to value else null
                }
                .firstOrNull()
            Log.d(LOG_TAG, "localStorage found=${stored != null}, key=${stored?.first ?: "none"}")
            val session = runCatching { stored?.second?.parseAs<AuthStorageDto>() }.getOrNull()
            Log.d(LOG_TAG, "session json parsed=${session != null}, access=${session?.accessToken != null}, refresh=${session?.refreshToken != null}")
            val access = session?.accessToken ?: return null
            val expiresAt = session.expiresAt?.times(1000L)
                ?: (System.currentTimeMillis() + (session.expiresIn ?: 3600L) * 1000L)
            Log.d(LOG_TAG, "token expired=${expiresAt <= System.currentTimeMillis()}")
            if (expiresAt <= System.currentTimeMillis()) return null
            preferences.edit()
                .putString(PREF_ACCESS_TOKEN, access)
                .putString(PREF_REFRESH_TOKEN, session.refreshToken)
                .putLong(PREF_TOKEN_EXPIRES, expiresAt)
                .apply()
            return access
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
            }.onFailure { Log.d(LOG_TAG, "refresh failed") }.getOrNull()
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
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val auth = response.parseAs<AuthResponseDto>()
                val access = auth.accessToken ?: throw IOException("Token de acesso ausente")
                preferences.edit()
                    .putString(PREF_ACCESS_TOKEN, access)
                    .putString(PREF_REFRESH_TOKEN, auth.refreshToken)
                    .putLong(PREF_TOKEN_EXPIRES, System.currentTimeMillis() + (auth.expiresIn ?: 3600) * 1000L)
                    .apply()
                return access
            }
        }

        private val authHeaders: Headers = Headers.Builder()
            .set("apikey", API_KEY)
            .set("x-client-info", CLIENT_INFO)
            .set("Content-Type", "application/json")
            .set("Accept", "application/json")
            .build()
    }
}
