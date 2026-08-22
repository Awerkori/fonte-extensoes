package eu.kanade.tachiyomi.extension.pt.risentoons

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import rx.Observable
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

@Source
abstract class Risentoons :
    HttpSource(),
    ConfigurableSource {
    override val supportsLatest = true
    private val preferences by getPreferencesLazy()
    private val combinedBlocks = ConcurrentHashMap<String, List<String>>()
    private val combinedCache = object : LinkedHashMap<String, ByteArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>): Boolean = size > 8
    }

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor(
            Interceptor { chain ->
                if (chain.request().url.encodedPath.startsWith("/__nox_combined/")) {
                    return@Interceptor composeBlock(chain.request())
                }
                val cookie = sessionCookie()
                if (cookie.isNullOrBlank()) throw IOException(LOGIN_MESSAGE)
                val request = chain.request().newBuilder()
                    .header("Cookie", cookie)
                    .header("Referer", "$baseUrl/")
                    .header("Origin", baseUrl)
                    .header("Accept", "application/json")
                    .apply {
                        if (chain.request().url.encodedPath.startsWith("/api/")) {
                            header("X-Rip-Client", RIP_CLIENT)
                            cookieValue(cookie, "access_token")?.let { header("Authorization", "Bearer $it") }
                        }
                    }
                    .build()
                preferences.edit().putBoolean(DIAG_AUTH, false).apply()
                val response = chain.proceed(request)
                val contentType = response.header("Content-Type").orEmpty()
                val bodyPreview = response.peekBody(256 * 1024).string()
                val received = catalogItemCount(bodyPreview, contentType)
                preferences.edit().putString(
                    catalogDiagnosticKey(request.url.toString(), DIAG_POPULAR_HTTP),
                    "${response.code} | $contentType | recebidos=$received",
                ).apply()
                if (response.code != 401) return@Interceptor response
                response.close()
                throw IOException("Sessão de cookies rejeitada pela Risetoons (HTTP 401). Faça login novamente.")
            },
        ).build()

    override fun popularMangaRequest(page: Int): okhttp3.Request = GET(
        "$baseUrl/api/mangas?limit=100&sort=ranking_score&page=$page",
        headers,
    )

    override fun popularMangaParse(response: okhttp3.Response): MangasPage = apiMangaParse(response)

    override fun fetchPopularManga(page: Int): Observable<MangasPage> = fetchCatalog { super.fetchPopularManga(page) }

    override fun latestUpdatesRequest(page: Int): okhttp3.Request = GET(
        "$baseUrl/api/mangas?limit=32&sort=created&page=$page",
        headers,
    )

    override fun latestUpdatesParse(response: okhttp3.Response): MangasPage = apiMangaParse(response)

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = fetchCatalog { super.fetchLatestUpdates(page) }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): okhttp3.Request = GET(
        "$baseUrl/api/mangas?limit=100&search=${java.net.URLEncoder.encode(query, "UTF-8")}&page=$page",
        headers,
    )

    override fun searchMangaParse(response: okhttp3.Response): MangasPage = apiMangaParse(response)

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = fetchCatalog { super.fetchSearchManga(page, query, filters) }

    override fun mangaDetailsRequest(manga: SManga): okhttp3.Request {
        val slug = manga.url.substringAfter("/biblioteca/").substringBefore('/').substringBefore('?')
        return GET("$baseUrl/api/mangas/$slug", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val root = response.parseAs<JsonElement>()
        val item = (root as? JsonObject)?.let { it["data"] as? JsonObject ?: it }
        return SManga.create().apply {
            title = item?.string("title", "name") ?: throw IOException("Risentoons details: título ausente")
            thumbnail_url = item?.coverUrl()?.let(::absoluteUrl)
            description = item?.string("description", "synopsis", "summary")
            author = item?.string("author", "author_name")
            artist = item?.string("artist", "artist_name")
            genre = item?.string("genres", "genre")
            initialized = true
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException("Risentoons chapters are parsed by fetchChapterList")

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Risentoons images use direct page URLs")

    private fun apiMangaParse(response: okhttp3.Response): MangasPage {
        val root = response.parseAs<JsonObject>()
        val mangas = root["mangas"]?.jsonArray.orEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val title = item.string("title", "name") ?: return@mapNotNull null
            val slug = item.string("slug", "id", "uuid") ?: return@mapNotNull null
            SManga.create().apply {
                this.title = title
                val uuid = item.string("uuid", "id") ?: slug
                setUrlWithoutDomain("/biblioteca/$slug?uuid=$uuid")
                thumbnail_url = item.coverUrl()?.let(::absoluteUrl)
                description = item.string("description", "synopsis", "summary")
                author = item.string("author", "author_name")
                artist = item.string("artist", "artist_name")
                genre = item.string("genres", "genre")
            }
        }
        if (mangas.isEmpty()) throw IOException("A API da Risentoons retornou 0 obras parseáveis.")
        val page = root["page"].textValue()?.toIntOrNull() ?: 1
        val total = root["total"].textValue()?.toIntOrNull() ?: mangas.size
        return MangasPage(mangas, page * mangas.size < total)
    }

    private fun JsonObject.string(vararg names: String): String? = names.firstNotNullOfOrNull { name ->
        this[name].textValue()?.takeIf(String::isNotBlank)
    }

    private fun JsonElement?.textValue(): String? = when (this) {
        is kotlinx.serialization.json.JsonPrimitive -> contentOrNull
        is JsonArray -> joinToString(", ") { it.textValue().orEmpty() }.takeIf(String::isNotBlank)
        is JsonObject -> values.firstNotNullOfOrNull { it.textValue() }
        else -> null
    }

    private fun JsonObject.coverUrl(): String? {
        val direct = string("cover", "cover_url", "coverPath", "thumbnail", "image", "media")
        if (direct != null) return direct
        values.forEach { value ->
            when (value) {
                is JsonObject -> value.coverUrl()?.let { return it }
                is JsonArray -> value.forEach { nested ->
                    nested.textValue()?.takeIf { it.contains("/media/manga_cover/") }?.let { return it }
                }
                else -> value.textValue()?.takeIf { it.contains("/media/manga_cover/") }?.let { return it }
            }
        }
        return null
    }

    private fun absoluteUrl(value: String): String = if (value.startsWith("http")) value else "$baseUrl${if (value.startsWith('/')) value else "/$value"}"

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url.substringBefore('?')}"

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.defer {
        var stage = "START"
        Observable.fromCallable {
            Log.d("Risentoons", "RISE_CHAPTER_API_FLOW START")
            Log.d("Risentoons", "chapter manga.title=${manga.title} manga.url=${manga.url} baseUrl=$baseUrl")
            stage = "SLUG"
            val mangaUrl = manga.url.takeIf(String::isNotBlank) ?: throw IllegalStateException("slug ausente")
            val slug = mangaUrl.substringAfter("/biblioteca/").substringBefore('/').substringBefore('?').takeIf(String::isNotBlank)
                ?: throw IllegalStateException("slug ausente em manga.url=$mangaUrl")
            stage = "UUID"
            val coverUuidRegex = Regex("/media/manga_cover/([0-9a-fA-F-]{36})\\.")
            val uuid = manga.thumbnail_url?.let { coverUuidRegex.find(it)?.groupValues?.getOrNull(1) }
                ?: run {
                    stage = "DETAILS_HTTP"
                    val detailsRoot = client.newCall(GET("$baseUrl/api/mangas/$slug", headers)).execute().use { response ->
                        if (!response.isSuccessful) throw IOException("details HTTP=${response.code}")
                        response.parseAs<JsonElement>()
                    }
                    detailsRoot.findCoverUrl()?.let { coverUuidRegex.find(it)?.groupValues?.getOrNull(1) }
                }
                ?: throw IllegalStateException("não foi possível obter UUID para slug=$slug")
            stage = "CHAPTERS_HTTP"
            val endpoint = "$baseUrl/api/mangas/$uuid/chapters?page=1&limit=1000&order=desc"
            val request = GET(endpoint, headers)
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("chapters HTTP=${response.code}")
                stage = "CHAPTERS_JSON"
                val root = response.parseAs<JsonElement>()
                val arrays = root.findArrays()
                if (arrays.isEmpty()) throw IllegalStateException("lista ausente; rootType=${root::class.simpleName}")
                stage = "CHAPTER_PARSE"
                val chapters = arrays.asSequence().flatMap { it.asSequence() }.mapNotNull { element ->
                    val item = element as? JsonObject ?: return@mapNotNull null
                    val chapterId = item.string("id", "uuid", "chapter_id") ?: return@mapNotNull null
                    SChapter.create().apply {
                        name = item.string("name", "title", "chapter", "number") ?: "Capítulo $chapterId"
                        chapter_number = item.string("chapter_number", "number", "chapter")?.toFloatOrNull() ?: -1f
                        date_upload = item.string("created_at", "published_at", "date")?.let(::parseChapterDate) ?: 0L
                        val number = item.string("chapter_number", "number", "chapter") ?: ""
                        setUrlWithoutDomain("/biblioteca/$slug/${number.ifBlank { chapterId }}/read?chapter_id=$chapterId")
                    }
                }.toList()
                if (chapters.isEmpty()) throw IllegalStateException("chapters list vazia após parse")
                Log.d("Risentoons", "RISE_CHAPTER_API_FLOW END count=${chapters.size}")
                chapters
            }
        }.onErrorResumeNext { error: Throwable ->
            val message = error.message?.replace(Regex("[\\r\\n]"), " ").orEmpty().take(240)
            Observable.error<List<SChapter>>(IOException("RISE_CHAPTERS_ERROR_$stage: ${error::class.simpleName}: $message", error))
        }
    }

    private fun JsonElement.findCoverUrl(): String? = when (this) {
        is kotlinx.serialization.json.JsonPrimitive -> contentOrNull?.takeIf { it.contains("/media/manga_cover/") }
        is JsonObject -> values.asSequence().mapNotNull { it.findCoverUrl() }.firstOrNull()
        is JsonArray -> asSequence().mapNotNull { it.findCoverUrl() }.firstOrNull()
        else -> null
    }

    private fun JsonElement?.findArrays(): List<JsonArray> = when (this) {
        is JsonArray -> listOf(this) + flatMap { it.findArrays() }
        is JsonObject -> values.flatMap { it.findArrays() }
        else -> emptyList()
    }

    private fun parseChapterDate(value: String): Long = runCatching {
        java.time.Instant.parse(value).toEpochMilli()
    }.getOrDefault(0L)

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl${chapter.url.substringBefore('?')}"

    override fun pageListRequest(chapter: SChapter): okhttp3.Request {
        val chapterId = chapter.url.substringAfter("chapter_id=", "").substringBefore('&')
        if (chapterId.isBlank()) throw IOException("Risentoons reader: chapter ID ausente")
        return GET("$baseUrl/api/mangas/chapters/$chapterId/pages", headers)
    }

    override fun pageListParse(response: okhttp3.Response): List<Page> {
        val root = response.parseAs<JsonElement>()
        val chapterId = response.request.url.pathSegments.getOrNull(response.request.url.pathSegments.indexOf("chapters") + 1)
            ?: throw IOException("Risentoons reader: chapter ID ausente na resposta")
        val pageElements = root.findArrays().firstOrNull().orEmpty()
        if (pageElements.isEmpty()) throw IOException("Risentoons reader: lista de páginas vazia")
        val imageUrls = pageElements.mapIndexed { index, element ->
            val item = element as? JsonObject
            item?.string("image_url")?.let { imageUrl ->
                baseUrl.toHttpUrl().resolve(imageUrl)?.toString()
                    ?: throw IOException("Risentoons reader: image_url inválida")
            } ?: "$baseUrl/api/mangas/chapters/$chapterId/pages/${item?.string("page", "number", "index")?.toIntOrNull() ?: index + 1}/image?v=${item?.string("version", "v") ?: "1.0.0"}"
        }
        val blocks = imageUrls.chunked(PAGES_PER_BLOCK)
        return blocks.mapIndexed { index, urls ->
            val synthetic = "$baseUrl/__nox_combined/$chapterId/$index"
            combinedBlocks[synthetic] = urls
            Page(index, imageUrl = synthetic)
        }
    }

    private fun composeBlock(request: Request): okhttp3.Response {
        val urls = combinedBlocks[request.url.toString()]
            ?: throw IOException("Risentoons reader: bloco composto ausente")
        return runCatching {
            synchronized(combinedCache) {
                combinedCache[request.url.toString()]?.let { cached ->
                    return@runCatching Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "image/png")
                        .body(cached.toResponseBody("image/png".toMediaType()))
                        .build()
                }
            }
            val bitmaps = urls.map { url ->
                val bytes = downloadImage(url)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
            val width = bitmaps.maxOf { it.width }
            val height = bitmaps.sumOf { it.height }
            val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(result)
            canvas.drawColor(android.graphics.Color.WHITE)
            var top = 0f
            bitmaps.forEach { bitmap ->
                canvas.drawBitmap(bitmap, 0f, top, null)
                top += bitmap.height
                if (!bitmap.isRecycled) bitmap.recycle()
            }
            val output = ByteArrayOutputStream()
            result.compress(Bitmap.CompressFormat.PNG, 100, output)
            result.recycle()
            val bytes = output.toByteArray()
            synchronized(combinedCache) { combinedCache[request.url.toString()] = bytes }
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Content-Type", "image/png")
                .body(bytes.toResponseBody("image/png".toMediaType()))
                .build()
        }.getOrElse { error -> throw IOException("RISE_READER_COMBINE_ERROR: ${error.message}", error) }
    }

    private fun downloadImage(url: String): ByteArray {
        val cookie = sessionCookie() ?: throw IOException(LOGIN_MESSAGE)
        val request = GET(url, headers).newBuilder()
            .header("Cookie", cookie)
            .header("Referer", "$baseUrl/")
            .header("X-Rip-Client", RIP_CLIENT)
            .withAccessToken(cookie, "access_token")
            .build()
        return network.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("imagem HTTP ${response.code}")
            response.body.bytes()
        }
    }

    private fun Request.Builder.withAccessToken(cookie: String, name: String): Request.Builder {
        cookieValue(cookie, name)?.let { header("Authorization", "Bearer $it") }
        return this
    }

    private fun recordDimensionDiagnostic(chapterId: String, urls: List<String>) {
        val dimensions = urls.map { url ->
            runCatching {
                client.newCall(GET(url, headers)).execute().use { response ->
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    response.body.byteStream().use { BitmapFactory.decodeStream(it, null, o) }
                    o.outWidth to o.outHeight
                }
            }.getOrDefault(0 to 0)
        }
        val histogram = dimensions.groupingBy { it.first }.eachCount().toSortedMap()
        val maxWidth = dimensions.maxOfOrNull { it.first } ?: 0
        val pairList = (0 until (dimensions.size - 1)).filter { i -> maxWidth > 0 && dimensions[i].first <= maxWidth * 0.65 && dimensions[i + 1].first <= maxWidth * 0.65 }
        val pairs = pairList.joinToString("\\n") { i -> "${i + 1} + ${i + 2} = ${dimensions[i].first}x${dimensions[i].second} + ${dimensions[i + 1].first}x${dimensions[i + 1].second}" }.ifEmpty { "nenhum" }
        preferences.edit().putString(
            DIAG_NATIVE_PAGES,
            buildString {
                append("DIMENSION DIAG\\nCHAPTER: $chapterId\\nTOTAL PAGES: ${urls.size}\\nWIDTH HISTOGRAM:\\n")
                histogram.forEach { (w, c) -> append("${w}px = $c imagens\\n") }
                append("DIMENSIONS:\\n")
                dimensions.forEachIndexed { i, d -> append("${i + 1} = ${d.first}x${d.second}\\n") }
                append("PAIR CANDIDATES:\\n$pairs")
            },
        ).apply()
    }

    private fun fetchCatalog(loader: () -> Observable<MangasPage>): Observable<MangasPage> {
        if (sessionCookie() == null) return Observable.just(loginCardPage())
        return loader().map { page ->
            if (page.mangas.isEmpty()) throw IOException("A Risetoons não retornou catálogo autenticado. Faça login novamente.")
            page
        }
    }

    private fun catalogDiagnosticKey(url: String, fallback: String): String = when {
        url.contains("load-more-releases") || url.contains("latest") || url.contains("recent") -> DIAG_LATEST_HTTP
        url.contains("search") -> DIAG_SEARCH_HTTP
        else -> fallback
    }

    private fun catalogItemCount(body: String, contentType: String): Int {
        if (contentType.contains("json", ignoreCase = true)) {
            return Regex("\\\"(?:title|name|manga|slug|cover|thumbnail)\\\"").findAll(body).count()
        }
        return Regex("class=\\\"[^\\\"]*(?:comic-card|manga-card)[^\\\"]*\\\"", RegexOption.IGNORE_CASE).findAll(body).count()
    }

    private fun loginCardPage(): MangasPage = MangasPage(
        listOf(
            SManga.create().apply {
                title = "Login necessário"
                description = "Entre na sua conta pelo WebView da extensão para acessar o catálogo."
                url = LOGIN_REQUIRED_URL
            },
        ),
        false,
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        lateinit var status: Preference
        lateinit var clear: Preference
        fun update() {
            val connected = sessionCookie() != null
            status.summary = if (connected) "Conectado" else "Não conectado"
            setVisible(clear, connected)
        }
        newPreference(screen.context).apply {
            key = "risentoons_login"
            title = "Entrar na Risetoons"
            summary = "Abre o login oficial no WebView."
            setOnPreferenceClickListener {
                showLoginWebView(screen.context, ::update)
                true
            }
        }.let(screen::addPreference)
        status = newPreference(screen.context).apply {
            key = "risentoons_session_status"
            title = "Sessão da Risetoons"
            setSelectable(this, false)
        }
        screen.addPreference(status)
        newPreference(screen.context).apply {
            key = "risentoons_native_reader_diag"
            title = "COPIAR DIAGNÓSTICO NATIVO DO READER"
            setOnPreferenceClickListener {
                val clipboard = screen.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Risentoons reader", preferences.getString(DIAG_NATIVE_PAGES, "Nenhum capítulo diagnosticado.").orEmpty()))
                true
            }
        }.let(screen::addPreference)
        clear = newPreference(screen.context).apply {
            key = "risentoons_clear_session"
            title = "Sair da conta"
            summary = "Remove a sessão salva neste aplicativo."
            setOnPreferenceClickListener {
                clearRisentoonsCookies()
                preferences.edit().remove(ACCESS).remove(REFRESH).remove(EXPIRES).remove(AUTH_URL).remove(COOKIE).remove(ORIGIN).remove(STORAGE_KEY).apply()
                update()
                true
            }
        }
        screen.addPreference(clear)
        update()
    }

    private fun diagnosticSummary(): String = listOf(
        "WebView login aberto: ${preferences.getBoolean(DIAG_WEBVIEW, false).yesNo()}",
        "Última URL observada: ${preferences.getString(DIAG_URL, "não observada")}",
        "Último origin observado: ${preferences.getString(DIAG_ORIGIN, "não observado")}",
        "Últimas URLs: ${preferences.getString(DIAG_URLS, "não observadas")}",
        "Título: ${preferences.getString(DIAG_TITLE, "não observado")}",
        "Página aparenta autenticada pelo DOM: ${preferences.getBoolean(DIAG_DOM_AUTH, false).yesNo()}",
        "Formulário de login presente: ${preferences.getBoolean(DIAG_LOGIN_FORM, false).yesNo()}",
        "Logout encontrado: ${preferences.getBoolean(DIAG_LOGOUT, false).yesNo()}",
        "localStorage lido: ${preferences.getBoolean(DIAG_STORAGE_READ, false).yesNo()}",
        "Quantidade de chaves encontradas: ${preferences.getInt(DIAG_KEY_COUNT, 0)}",
        "localStorage keys: ${preferences.getString(DIAG_LOCAL_KEYS, "nenhuma")}",
        "localStorage campos: ${preferences.getString(DIAG_LOCAL_FIELDS, "nenhum")}",
        "sessionStorage lido: ${preferences.getBoolean(DIAG_SESSION_READ, false).yesNo()}",
        "sessionStorage keys: ${preferences.getString(DIAG_SESSION_KEYS, "nenhuma")}",
        "sessionStorage campos: ${preferences.getString(DIAG_SESSION_FIELDS, "nenhum")}",
        "Chave candidata encontrada: ${preferences.getBoolean(DIAG_CANDIDATE, false).yesNo()}",
        "access_token encontrado: ${preferences.getBoolean(DIAG_ACCESS, false).yesNo()}",
        "refresh_token encontrado: ${preferences.getBoolean(DIAG_REFRESH, false).yesNo()}",
        "expires_at encontrado: ${preferences.getBoolean(DIAG_EXPIRES, false).yesNo()}",
        "Sessão salva nas preferências: ${preferences.getBoolean(DIAG_SAVED, false).yesNo()}",
        "Access token nas preferências: ${preferences.getString(ACCESS, null).orEmpty().isNotBlank().yesNo()}",
        "Refresh token nas preferências: ${preferences.getString(REFRESH, null).orEmpty().isNotBlank().yesNo()}",
        "Cookies encontrados: ${preferences.getBoolean(DIAG_COOKIES, false).yesNo()}",
        "Quantidade de cookies: ${preferences.getInt(DIAG_COOKIE_COUNT, 0)}",
        "Nomes dos cookies: ${preferences.getString(DIAG_COOKIE_NAMES, "nenhum")}",
        "IndexedDB: ${preferences.getString(DIAG_IDB, "não verificado")}",
        "Recursos/endpoints observados: ${preferences.getString(DIAG_RESOURCES, "nenhum")}",
        "Fetch/XHR capturados: ${preferences.getString(DIAG_REQUESTS, "nenhum")}",
        "Último refresh: ${preferences.getString(DIAG_LAST_REFRESH, "NÃO EXECUTADA")}",
        "Authorization preparado: ${preferences.getBoolean(DIAG_AUTH, false).yesNo()}",
        "Bearer confirmado pelo frontend: NÃO CONFIRMADO",
        "Sessão por cookie confirmada: NÃO CONFIRMADO",
        "Último HTTP Popular: ${preferences.getString(DIAG_POPULAR_HTTP, "não executado")}",
        "Último HTTP Recentes: ${preferences.getString(DIAG_LATEST_HTTP, "não executado")}",
        "Último HTTP Search: ${preferences.getString(DIAG_SEARCH_HTTP, "não executado")}",
        "Endpoint testado: ${preferences.getString(DIAG_TEST_URL, "não testado")}",
        "HTTP WebView/session: ${preferences.getString(DIAG_WEB_HTTP, "não testado")}",
        "HTTP OkHttp: ${preferences.getString(DIAG_OKHTTP, "não testado")}",
        "Content-Type: ${preferences.getString(DIAG_CONTENT_TYPE, "não observado")}",
        "Redirect Location: ${preferences.getString(DIAG_REDIRECT, "nenhum")}",
        "Resposta aparenta login: ${preferences.getBoolean(DIAG_LOGIN_RESPONSE, false).yesNo()}",
        "Resposta aparenta catálogo: ${preferences.getBoolean(DIAG_CATALOG_RESPONSE, false).yesNo()}",
    ).joinToString("\n")

    private fun Boolean.yesNo(): String = if (this) "SIM" else "NÃO"

    private fun newPreference(context: android.content.Context): Preference = runCatching {
        Preference::class.java.getConstructor(android.content.Context::class.java).newInstance(context)
    }.getOrElse { Preference() }

    private fun setVisible(preference: Preference, visible: Boolean) {
        runCatching {
            preference::class.java.methods.firstOrNull { it.name == "setVisible" }?.invoke(preference, visible)
        }
    }

    private fun setSelectable(preference: Preference, selectable: Boolean) {
        runCatching {
            preference::class.java.methods.firstOrNull { it.name == "setSelectable" }?.invoke(preference, selectable)
        }
    }

    private fun showLoginWebView(context: android.content.Context, onDone: () -> Unit) {
        val dialog = Dialog(context)
        val webView = WebView(context)
        val handler = Handler(Looper.getMainLooper())
        var closed = false
        preferences.edit().putBoolean(DIAG_WEBVIEW, true).apply()
        fun close() {
            if (closed) return
            closed = true
            handler.removeCallbacksAndMessages(null)
            if (dialog.isShowing) dialog.dismiss()
            webView.destroy()
        }
        fun poll() {
            if (closed || !dialog.isShowing) return
            webView.evaluateJavascript(SESSION_SCRIPT) { raw ->
                val session = runCatching { raw.parseAs<String?>()?.parseAs<StorageSession?>() }.getOrNull()
                session?.let { recordDiagnostic(it) }
                CookieManager.getInstance().flush()
                if (sessionCookie().isNullOrBlank()) {
                    handler.postDelayed(::poll, 1_000L)
                    return@evaluateJavascript
                }
                preferences.edit().putString(ACCESS, session?.accessToken.orEmpty()).putString(REFRESH, session?.refreshToken.orEmpty())
                    .putLong(EXPIRES, session?.expiresAt ?: 0L).putString(AUTH_URL, session?.authUrl)
                    .putString(COOKIE, webViewCookieHeader())
                    .putString(ORIGIN, session?.origin).putString(STORAGE_KEY, session?.storageKey).apply()
                val saved = sessionCookie() != null
                preferences.edit().putBoolean(DIAG_SAVED, saved).apply()
                onDone()
                close()
            }
        }
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean = false
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val host = request?.url?.host.orEmpty()
                return if (isLoginAdHost(host)) {
                    Log.d("Risentoons", "RISENTOONS_DEBUG WEBVIEW blocked resource host=$host")
                    WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                } else {
                    null
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val host = request?.url?.host.orEmpty()
                if (!isLoginAdHost(host)) return false

                Log.d("Risentoons", "RISENTOONS_DEBUG WEBVIEW blocked navigation host=$host")
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                view?.evaluateJavascript(INSTRUMENTATION_SCRIPT, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript(INSTRUMENTATION_SCRIPT, null)
                val urls = preferences.getString(DIAG_URLS, "").orEmpty().split("\n").filter { it.isNotBlank() }.plus(url.orEmpty()).takeLast(8)
                preferences.edit().putString(DIAG_URL, url.orEmpty()).putString(DIAG_URLS, urls.joinToString("\n")).apply()
                view?.title?.let { preferences.edit().putString(DIAG_TITLE, it).apply() }
                poll()
            }
        }
        dialog.setContentView(webView)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.setOnShowListener { dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
        dialog.setOnDismissListener { close() }
        dialog.show()
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        webView.loadUrl("$baseUrl/login")
    }

    private fun isLoginAdHost(host: String): Boolean = host == "platform.pubadx.one" ||
        host.endsWith(".pubadx.one") ||
        host == "static.cloudflareinsights.com" ||
        host == "googletagmanager.com" ||
        host.endsWith(".googletagmanager.com")

    private fun showRouteDiagnosticWebView(context: Context) {
        val dialog = Dialog(context)
        val webView = WebView(context)
        preferences.edit()
            .remove(DIAG_LAYOUT)
            .remove(DIAG_READER_URL)
            .remove(DIAG_LAYOUT_NONCE)
            .remove(DIAG_LAYOUT_INSTANCE)
            .remove(DIAG_LAYOUT_REASON)
            .putBoolean(DIAG_LAYOUT_VALID, false)
            .putString(DIAG_LAYOUT_REASON, "bridge não recebeu")
            .apply()
        webView.addJavascriptInterface(
            object {
                @JavascriptInterface
                fun capture(report: String) {
                    val jsUrl = Regex("\\\"url\\\":\\\"([^\\\"]*)").find(report)?.groupValues?.getOrNull(1).orEmpty()
                    val elements = Regex("\\\"elementCount\\\":(\\d+)").find(report)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                    val nonce = Regex("\\\"nonce\\\":\\\"([^\\\"]*)").find(report)?.groupValues?.getOrNull(1).orEmpty()
                    val valid = isReaderUrl(jsUrl) && elements > 0
                    if (valid) {
                        preferences.edit().putString(DIAG_LAYOUT, report).putString(DIAG_READER_URL, jsUrl)
                            .putString(DIAG_LAYOUT_NONCE, nonce).putString(DIAG_LAYOUT_INSTANCE, System.identityHashCode(webView).toString())
                            .putBoolean(DIAG_LAYOUT_VALID, true).apply()
                    } else {
                        preferences.edit().putBoolean(DIAG_LAYOUT_VALID, false)
                            .putString(DIAG_LAYOUT_REASON, if (!isReaderUrl(jsUrl)) "nunca entrou em /read" else "/read detectado mas elementos=0").apply()
                    }
                }
            },
            "RisentoonsLayout",
        )
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
        }
        fun nativeEvent(type: String, url: String?) {
            val line = "$type | ${System.currentTimeMillis()} | ${url.orEmpty()}"
            val old = preferences.getString(DIAG_NATIVE_EVENTS, "").orEmpty().split("\n").filter(String::isNotBlank)
            preferences.edit().putString(DIAG_NATIVE_EVENTS, (old + line).takeLast(120).joinToString("\n")).apply()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ServiceWorkerController.getInstance().setServiceWorkerClient(object : ServiceWorkerClient() {
                override fun shouldInterceptRequest(request: WebResourceRequest): android.webkit.WebResourceResponse? {
                    nativeRequest("SERVICE_WORKER", request.url.toString(), request.method, request.requestHeaders.keys)
                    return null
                }
            })
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                request?.let { nativeEvent("OVERRIDE", it.url.toString()) }
                return false
            }
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                nativeEvent("START", url)
                view?.evaluateJavascript(ROUTE_HOOK_SCRIPT, null)
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                nativeEvent("FINISH", url)
                view?.evaluateJavascript(ROUTE_HOOK_SCRIPT, null)
                if (isReaderUrl(url)) {
                    preferences.edit().putString(DIAG_READER_URL, url.orEmpty()).apply()
                    view?.evaluateJavascript(LAYOUT_DIAG4_SCRIPT, null)
                }
                view?.evaluateJavascript(ROUTE_SNAPSHOT_SCRIPT) { raw ->
                    raw.parseAs<String?>()?.let { preferences.edit().putString(DIAG_ROUTE_REPORT, it).apply() }
                }
            }
            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                nativeEvent("HISTORY${if (isReload) "_RELOAD" else ""}", url)
            }
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): android.webkit.WebResourceResponse? {
                request?.let { nativeRequest("REQUEST", it.url.toString(), it.method, it.requestHeaders.keys) }
                return null
            }
        }
        dialog.setContentView(webView)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.setOnShowListener { dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
        dialog.show()
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        webView.loadUrl("$baseUrl/home")
    }

    private fun nativeRequest(type: String, url: String, method: String, headers: Set<String>) {
        if (!url.contains("/api/") && !url.contains("manga", true) && !url.contains("chapter", true) && !url.contains("reader", true) && !url.contains("manifest", true) && !url.contains("progress", true)) return
        val names = headers.joinToString(",")
        val line = "$type | $method | $url | headers=$names | Authorization=${headers.any { it.equals("Authorization", true) }} | Cookie=${headers.any { it.equals("Cookie", true) }} | X-Rip-Client=${headers.any { it.equals("X-Rip-Client", true) }}"
        val old = preferences.getString(DIAG_NATIVE_REQUESTS, "").orEmpty().split("\n").filter(String::isNotBlank)
        preferences.edit().putString(DIAG_NATIVE_REQUESTS, (old + line).takeLast(120).joinToString("\n")).apply()
    }

    private fun routeDiagnosticSummary(): String = listOf(
        "NAVEGAÇÃO NATIVA",
        preferences.getString(DIAG_NATIVE_EVENTS, "nenhuma").orEmpty(),
        "REQUESTS NATIVAS",
        preferences.getString(DIAG_NATIVE_REQUESTS, "nenhuma").orEmpty(),
        "HOOK JS",
        preferences.getString(DIAG_ROUTE_REPORT, "não capturado").orEmpty(),
    ).joinToString("\n")

    private fun layoutDiagnosticSummary(): String = listOf(
        "READER-LAYOUT-DIAG4",
        "URL CAPTURADA: ${preferences.getString(DIAG_READER_URL, "não capturada")}",
        preferences.getString(DIAG_LAYOUT, "nenhum snapshot capturado").orEmpty(),
    ).joinToString("\n")

    private fun isReaderUrl(url: String?): Boolean = url?.matches(Regex(".*/biblioteca/[^/]+/[^/]+/read(?:[?#].*)?$")) == true

    private fun sessionCookie(): String? {
        val cookie = preferences.getString(COOKIE, null).orEmpty()
        return cookie.takeIf { hasRequiredCookies(it) }
            ?: webViewCookieHeader().takeIf { hasRequiredCookies(it) }
    }

    private fun webViewCookieHeader(): String = listOf(baseUrl, "https://www.risentoons.xyz")
        .flatMap { CookieManager.getInstance().getCookie(it).orEmpty().split(';') }
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .joinToString("; ")

    private fun hasRequiredCookies(cookie: String): Boolean {
        val names = cookie.split(';').map { it.substringBefore('=').trim() }.toSet()
        return REQUIRED_COOKIE_NAMES.all(names::contains)
    }

    private fun cookieValue(cookie: String, name: String): String? = cookie.split(';')
        .map { it.trim() }
        .firstOrNull { it.substringBefore('=') == name }
        ?.substringAfter('=')
        ?.takeIf(String::isNotBlank)

    private fun clearRisentoonsCookies() {
        val manager = CookieManager.getInstance()
        listOf(baseUrl, "https://www.risentoons.xyz").forEach { url ->
            REQUIRED_COOKIE_NAMES.forEach { name ->
                manager.setCookie(url, "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/")
            }
        }
        manager.flush()
    }

    private fun runRequestProbe(url: String) {
        Thread {
            runCatching {
                client.newCall(okhttp3.Request.Builder().url(url).header("Accept", "text/html,application/json").build()).execute().use { response ->
                    preferences.edit()
                        .putString(DIAG_OKHTTP, response.code.toString())
                        .putString(DIAG_CONTENT_TYPE, response.header("Content-Type").orEmpty())
                        .putString(DIAG_REDIRECT, response.header("Location").orEmpty().ifBlank { "nenhum" })
                        .putBoolean(DIAG_LOGIN_RESPONSE, response.header("Content-Type").orEmpty().contains("text/html") && response.code in 200..399)
                        .putBoolean(DIAG_CATALOG_RESPONSE, response.header("Content-Type").orEmpty().contains("json"))
                        .apply()
                }
            }.onFailure {
                preferences.edit().putString(DIAG_OKHTTP, "falha: ${it::class.simpleName}").apply()
            }
        }.start()
    }

    private fun recordDiagnostic(session: StorageSession) {
        val cookies = listOf(baseUrl, "https://www.risentoons.xyz")
            .flatMap { CookieManager.getInstance().getCookie(it).orEmpty().split(';') }
            .map { it.substringBefore('=').trim() }
            .filter { it.isNotBlank() }
            .distinct()
        preferences.edit()
            .putString(DIAG_ORIGIN, session.origin)
            .putBoolean(DIAG_STORAGE_READ, true)
            .putInt(DIAG_KEY_COUNT, session.keys?.size ?: 0)
            .putBoolean(DIAG_CANDIDATE, !session.storageKey.isNullOrBlank())
            .putBoolean(DIAG_ACCESS, !session.accessToken.isNullOrBlank())
            .putBoolean(DIAG_REFRESH, !session.refreshToken.isNullOrBlank())
            .putBoolean(DIAG_EXPIRES, session.expiresAt > 0L)
            .putBoolean(DIAG_COOKIES, cookies.isNotEmpty())
            .putInt(DIAG_COOKIE_COUNT, cookies.size)
            .putString(DIAG_COOKIE_NAMES, cookies.joinToString(", ").ifBlank { "nenhum" })
            .putString(DIAG_LOCAL_KEYS, session.localKeys.joinToString(", ").ifBlank { "nenhuma" })
            .putString(DIAG_LOCAL_FIELDS, session.localFields.joinToString(", ").ifBlank { "nenhum" })
            .putBoolean(DIAG_SESSION_READ, true)
            .putString(DIAG_SESSION_KEYS, session.sessionKeys.joinToString(", ").ifBlank { "nenhuma" })
            .putString(DIAG_SESSION_FIELDS, session.sessionFields.joinToString(", ").ifBlank { "nenhum" })
            .putString(DIAG_IDB, session.indexedDb.ifBlank { "nenhum detectado" })
            .putBoolean(DIAG_DOM_AUTH, session.domAuthenticated)
            .putBoolean(DIAG_LOGIN_FORM, session.loginForm)
            .putBoolean(DIAG_LOGOUT, session.logoutFound)
            .putString(DIAG_RESOURCES, session.resources.joinToString("\n").ifBlank { "nenhum" })
            .putString(DIAG_REQUESTS, session.requests.joinToString("\n").ifBlank { "nenhum" })
            .putString(DIAG_TEST_URL, session.testUrl.orEmpty().ifBlank { "não testado" })
            .putString(DIAG_WEB_HTTP, session.webHttp.orEmpty().ifBlank { "não testado" })
            .putString(DIAG_CONTENT_TYPE, session.contentType.orEmpty().ifBlank { "não observado" })
            .putString(DIAG_REDIRECT, session.redirect.orEmpty().ifBlank { "nenhum" })
            .putBoolean(DIAG_LOGIN_RESPONSE, session.loginResponse)
            .putBoolean(DIAG_CATALOG_RESPONSE, session.catalogResponse)
            .apply()
        session.testUrl?.takeIf { it.isNotBlank() }?.let(::runRequestProbe)
    }

    @Synchronized
    private fun refreshToken(): String? {
        val refresh = preferences.getString(REFRESH, null).orEmpty()
        val authUrl = preferences.getString(AUTH_URL, null).orEmpty()
        if (refresh.isBlank() || authUrl.isBlank()) return null
        return runCatching {
            val request = okhttp3.Request.Builder().url("$authUrl/token?grant_type=refresh_token")
                .post(okhttp3.RequestBody.create("application/json".toMediaType(), "{\"refresh_token\":\"$refresh\"}")).build()
            network.client.newCall(request).execute().use { response ->
                preferences.edit().putString(DIAG_LAST_REFRESH, if (response.isSuccessful) "SUCESSO" else "FALHA").apply()
                if (!response.isSuccessful) return@use null
                val session = response.parseAs<StorageSession>()
                preferences.edit().putString(ACCESS, session.accessToken).putString(REFRESH, session.refreshToken).putLong(EXPIRES, session.expiresAt).apply()
                session.accessToken
            }
        }.getOrNull()
    }

    @Serializable
    private class StorageSession(
        val accessToken: String? = null,
        val refreshToken: String? = null,
        val expiresAt: Long = 0L,
        val authUrl: String? = null,
        val origin: String? = null,
        val storageKey: String? = null,
        val keys: List<String> = emptyList(),
        val localKeys: List<String> = emptyList(),
        val localFields: List<String> = emptyList(),
        val sessionKeys: List<String> = emptyList(),
        val sessionFields: List<String> = emptyList(),
        val indexedDb: String = "",
        val domAuthenticated: Boolean = false,
        val loginForm: Boolean = false,
        val logoutFound: Boolean = false,
        val resources: List<String> = emptyList(),
        val requests: List<String> = emptyList(),
        val testUrl: String? = null,
        val webHttp: String? = null,
        val contentType: String? = null,
        val redirect: String? = null,
        val loginResponse: Boolean = false,
        val catalogResponse: Boolean = false,
    )

    companion object {
        const val MAX_BLOCK_HEIGHT = 7000
        const val PAGES_PER_BLOCK = 7
        const val LOGIN_MESSAGE = "Faça login na Risetoons pelas configurações da extensão."
        const val ACCESS = "risentoons_access_token"
        const val REFRESH = "risentoons_refresh_token"
        const val EXPIRES = "risentoons_token_expires"
        const val AUTH_URL = "risentoons_auth_url"
        const val COOKIE = "risentoons_cookie"
        const val ORIGIN = "risentoons_session_origin"
        const val STORAGE_KEY = "risentoons_session_storage_key"
        const val REFRESH_MARGIN = 60_000L
        val REQUIRED_COOKIE_NAMES = setOf("session_id", "access_token", "refresh_token")
        const val RIP_CLIENT = "V6"
        const val LOGIN_REQUIRED_URL = "__risentoons_login_required__"
        const val DIAG_WEBVIEW = "risentoons_diag_webview"
        const val DIAG_URL = "risentoons_diag_url"
        const val DIAG_URLS = "risentoons_diag_urls"
        const val DIAG_TITLE = "risentoons_diag_title"
        const val DIAG_ORIGIN = "risentoons_diag_origin"
        const val DIAG_STORAGE_READ = "risentoons_diag_storage_read"
        const val DIAG_KEY_COUNT = "risentoons_diag_key_count"
        const val DIAG_LOCAL_KEYS = "risentoons_diag_local_keys"
        const val DIAG_LOCAL_FIELDS = "risentoons_diag_local_fields"
        const val DIAG_SESSION_READ = "risentoons_diag_session_read"
        const val DIAG_SESSION_KEYS = "risentoons_diag_session_keys"
        const val DIAG_SESSION_FIELDS = "risentoons_diag_session_fields"
        const val DIAG_CANDIDATE = "risentoons_diag_candidate"
        const val DIAG_ACCESS = "risentoons_diag_access"
        const val DIAG_REFRESH = "risentoons_diag_refresh"
        const val DIAG_EXPIRES = "risentoons_diag_expires"
        const val DIAG_SAVED = "risentoons_diag_saved"
        const val DIAG_COOKIES = "risentoons_diag_cookies"
        const val DIAG_COOKIE_COUNT = "risentoons_diag_cookie_count"
        const val DIAG_COOKIE_NAMES = "risentoons_diag_cookie_names"
        const val DIAG_IDB = "risentoons_diag_idb"
        const val DIAG_LAST_REFRESH = "risentoons_diag_last_refresh"
        const val DIAG_AUTH = "risentoons_diag_auth"
        const val DIAG_POPULAR_HTTP = "risentoons_diag_popular_http"
        const val DIAG_LATEST_HTTP = "risentoons_diag_latest_http"
        const val DIAG_SEARCH_HTTP = "risentoons_diag_search_http"
        const val DIAG_DOM_AUTH = "risentoons_diag_dom_auth"
        const val DIAG_LOGIN_FORM = "risentoons_diag_login_form"
        const val DIAG_LOGOUT = "risentoons_diag_logout"
        const val DIAG_RESOURCES = "risentoons_diag_resources"
        const val DIAG_REQUESTS = "risentoons_diag_requests"
        const val DIAG_TEST_URL = "risentoons_diag_test_url"
        const val DIAG_WEB_HTTP = "risentoons_diag_web_http"
        const val DIAG_OKHTTP = "risentoons_diag_okhttp"
        const val DIAG_CONTENT_TYPE = "risentoons_diag_content_type"
        const val DIAG_REDIRECT = "risentoons_diag_redirect"
        const val DIAG_LOGIN_RESPONSE = "risentoons_diag_login_response"
        const val DIAG_CATALOG_RESPONSE = "risentoons_diag_catalog_response"
        const val DIAG_ROUTE_REPORT = "risentoons_diag_route_report"
        const val DIAG_NATIVE_EVENTS = "risentoons_diag_native_events"
        const val DIAG_NATIVE_REQUESTS = "risentoons_diag_native_requests"
        const val DIAG_LAYOUT = "risentoons_diag_layout"
        const val DIAG_READER_URL = "risentoons_diag_reader_url"
        const val DIAG_LAYOUT_VALID = "risentoons_diag_layout_valid"
        const val DIAG_LAYOUT_NONCE = "risentoons_diag_layout_nonce"
        const val DIAG_LAYOUT_INSTANCE = "risentoons_diag_layout_instance"
        const val DIAG_NATIVE_PAGES = "risentoons_diag_native_pages"
        const val DIAG_LAYOUT_REASON = "risentoons_diag_layout_reason"
        const val ROUTE_HOOK_SCRIPT = """
            (function(){if(window.__rd)return;window.__rd={start:location.href,urls:[],events:[],requests:[]};
            function add(x){window.__rd.events.push(x);if(window.__rd.events.length>100)window.__rd.events.shift()}
            function req(x,m,h){var u=typeof x==='string'?x:(x&&x.url)||'';if(!/manga|chapter|capitulo|manifest|reader|progress/i.test(u))return;
              window.__rd.requests.push({url:u,method:m||'GET',headers:h||[]});}
            var f=window.fetch;window.fetch=function(x,o){var h=[];try{var z=o&&o.headers;if(z)for(var k in z)h.push(k)}catch(e){}req(x,(o&&o.method)||'GET',h);return f.apply(this,arguments).then(function(r){if(window.__rd.requests.length)window.__rd.requests[window.__rd.requests.length-1].status=r.status;return r})};
            var op=history.pushState,or=history.replaceState;history.pushState=function(s,t,u){add({type:'pushState',url:String(u)});return op.apply(this,arguments)};history.replaceState=function(s,t,u){add({type:'replaceState',url:String(u)});return or.apply(this,arguments)};
            var xo=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(m,u){this.__rd={method:m,url:u};req(u,m,[]);this.addEventListener('load',function(){var a=window.__rd.requests[window.__rd.requests.length-1];if(a)a.status=this.status});return xo.apply(this,arguments)};
            document.addEventListener('click',function(e){var a=e.target.closest&&e.target.closest('a');if(a)add({type:'click',href:a.href||'',text:(a.textContent||'').trim().slice(0,100)})},true)})()
        """
        const val ROUTE_SNAPSHOT_SCRIPT = """
            (function(){var r=window.__rd||{start:'',events:[],requests:[]};return JSON.stringify({obra:{title:document.title,url:location.href},navegacao:{inicio:r.start,eventos:r.events.slice(-30)},requests:r.requests.slice(-50)})})()
        """
        const val LAYOUT_DIAG4_SCRIPT = """
            (function(){
              if(window.__risentoonsLayoutDiag4)return;
              window.__risentoonsLayoutDiag4=true;
              function clean(v){return String(v||'').replace(/[\\r\\n]+/g,' ').slice(0,240)}
              function attrs(e){var a={};Array.from(e.attributes||[]).forEach(function(x){if(/^data-/.test(x.name))a[x.name]=clean(x.value)});return a}
              function style(e){var s=getComputedStyle(e);return {display:s.display,position:s.position,width:s.width,height:s.height,gridTemplateColumns:s.gridTemplateColumns,gridTemplateRows:s.gridTemplateRows,gridColumn:s.gridColumn,gridRow:s.gridRow,gap:s.gap,flexDirection:s.flexDirection,flexWrap:s.flexWrap,justifyContent:s.justifyContent,alignItems:s.alignItems}}
              function rect(e){var r=e.getBoundingClientRect();return {x:Math.round(r.x),y:Math.round(r.y),width:Math.round(r.width),height:Math.round(r.height),top:Math.round(r.top),left:Math.round(r.left),right:Math.round(r.right),bottom:Math.round(r.bottom)}}
              function capture(){
                var nonce=String(Date.now())+'-'+Math.random().toString(36).slice(2,10);
                var els=Array.from(document.querySelectorAll('img,canvas,picture,source,[style*="background-image"]')).map(function(e,i){
                  var r=rect(e),p=e.parentElement||e,pr=rect(p);return {index:i,tag:e.tagName.toLowerCase(),src:clean(e.currentSrc||e.src||e.getAttribute('src')||e.style.backgroundImage),naturalWidth:e.naturalWidth||e.width||0,naturalHeight:e.naturalHeight||e.height||0,rect:r,visibleInViewport:r.bottom>0&&r.top<innerHeight,className:clean(e.className),id:clean(e.id),data:attrs(e),parent:{tag:p.tagName.toLowerCase(),className:clean(p.className),rect:pr,style:style(p)}}
                }).sort(function(a,b){return a.rect.top-b.rect.top||a.rect.left-b.rect.left});
                var rows=[];els.forEach(function(e){var row=rows.find(function(x){return Math.abs(x.top-e.rect.top)<=8});if(!row){row={top:e.rect.top,elements:[]};rows.push(row)}row.elements.push(e)});rows.sort(function(a,b){return a.top-b.top});
                var requests=performance.getEntriesByType('resource').map(function(x){return x.name}).filter(function(x){return /\/api\/mangas\/chapters\//.test(x)&&(/\/pages(?:$|[/?])/.test(x)||/\/image(?:[/?])/.test(x))}).slice(-80);
                return {nonce:nonce,url:location.href,title:document.title,viewport:{width:innerWidth,height:innerHeight,scrollX:scrollX,scrollY:scrollY},rows:rows,requests:requests,elementCount:els.length,imgCount:els.filter(function(x){return x.tag==='img'}).length,canvasCount:els.filter(function(x){return x.tag==='canvas'}).length}
              }
              window.__risentoonsDiagError=function(t){var e=document.createElement('pre');e.textContent='ERRO DIAG: '+t;e.style='position:fixed;z-index:2147483647;top:12px;left:12px;right:12px;padding:12px;background:#991b1b;color:#fff;white-space:pre-wrap';document.body.appendChild(e)};
              function auto(){if(!/\/biblioteca\/[^/]+\/[^/]+\/read(?:[?#].*)?$/.test(location.href))return;var r=capture();if(r.elementCount>0&&window.RisentoonsLayout)window.RisentoonsLayout.capture(JSON.stringify(r));}
              new MutationObserver(function(){setTimeout(auto,100)}).observe(document.documentElement,{childList:true,subtree:true});
              var ps=history.pushState,rs=history.replaceState;history.pushState=function(){var v=ps.apply(this,arguments);setTimeout(auto,100);return v};history.replaceState=function(){var v=rs.apply(this,arguments);setTimeout(auto,100);return v};window.addEventListener('popstate',function(){setTimeout(auto,100)});setInterval(auto,500);auto();
            })()
        """
        const val INSTRUMENTATION_SCRIPT = """
            (function(){
              if(window.__risentoons_hooks)return;
              window.__risentoons_hooks=true;window.__risentoons_requests=[];
              function save(item){
                try{var a=window.__risentoons_requests;a.push(item);if(a.length>80)a.shift()}catch(e){}
              }
              function fields(text,type){
                try{if(!/json/i.test(type))return '';var v=JSON.parse(text),x=Array.isArray(v)?v[0]:v;
                  return v&&typeof v==='object'?Object.keys(x||v).slice(0,20).join(','):''}catch(e){return ''}
              }
              function count(text,type){
                try{if(!/json/i.test(type))return 0;var v=JSON.parse(text);
                  if(Array.isArray(v))return v.length;
                  for(var k in v)if(Array.isArray(v[k]))return v[k].length;
                  return 0}catch(e){return 0}
              }
              var oldFetch=window.fetch;
              window.fetch=function(input,init){
                var u=typeof input==='string'?input:(input&&input.url)||'',m=(init&&init.method)||((input&&input.method)||'GET'),hs=[];
                try{var h=(init&&init.headers)||(input&&input.headers);if(h)for(var k in h)hs.push(k)}catch(e){}
                return oldFetch.apply(this,arguments).then(function(r){var c=r.clone(),type=r.headers.get('content-type')||'';
                  c.text().then(function(t){save({url:u,method:m,status:r.status,type:type,size:t.length,fields:fields(t,type),items:count(t,type),headers:hs})}).catch(function(){save({url:u,method:m,status:r.status,type:type,size:0,fields:'',items:0,headers:hs})});return r})
              };
              var XO=XMLHttpRequest.prototype.open,XS=XMLHttpRequest.prototype.setRequestHeader,XP=XMLHttpRequest.prototype.send;
              XMLHttpRequest.prototype.open=function(m,u){this.__r={method:m,url:u,headers:[]};return XO.apply(this,arguments)};
              XMLHttpRequest.prototype.setRequestHeader=function(k,v){if(this.__r)this.__r.headers.push(k);return XS.apply(this,arguments)};
              XMLHttpRequest.prototype.send=function(body){var x=this;function done(){if(!x.__r)return;var t=x.getResponseHeader('content-type')||'',b=x.responseText||'';save({url:x.__r.url,method:x.__r.method,status:x.status,type:t,size:b.length,fields:fields(b,t),items:count(b,t),headers:x.__r.headers})}x.addEventListener('load',done,{once:true});x.addEventListener('error',done,{once:true});return XP.apply(this,arguments)};
            })()
        """
        const val SESSION_SCRIPT = """
            (function(){
              try {
                var seen=[];
                function decode(value){
                  if(typeof value !== 'string') return value;
                  var current=value;
                  for(var n=0;n<3;n++){
                    try { var parsed=JSON.parse(current); if(typeof parsed==='string'){current=parsed;continue} return parsed } catch(e){return value}
                  }
                  return current;
                }
                function walk(value,key){
                  value=decode(value);
                  if(!value || typeof value!=='object') return null;
                  if(value.access_token){
                    var expires=Number(value.expires_at||0);
                    if(expires>0 && expires<100000000000) expires*=1000;
                    return {accessToken:String(value.access_token),refreshToken:String(value.refresh_token||''),expiresAt:expires,authUrl:'',origin:location.origin,storageKey:key||''};
                  }
                  if(value.session){var nested=walk(value.session,key);if(nested)return nested}
                  for(var child in value){if(value[child] && typeof value[child]==='object'){var found=walk(value[child],key);if(found)return found}}
                  return null;
                }
                function scan(storage){
                  var candidate=null;
                  for(var i=0;i<storage.length;i++){
                    var key=storage.key(i);seen.push(key);
                    var found=walk(storage.getItem(key),key);
                    if(found && !candidate) candidate=found;
                  }
                  if(candidate){candidate.keys=seen;return candidate}
                  return null;
                }
                function metadata(storage){
                  var names=[],fields=[];
                  for(var i=0;i<storage.length;i++){
                    var key=storage.key(i);names.push(key);
                    try{var value=decode(storage.getItem(key));if(value&&typeof value==='object'){Object.keys(value).forEach(function(f){if(fields.indexOf(f)<0)fields.push(f)})}}catch(e){}
                  }
                  return {names:names,fields:fields};
                }
                var localMeta=metadata(localStorage),sessionMeta=metadata(sessionStorage);
                var result=scan(localStorage)||scan(sessionStorage);
                var resources=performance.getEntriesByType('resource').map(function(x){return x.name}).filter(function(x){return /api|manga|obra|chapter|search|catalog/i.test(x)}).slice(-30);
                var testUrl=resources.find(function(x){return /api|manga|catalog/i.test(x)})||'';
                var loginForm=!!document.querySelector('input[type=email],input[type=password],form[action*="login"]');
                var logout=!!Array.from(document.querySelectorAll('button,a')).find(function(x){return /sair|logout|sign out/i.test(x.textContent||'')});
                var domAuthenticated=!loginForm && logout;
                var base={origin:location.origin,keys:seen,localKeys:localMeta.names,localFields:localMeta.fields,sessionKeys:sessionMeta.names,sessionFields:sessionMeta.fields,indexedDb:(window.__risentoons_idb||''),domAuthenticated:domAuthenticated,loginForm:loginForm,logoutFound:logout,resources:resources,requests:(window.__risentoons_requests||[]).map(function(x){return JSON.stringify(x)}) ,testUrl:testUrl};
                if(testUrl && !window.__risentoons_probe_started){
                  window.__risentoons_probe_started=true;
                  fetch(testUrl,{credentials:'include',redirect:'manual'}).then(function(r){window.__risentoons_probe={status:r.status,type:r.headers.get('content-type')||'',redirect:r.headers.get('location')||'',login:(r.url||'').indexOf('/login')>=0||r.status===401||r.status===403,catalog:(r.headers.get('content-type')||'').indexOf('json')>=0}}).catch(function(e){window.__risentoons_probe={status:'FAIL',type:'',redirect:'',login:false,catalog:false}});
                }
                if(window.__risentoons_probe){base.webHttp=String(window.__risentoons_probe.status);base.contentType=window.__risentoons_probe.type;base.redirect=window.__risentoons_probe.redirect;base.loginResponse=window.__risentoons_probe.login;base.catalogResponse=window.__risentoons_probe.catalog}
                if(indexedDB && indexedDB.databases){indexedDB.databases().then(function(d){window.__risentoons_idb=d.map(function(x){return (x.name||'(sem nome)')+' v'+(x.version||0)}).join(', ')||'nenhum detectado'}).catch(function(){window.__risentoons_idb='falha ao consultar'})}
                if(!result)return JSON.stringify(base);
                Object.keys(base).forEach(function(k){result[k]=base[k]});
                var entries=performance.getEntriesByType('resource');
                for(var j=0;j<entries.length;j++){var u=entries[j].name;if(u.indexOf('/auth/v1/')>=0){result.authUrl=u.split('/auth/v1/')[0]+'/auth/v1';break}}
                return JSON.stringify(result);
              } catch(e){return JSON.stringify({origin:location.origin,keys:[],localKeys:[],localFields:[],sessionKeys:[],sessionFields:[],indexedDb:'erro: '+String(e)})}
            })()
        """
    }
}
