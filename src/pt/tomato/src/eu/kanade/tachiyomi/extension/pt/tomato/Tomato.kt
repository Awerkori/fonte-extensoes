package eu.kanade.tachiyomi.extension.pt.tomato

import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.util.Log
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.lib.cookieinterceptor.CookieInterceptor
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.toJsonRequestBody
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Calendar
import java.util.Locale

@Source
abstract class Tomato :
    KeiSource(),
    ConfigurableSource {

    private val preferences: SharedPreferences by getPreferencesLazy()
    private val serverBootstrap by lazy { TomatoServerBootstrap(preferences) }
    private var loginResultReceiver: ResultReceiver? = null

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(5)
        .addNetworkInterceptor(
            CookieInterceptor(
                NINE_HOST,
                listOf(
                    "ninemanga_template_desk" to "yes",
                    "ninemanga_webp_valid" to "true",
                ),
            ),
        )
        .addInterceptor { chain ->
            val request = chain.request()
            Log.d(TAG, "TOMATO_DEBUG REQUEST method=${request.method} path=${request.url.encodedPath}")
            val response = chain.proceed(request)
            Log.d(TAG, "TOMATO_DEBUG RESPONSE path=${request.url.encodedPath} HTTP=${response.code}")
            if (request.url.host.isTomatoHost() && response.code in listOf(401, 403)) {
                preferences.edit().remove(PREF_TOKEN).apply()
                Log.d(TAG, "TOMATO_DEBUG AUTH session_invalid=true HTTP=${response.code}")
            }
            response
        }

    override fun Headers.Builder.configureHeaders(): Headers.Builder = this
        .set("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")

    override suspend fun getPopularManga(page: Int): MangasPage {
        if (sessionToken() == null) return loginRequiredPage()
        if (page != 1) return MangasPage(emptyList(), false)
        val section = getFeed().data.firstOrNull { it.type == POPULAR_SECTION_TYPE }
            ?: error("Seção Popular não encontrada no feed Tomato")
        val mangas = section.data.map { it.toSManga() }
        Log.d(TAG, "TOMATO_DEBUG FEED section=popular count=${mangas.size} page=$page")
        return MangasPage(mangas, false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        if (sessionToken() == null) return loginRequiredPage()
        if (page != 1) return MangasPage(emptyList(), false)
        val section = getFeed().data.firstOrNull { item ->
            item.type == NORMAL_SECTION_TYPE &&
                item.meta?.type != USER_HISTORY_META &&
                item.title.normalized().let { title ->
                    title.contains("recent") || title.contains("atualiza") || title.contains("lancamento")
                }
        } ?: error("Seção Recentes não encontrada no feed Tomato")
        val mangas = section.data.map { it.toSManga() }
        Log.d(TAG, "TOMATO_DEBUG FEED section=recentes count=${mangas.size} page=$page")
        return MangasPage(mangas, false)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val token = requireToken()
        val tags = filters.filterIsInstance<CategoryFilter>()
            .firstOrNull()
            ?.selectedNames
            .orEmpty()
            .takeIf { query.isBlank() && it.isNotEmpty() }
        val payload = SearchRequestDto(
            search = query.trim(),
            contentType = MANGA_CONTENT_TYPE,
            page = page - 1,
            tags = tags,
            token = token,
        )
        val rawResult = client.post(
            "${selectedApiBaseUrl()}$SEARCH_PATH",
            apiHeaders(),
            payload.toJsonRequestBody(),
        ).parseAs<SearchResponseDto>().result
        val result = rawResult.filter { it.type == MANGA_CONTENT_TYPE }
        Log.d(
            TAG,
            "TOMATO_DEBUG SEARCH page=${page - 1} rawCount=${rawResult.size} " +
                "mangaCount=${result.size} filtered=${tags != null}",
        )
        return MangasPage(result.map { it.toSManga() }, rawResult.size >= SEARCH_PAGE_SIZE)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val id = url.pathSegments.getOrNull(2)?.toLongOrNull()
            ?.takeIf { url.pathSegments.getOrNull(0) == "v2" && url.pathSegments.getOrNull(1) == "manga" }
            ?: return null
        val manga = SManga.create().apply { this.url = mangaUrl(id) }
        return fetchMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false)
            .manga
            .apply { initialized = true }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        if (manga.url == LOGIN_REQUIRED_URL) return SMangaUpdate(manga, emptyList())
        val mangaId = manga.tomatoId()
        val core = queryManga(mangaId)
        Log.d(TAG, "TOMATO_DEBUG DETAILS mangaId=$mangaId source=${core.source}")

        val nineDocument = if (core.source == SOURCE_NINE && (fetchDetails || fetchChapters)) {
            client.get(nineMangaUrl(core.url), externalHeaders()).asJsoup()
        } else {
            null
        }

        val updatedManga = manga.apply {
            memo = buildJsonObject {
                put(MEMO_SOURCE, core.source)
                put(MEMO_REMOTE_URL, core.url)
                put(MEMO_SOURCE_URL, core.sourceUrl)
            }
            if (fetchDetails) {
                title = core.name
                author = core.author
                thumbnail_url = core.cover
                status = SManga.UNKNOWN
                val metadata = client.get(
                    "${selectedApiBaseUrl()}/v2/manga/$mangaId",
                    apiHeaders(nativeClient = true),
                ).parseAs<MangaMetadataResponseDto>().details
                description = metadata.description
                genre = metadata.genre
                when (core.source) {
                    SOURCE_NINE -> applyNineDetails(requireNotNull(nineDocument))
                    SOURCE_MANGADEX -> applyMangaDexDetails(fetchMangaDexDetails(core.url))
                }
                initialized = true
            }
        }

        val updatedChapters = if (fetchChapters) {
            when (core.source) {
                SOURCE_NINE -> parseNineChapters(requireNotNull(nineDocument))
                SOURCE_MANGADEX -> fetchMangaDexChapters(core.url)
                SOURCE_MANGALIVRE -> fetchMangaLivreChapters(core.url)
                SOURCE_TOMATO -> fetchTomatoChapters(mangaId)
                else -> error("Fonte de mangá Tomato desconhecida: ${core.source}")
            }.also {
                Log.d(TAG, "TOMATO_DEBUG CHAPTERS mangaId=$mangaId source=${core.source} count=${it.size}")
            }
        } else {
            chapters
        }
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val source = chapter.memo[MEMO_SOURCE]?.jsonPrimitive?.intOrNull
            ?: error("Fonte do capítulo Tomato ausente")
        val remoteUrl = chapter.memo[MEMO_REMOTE_URL]?.jsonPrimitive?.contentOrNull
            ?: error("URL do capítulo Tomato ausente")
        val pages = when (source) {
            SOURCE_NINE -> fetchScrapedPages(remoteUrl, SOURCE_NINE)
            SOURCE_MANGADEX -> fetchMangaDexPages(remoteUrl)
            SOURCE_MANGALIVRE -> fetchScrapedPages(remoteUrl, SOURCE_MANGALIVRE)
            SOURCE_TOMATO -> fetchTomatoPages(remoteUrl)
            else -> error("Fonte de páginas Tomato desconhecida: $source")
        }
        Log.d(TAG, "TOMATO_DEBUG PAGES chapterId=${chapter.url.substringAfterLast('/')} source=$source count=${pages.size}")
        return pages
    }

    override fun imageRequest(page: Page): Request {
        val imageHeaders = Headers.Builder()
            .set("Accept", "image/webp,image/png;q=0.9,image/jpeg,*/*;q=0.8")
            .set("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
            .apply { page.url.takeIf(String::isNotBlank)?.let { set("Referer", it) } }
            .build()
        return GET(requireNotNull(page.imageUrl), imageHeaders)
    }

    override val supportsFilterFetching: Boolean get() = true

    override suspend fun fetchFilterData(): JsonElement = client.get(
        "${selectedApiBaseUrl()}$CATEGORIES_PATH",
        apiHeaders(),
    ).parseAs<CategoriesResponseDto>().categories.map(CategoryDto::name).toJsonElement()

    override fun getFilterList(data: JsonElement?): FilterList = getFilters(
        data?.parseAs<List<String>>().orEmpty(),
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        lateinit var accountPreference: EditTextPreference
        lateinit var logoutPreference: EditTextPreference

        fun refreshAccountState() {
            val connected = sessionToken() != null
            accountPreference.summary = if (connected) "Conectado" else "Não conectado — toque para entrar"
            logoutPreference.setEnabled(connected)
        }

        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                if (resultCode != TomatoLoginActivity.RESULT_LOGIN_SUCCESS) return
                val token = resultData?.getString(TomatoLoginActivity.EXTRA_SESSION_TOKEN)
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?: return
                preferences.edit().putString(PREF_TOKEN, token).apply()
                Log.d(TAG, "TOMATO_DEBUG AUTH session_saved=true")
                refreshAccountState()
            }
        }

        accountPreference = EditTextPreference(screen.context).apply {
            key = PREF_ACCOUNT_ACTION
            title = "Conta Tomato"
            setOnPreferenceClickListener {
                loginResultReceiver = receiver
                val context = screen.context
                Thread {
                    val host = runCatching(::selectedApiBaseUrl)
                    Handler(Looper.getMainLooper()).post {
                        host.onFailure {
                            Toast.makeText(context, "Servidor Tomato temporariamente indisponível.", Toast.LENGTH_LONG).show()
                            return@post
                        }
                        context.startActivity(
                            Intent().setComponent(ComponentName(EXTENSION_PACKAGE, TomatoLoginActivity::class.java.name))
                                .putExtra(TomatoLoginActivity.EXTRA_RESULT_RECEIVER, receiver)
                                .putExtra(TomatoLoginActivity.EXTRA_API_HOST, host.getOrThrow())
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }.start()
                true
            }
        }.also(screen::addPreference)

        logoutPreference = EditTextPreference(screen.context).apply {
            key = PREF_LOGOUT_ACTION
            title = "Sair"
            summary = "Apagar sessão da conta Tomato"
            setOnPreferenceClickListener {
                preferences.edit().remove(PREF_TOKEN).apply()
                Log.d(TAG, "TOMATO_DEBUG AUTH logout")
                refreshAccountState()
                true
            }
        }.also(screen::addPreference)
        refreshAccountState()
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String {
        val source = chapter.memo[MEMO_SOURCE]?.jsonPrimitive?.intOrNull
        val remote = chapter.memo[MEMO_REMOTE_URL]?.jsonPrimitive?.contentOrNull ?: return chapter.url
        return when (source) {
            SOURCE_NINE -> remote.absoluteUrl(NINE_BASE_URL)
            SOURCE_MANGADEX -> "https://mangadex.org/chapter/$remote"
            SOURCE_MANGALIVRE -> remote.absoluteUrl(MANGA_LIVRE_BASE_URL)
            else -> "${serverBootstrap.persistedHost()}/manga/pages/query/$remote"
        }
    }

    private suspend fun getFeed(): FeedResponseDto = client.get(
        "${selectedApiBaseUrl()}$FEED_PATH",
        apiHeaders(),
    ).parseAs()

    private suspend fun queryManga(id: Long): MangaQueryDetailsDto {
        val token = requireToken()
        return client.post(
            "${selectedApiBaseUrl()}$QUERY_PATH",
            apiHeaders(),
            MangaQueryRequestDto(id, token).toJsonRequestBody(),
        ).parseAs<MangaQueryResponseDto>().details
    }

    private suspend fun fetchTomatoChapters(mangaId: Long): List<SChapter> = client.get(
        "${selectedApiBaseUrl()}/manga/chapters/query/$mangaId",
        apiHeaders(nativeClient = true),
    ).parseAs<ChaptersResponseDto>().data.map { chapter ->
        val remote = when {
            chapter.sourceUrl.contains("mangadex.org") -> chapter.sourceUrl.substringAfter("chapter/")
            chapter.sourceUrl.isEmpty() -> chapter.id.toString()
            else -> chapter.sourceUrl
        }
        SChapter.create().apply {
            url = "/chapter/${chapter.id}"
            name = chapter.name
            chapter_number = chapter.number
            memo = chapterMemo(chapter.source, remote)
        }
    }

    private suspend fun fetchTomatoPages(chapterRemote: String): List<Page> = client.get(
        "${selectedApiBaseUrl()}/manga/pages/query/$chapterRemote",
        apiHeaders(nativeClient = true),
    ).parseAs<PagesResponseDto>().data.mapIndexed { index, page ->
        Page(index, url = "", imageUrl = page.pageUrl)
    }

    private suspend fun fetchMangaDexChapters(mangaRemote: String): List<SChapter> {
        val mangaId = mangaRemote.mangaDexId()
        val url = "$MANGADEX_API/manga/$mangaId/feed".toHttpUrl().newBuilder()
            .addQueryParameter("limit", "200")
            .addQueryParameter("includes[]", "scanlation_group")
            .addQueryParameter("order[volume]", "desc")
            .addQueryParameter("order[chapter]", "desc")
            .addQueryParameter("offset", "0")
            .addQueryParameter("contentRating[]", "safe")
            .addQueryParameter("contentRating[]", "suggestive")
            .addQueryParameter("contentRating[]", "erotica")
            .addQueryParameter("contentRating[]", "pornographic")
            .addQueryParameter("translatedLanguage[]", "pt-br")
            .build()
        return client.get(url.toString(), externalHeaders()).parseAs<MangaDexFeedResponseDto>().data.map { item ->
            val number = item.attributes.chapter?.toFloatOrNull() ?: -1f
            val itemTitle = item.attributes.title?.takeIf(String::isNotBlank)
            SChapter.create().apply {
                this.url = "/chapter/${item.id}"
                name = buildString {
                    append("Capítulo ", item.attributes.chapter ?: "?")
                    itemTitle?.let { append(" - ", it) }
                }
                chapter_number = number
                date_upload = (item.attributes.readableAt ?: item.attributes.publishAt)
                    ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrDefault(0L) }
                    ?: 0L
                scanlator = item.relationships.firstOrNull { it.type == "scanlation_group" }?.attributes?.name
                memo = chapterMemo(SOURCE_MANGADEX, item.id)
            }
        }
    }

    private suspend fun fetchMangaDexPages(chapterId: String): List<Page> {
        val response = client.get(
            "$MANGADEX_API/at-home/server/${chapterId.substringAfterLast('/')}?forcePort443=false",
            externalHeaders(),
        ).parseAs<MangaDexAtHomeDto>()
        return response.chapter.data.mapIndexed { index, fileName ->
            Page(
                index,
                url = "https://mangadex.org/",
                imageUrl = "$MANGADEX_UPLOADS/data/${response.chapter.hash}/$fileName",
            )
        }
    }

    private suspend fun fetchMangaDexDetails(mangaRemote: String): MangaDexMangaDto {
        val url = "$MANGADEX_API/manga/${mangaRemote.mangaDexId()}".toHttpUrl().newBuilder()
            .addQueryParameter("includes[]", "artist")
            .addQueryParameter("includes[]", "author")
            .addQueryParameter("includes[]", "cover_art")
            .build()
        return client.get(url.toString(), externalHeaders()).parseAs<MangaDexMangaResponseDto>().data
    }

    private fun SManga.applyMangaDexDetails(details: MangaDexMangaDto) {
        description = details.attributes.description.localized() ?: description
        genre = details.attributes.tags.mapNotNull { it.attributes.name.localized() }.joinToString().ifBlank { genre }
        author = details.relationships.firstOrNull { it.type == "author" }?.attributes?.name ?: author
        artist = details.relationships.firstOrNull { it.type == "artist" }?.attributes?.name
        status = when (details.attributes.status) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            "cancelled" -> SManga.CANCELLED
            else -> status
        }
        details.relationships.firstOrNull { it.type == "cover_art" }?.attributes?.fileName?.let {
            thumbnail_url = "$MANGADEX_UPLOADS/covers/${details.id}/$it"
        }
    }

    private suspend fun fetchMangaLivreChapters(mangaRemote: String): List<SChapter> {
        val seriesId = mangaRemote.split('/').getOrNull(3)
            ?: error("ID MangaLivre ausente")
        val response = client.get(
            "$MANGA_LIVRE_BASE_URL/series/chapters_list.json?page=1&id_serie=$seriesId",
            externalHeaders(),
        ).parseAs<MangaLivreChaptersDto>()
        return response.chapters.map { chapter ->
            val releaseElement = chapter.releases.values.firstOrNull()
                ?: error("Release MangaLivre ausente")
            val releaseJson = if (releaseElement is JsonPrimitive && releaseElement.isString) {
                releaseElement.content
            } else {
                releaseElement.toString()
            }
            val remote = releaseJson.parseAs<MangaLivreReleaseDto>().link
            SChapter.create().apply {
                url = "/chapter/${chapter.id}"
                name = "${chapter.name} ${chapter.number}"
                chapter_number = chapter.number.toFloatOrNull() ?: -1f
                memo = chapterMemo(SOURCE_MANGALIVRE, remote)
            }
        }
    }

    private fun parseNineChapters(document: Document): List<SChapter> = document
        .select("div.chapterbox ul.sub_vol_ul > li")
        .asReversed()
        .mapIndexed { index, row ->
            val link = row.selectFirst("a.chapter_list_a[href]")
                ?: error("Link de capítulo NineManga ausente")
            val remote = link.attr("href").replace("%20", " ")
            SChapter.create().apply {
                url = remote
                name = link.text()
                chapter_number = (index + 1).toFloat()
                date_upload = parseNineDate(row.selectFirst("span")?.text().orEmpty())
                memo = chapterMemo(SOURCE_NINE, remote)
            }
        }

    private fun SManga.applyNineDetails(document: Document) {
        val info = document.selectFirst("div.manga div.bookintro") ?: return
        author = info.getElementsByAttributeValue("itemprop", "author").firstOrNull()?.text() ?: author
        genre = info.getElementsByAttributeValue("itemprop", "genre").firstOrNull()
            ?.select("a")
            ?.joinToString { it.text() }
            ?.ifBlank { genre }
            ?: genre
        description = info.getElementsByAttributeValue("itemprop", "description").firstOrNull()
            ?.text()
            ?.substringAfter(':')
            ?.trim()
            ?.ifBlank { description }
            ?: description
        val state = info.select("li a.red").text().normalized()
        status = when {
            state.contains("complet") || state.contains("conclu") -> SManga.COMPLETED
            state.contains("ongoing") || state.contains("andamento") -> SManga.ONGOING
            else -> status
        }
    }

    private suspend fun fetchScrapedPages(chapterRemote: String, source: Int): List<Page> {
        val base = if (source == SOURCE_NINE) NINE_BASE_URL else MANGA_LIVRE_BASE_URL
        val chapterUrl = chapterRemote.absoluteUrl(base)
        val document = client.get(chapterUrl, externalHeaders()).asJsoup()
        val optionUrls = document.select("#page option[value]").map { option ->
            option.absUrl("value").ifBlank { option.attr("value").absoluteUrl(base) }
        }
        return optionUrls.mapIndexed { index, optionUrl ->
            val imageUrl = client.get(optionUrl, externalHeaders()).asJsoup()
                .selectFirst("a.pic_download[href]")
                ?.absUrl("href")
                ?.takeIf(String::isNotBlank)
                ?: error("Imagem da página não encontrada")
            val referer = if (source == SOURCE_NINE) optionUrl else chapterUrl
            Page(index, url = referer, imageUrl = imageUrl)
        }
    }

    private fun parseNineDate(raw: String): Long {
        runCatching { SimpleDateFormat("MMM d, yyyy", Locale.ENGLISH).parse(raw)?.time }
            .getOrNull()
            ?.let { return it }
        val parts = raw.split(' ')
        if (parts.size != 3) return 0L
        val amount = parts[0].toIntOrNull() ?: return 0L
        val field = when (parts[1].normalized()) {
            "minuto", "minutos", "minute", "minutes" -> Calendar.MINUTE
            "hora", "horas", "hour", "hours" -> Calendar.HOUR
            "dia", "dias", "day", "days" -> Calendar.DAY_OF_MONTH
            else -> return 0L
        }
        return Calendar.getInstance().apply { add(field, -amount) }.timeInMillis
    }

    private fun FeedMangaDto.toSManga() = SManga.create().apply {
        url = mangaUrl(id)
        title = name
        thumbnail_url = thumbnail
    }

    private fun SearchMangaDto.toSManga() = SManga.create().apply {
        url = mangaUrl(id)
        title = name
        author = this@toSManga.author
        thumbnail_url = image
    }

    private fun chapterMemo(source: Int, remoteUrl: String) = buildJsonObject {
        put(MEMO_SOURCE, source)
        put(MEMO_REMOTE_URL, remoteUrl)
    }

    private fun loginRequiredPage() = MangasPage(
        listOf(
            SManga.create().apply {
                url = LOGIN_REQUIRED_URL
                title = "Login necessário"
                description = "Abra as configurações da extensão Tomato e entre com sua conta."
            },
        ),
        false,
    )

    private fun apiHeaders(nativeClient: Boolean = false): Headers = headers.newBuilder()
        .set("Accept", if (nativeClient) "application/json" else "application/json, text/plain, */*")
        .set("User-Agent", if (nativeClient) APP_USER_AGENT else OFFICIAL_OKHTTP_USER_AGENT)
        .set("Authorization", "Bearer ${requireToken()}")
        .set("request-time", System.currentTimeMillis().toString())
        .build()

    private fun externalHeaders() = headers.newBuilder()
        .set("User-Agent", OFFICIAL_OKHTTP_USER_AGENT)
        .build()

    private fun selectedApiBaseUrl() = serverBootstrap.selectedHost()
    private fun sessionToken() = preferences.getString(PREF_TOKEN, null)
        ?.trim()
        ?.removePrefix("Bearer ")
        ?.takeIf(String::isNotEmpty)
    private fun requireToken() = sessionToken()
        ?: error("Login necessário. Abra as configurações da extensão Tomato e entre com sua conta.")
    private fun SManga.tomatoId() = url.substringBefore('?').trimEnd('/').substringAfterLast('/').toLongOrNull()
        ?: error("ID de mangá Tomato inválido")
    private fun mangaUrl(id: Long) = "/v2/manga/$id"
    private fun String.mangaDexId() = substringAfter("/title/", this).substringBefore('/').substringAfterLast('/')
    private fun Map<String, String>.localized() = get("pt-br") ?: get("pt") ?: get("en") ?: values.firstOrNull()
    private fun String.absoluteUrl(base: String) = toHttpUrlOrNull()?.toString()
        ?: base.toHttpUrl().resolve(this)?.toString()
        ?: error("URL externa inválida")
    private fun nineMangaUrl(remote: String): String {
        val url = remote.absoluteUrl(NINE_BASE_URL).toHttpUrl().newBuilder()
        if (url.build().queryParameter("waring") == null) url.addQueryParameter("waring", "1")
        return url.build().toString()
    }
    private fun String.normalized() = Normalizer.normalize(lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
    private fun String.isTomatoHost() = this == PROD_HOST || this == EDGE_HOST

    companion object {
        private const val TAG = "Tomato"
        private const val EXTENSION_PACKAGE = "eu.kanade.tachiyomi.extension.pt.tomato"
        private const val PROD_HOST = "prod-api.tomatoanimes.com"
        private const val EDGE_HOST = "edge.betomato.com"
        private const val NINE_HOST = "br.ninemanga.com"
        private const val NINE_BASE_URL = "https://br.ninemanga.com"
        private const val MANGADEX_API = "https://api.mangadex.org"
        private const val MANGADEX_UPLOADS = "https://uploads.mangadex.org"
        private const val MANGA_LIVRE_BASE_URL = "https://mangalivre.net"
        private const val APP_USER_AGENT = "tomato-android"
        private const val OFFICIAL_OKHTTP_USER_AGENT = "okhttp/4.11.0"
        private const val FEED_PATH = "/v2/manga/feed"
        private const val SEARCH_PATH = "/v2/content/search"
        private const val MANGA_CONTENT_TYPE = "manga"
        private const val CATEGORIES_PATH = "/v2/content/categories"
        private const val QUERY_PATH = "/manga/query/"
        private const val LOGIN_REQUIRED_URL = "/tomato-login-required"
        private const val PREF_TOKEN = "tomato_official_session_token_v1"
        private const val PREF_ACCOUNT_ACTION = "tomato_account_action_v2"
        private const val PREF_LOGOUT_ACTION = "tomato_logout_action_v2"
        private const val MEMO_SOURCE = "source"
        private const val MEMO_REMOTE_URL = "remoteUrl"
        private const val MEMO_SOURCE_URL = "sourceUrl"
        private const val USER_HISTORY_META = "USER_HISTORY"
        private const val POPULAR_SECTION_TYPE = 2
        private const val NORMAL_SECTION_TYPE = 1
        private const val SEARCH_PAGE_SIZE = 25
        private const val SOURCE_NINE = 1
        private const val SOURCE_MANGADEX = 2
        private const val SOURCE_MANGALIVRE = 3
        private const val SOURCE_TOMATO = 4
    }
}
