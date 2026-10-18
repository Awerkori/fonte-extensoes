package eu.kanade.tachiyomi.extension.pt.lycantoons

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import keiyoushi.utils.runWebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.Dns
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.parser.Parser
import java.io.IOException
import java.net.Inet4Address
import java.util.Collections
import kotlin.time.Duration.Companion.seconds

@Source
abstract class LycanToons : KeiSource() {

    override fun OkHttpClient.Builder.configureClient() = rateLimit(1, 2.seconds) {
        !it.host.startsWith("cdn.") && !it.encodedPath.contains("/file/")
    }.dns { hostname ->
        val addresses = Dns.SYSTEM.lookup(hostname)
        addresses.sortedBy { it !is Inet4Address }
    }.addInterceptor(LycanToonsInterceptor(baseUrl))

    override fun Headers.Builder.configureHeaders(): Headers.Builder = this
        .removeAll("Origin")
        .set("User-Agent", DEFAULT_USER_AGENT)
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .set("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
        .set("Referer", "$baseUrl/")

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    private class SearchCacheEntry(val page: MangasPage, val timestamp: Long)

    private val searchCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, SearchCacheEntry>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SearchCacheEntry>?): Boolean = size > 32
        },
    )

    // =====================Popular=====================

    override suspend fun getPopularManga(page: Int): MangasPage = withContext(Dispatchers.IO) {
        if (page > 1) {
            return@withContext MangasPage(emptyList(), false)
        }

        var html = try {
            val request = GET("$baseUrl/ranking/obras", headers)
            val response = client.newCall(request).execute()
            if (response.isSuccessful) response.body.string() else ""
        } catch (e: LycanAntiAbuseException) {
            throw e
        } catch (_: Throwable) {
            ""
        }

        if (html.isNotEmpty() && isAntiAbuseBlock(html)) {
            throw LycanAntiAbuseException()
        }

        if (html.isBlank() || html.contains("challenges.cloudflare.com")) {
            try {
                html = webFetchHtml("$baseUrl/ranking/obras")
            } catch (e: LycanAntiAbuseException) {
                throw e
            } catch (_: Throwable) {}
        }

        val match = RANKING_ALL_REGEX.find(html) ?: RANKING_WEEKLY_REGEX.find(html)
        val jsonString = match?.groupValues?.get(1)?.replace("\\\"", "\"")?.replace("\\\\", "\\")
            ?: return@withContext MangasPage(emptyList(), false)

        val series = json.decodeFromString<List<RankingRowDto>>(jsonString)
        MangasPage(series.map { it.toSManga() }, false)
    }

    // =====================Latest=====================

    override suspend fun getLatestUpdates(page: Int): MangasPage = getSearchMangaList(page, "", FilterList())

    // =====================Search=====================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = withContext(Dispatchers.IO) {
        var search = query.trim()
        val tags = filters.selectedTags().toMutableList()

        val genreEntry = tagMapping.entries.find { it.value.equals(query, ignoreCase = true) }
        if (genreEntry != null) {
            tags.add(genreEntry.key)
            search = ""
        }

        val selectedType = filters.valueOrEmpty<SeriesTypeFilter>().ifEmpty { null }
        val selectedStatus = filters.valueOrEmpty<StatusFilter>().ifEmpty { null }
        val genreList = tags.ifEmpty { null }

        val cacheKey = "$page|$search|$selectedType|$selectedStatus|${tags.sorted().joinToString(",")}"
        val cached = searchCache[cacheKey]
        if (cached != null && (System.currentTimeMillis() - cached.timestamp) < SEARCH_CACHE_TTL) {
            return@withContext cached.page
        }

        val requestPayload = SeriesSearchRequest(
            search = search,
            limit = PAGE_LIMIT,
            page = page,
            seriesType = selectedType,
            status = selectedStatus,
            genre = genreList,
        )
        val jsonPayload = json.encodeToString(requestPayload)

        var responseBody: String? = null

        // 1. Requisição direta via OkHttp (com rateLimit e DNS IPv4)
        try {
            val req = POST(
                url = "$baseUrl/api/series",
                headers = headers.newBuilder()
                    .set("Accept", "application/json, text/plain, */*")
                    .set("Content-Type", "application/json")
                    .set("Referer", "$baseUrl/series")
                    .set("Origin", baseUrl)
                    .set("Sec-Fetch-Site", "same-origin")
                    .set("Sec-Fetch-Mode", "cors")
                    .set("Sec-Fetch-Dest", "empty")
                    .build(),
                body = jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaType()),
            )
            val resp = client.newCall(req).execute()
            if (resp.isSuccessful) {
                val body = resp.body.string()
                if (isAntiAbuseBlock(body)) {
                    throw LycanAntiAbuseException()
                }
                responseBody = body
            }
        } catch (e: LycanAntiAbuseException) {
            throw e
        } catch (_: Throwable) {
            // Cloudflare Turnstile / network challenge on mobile
        }

        // 2. Fallback via WebView (executa mesmo origin fetch com Turnstile clearance)
        if (responseBody.isNullOrBlank()) {
            try {
                responseBody = webFetchPost("$baseUrl/api/series", jsonPayload)
            } catch (e: LycanAntiAbuseException) {
                throw e
            } catch (_: Throwable) {
                // WebView indisponível ou timeout
            }
        }

        // 3. Fallback para HTML do Next.js se for listagem sem filtros complexos
        if (responseBody.isNullOrBlank() && search.isEmpty() && genreList == null && selectedType == null && selectedStatus == null) {
            try {
                val req = GET("$baseUrl/series?page=$page", headers)
                val resp = client.newCall(req).execute()
                val html = resp.body.string()
                if (isAntiAbuseBlock(html)) {
                    throw LycanAntiAbuseException()
                }
                val match = SERIES_REGEX.find(html)
                if (match != null) {
                    val jsonString = match.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
                    val series = json.decodeFromString<List<SeriesDto>>(jsonString)
                    val totalPages = TOTAL_PAGES_REGEX.find(html)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val result = MangasPage(series.map { it.toSManga() }, page < totalPages)
                    searchCache[cacheKey] = SearchCacheEntry(result, System.currentTimeMillis())
                    return@withContext result
                }
            } catch (e: LycanAntiAbuseException) {
                throw e
            } catch (_: Throwable) {}
        }

        if (!responseBody.isNullOrBlank()) {
            try {
                val searchResponse = json.decodeFromString<SeriesSearchResponse>(responseBody)
                val mangas = searchResponse.series.map { it.toSManga() }
                val hasNext = searchResponse.pagination?.hasNextPage ?: (mangas.size >= PAGE_LIMIT)
                val result = MangasPage(mangas, hasNext)
                searchCache[cacheKey] = SearchCacheEntry(result, System.currentTimeMillis())
                return@withContext result
            } catch (_: Exception) {}
        }

        MangasPage(emptyList(), false)
    }

    override fun getFilterList(data: JsonElement?): FilterList = LycanToonsFilters.get()

    // =====================Details & Chapters=====================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = withContext(Dispatchers.IO) {
        val slug = manga.slug()

        var fetchedChapters: List<SChapter> = emptyList()

        // 1. If fetching chapters, attempt direct internal API call first (take=500 returns all chapters instantly with 0 Cloudflare challenge on JVM/desktop)
        if (fetchChapters) {
            try {
                val apiReq = GET(
                    url = "$baseUrl/api/series/$slug/chapters?skip=0&take=500",
                    headers = headers.newBuilder()
                        .set("Accept", "application/json, text/plain, */*")
                        .set("Referer", "$baseUrl/series/$slug")
                        .set("Sec-Fetch-Site", "same-origin")
                        .set("Sec-Fetch-Mode", "cors")
                        .set("Sec-Fetch-Dest", "empty")
                        .build(),
                )
                val apiResp = client.newCall(apiReq).execute()
                if (apiResp.isSuccessful) {
                    val apiBody = apiResp.body.string().trim()
                    if (apiBody.startsWith("{")) {
                        val apiData = json.decodeFromString<ChapterApiResponse>(apiBody)
                        if (apiData.chapters.isNotEmpty()) {
                            fetchedChapters = apiData.chapters
                                .map { it.toSChapter(slug) }
                                .sortedByDescending { it.chapter_number }
                        }
                    }
                }
            } catch (e: LycanAntiAbuseException) {
                throw e
            } catch (_: Throwable) {
                // OkHttp challenged or network error
            }
        }

        // 2. Fetch HTML for details (or for chapters fallback if API call didn't yield chapters)
        var html = ""
        val needsHtml = fetchDetails || (fetchChapters && fetchedChapters.isEmpty())

        if (needsHtml) {
            try {
                val req = GET(
                    url = "$baseUrl/series/$slug",
                    headers = headers.newBuilder()
                        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .set("Referer", "$baseUrl/")
                        .set("Sec-Fetch-Site", "same-origin")
                        .set("Sec-Fetch-Mode", "navigate")
                        .set("Sec-Fetch-Dest", "document")
                        .build(),
                )
                val resp = client.newCall(req).execute()
                if (resp.isSuccessful) {
                    val body = resp.body.string()
                    if (isAntiAbuseBlock(body)) {
                        throw LycanAntiAbuseException()
                    }
                    if (!body.contains("challenges.cloudflare.com") &&
                        !body.contains("<title>Just a moment...") &&
                        (body.contains("application/ld+json") || body.contains("lycantoons"))
                    ) {
                        html = body
                    }
                }
            } catch (e: LycanAntiAbuseException) {
                throw e
            } catch (_: Throwable) {
                // OkHttp challenged / CloudflareException
            }

            // Fallback via WebView on Android (bypasses Cloudflare Turnstile natively)
            if (html.isBlank()) {
                try {
                    html = webFetchHtml("$baseUrl/series/$slug")
                } catch (e: LycanAntiAbuseException) {
                    throw e
                } catch (_: Throwable) {
                    // WebView not available (e.g. Suwayomi JVM) or timeout
                }
            }
        }

        val updatedDetails = if (fetchDetails && html.isNotBlank()) {
            SManga.create().apply {
                title = OG_TITLE_REGEX.find(html)?.groupValues?.get(1)?.let { Parser.unescapeEntities(it, false) } ?: manga.title
                thumbnail_url = normalizeImageUrl(OG_IMAGE_REGEX.find(html)?.groupValues?.get(1), baseUrl) ?: manga.thumbnail_url

                // Parse Book schema from JSON-LD for untruncated synopsis and genres
                var parsedDesc: String? = null
                var parsedGenres: List<String>? = null

                for (m in JSON_LD_REGEX.findAll(html)) {
                    val rawJson = m.groupValues[1].trim()
                    if (rawJson.contains(""""@type"\s*:\s*"Book"""".toRegex())) {
                        try {
                            val book = json.decodeFromString<BookSchemaDto>(rawJson)
                            if (!book.description.isNullOrBlank() && !isRscPointer(book.description)) {
                                parsedDesc = Parser.unescapeEntities(book.description.trim(), false)
                            }
                            val bg = book.getGenres()
                            if (bg.isNotEmpty()) {
                                parsedGenres = bg
                            }
                            break
                        } catch (_: Exception) {}
                    }
                }

                if (parsedDesc == null) {
                    val ogDesc = OG_DESC_REGEX.find(html)?.groupValues?.get(1)
                    val metaDesc = META_DESC_REGEX.find(html)?.groupValues?.get(1)
                    parsedDesc = cleanSynopsis(ogDesc) ?: cleanSynopsis(metaDesc)
                }

                description = parsedDesc ?: cleanSynopsis(manga.description)

                genre = if (!parsedGenres.isNullOrEmpty()) {
                    parsedGenres.map { tagMapping[it] ?: it }.joinToString()
                } else {
                    val genresMatch = GENRES_REGEX.find(html)
                    if (genresMatch != null) {
                        val g = genresMatch.groupValues[1]
                        """"([^"]+)"""".toRegex().findAll(g).map { it.groupValues[1] }
                            .map { tagMapping[it] ?: it }
                            .joinToString()
                    } else {
                        manga.genre
                    }
                }

                status = when (STATUS_REGEX.find(html)?.groupValues?.get(1)?.lowercase()) {
                    "ongoing" -> SManga.ONGOING
                    "completed" -> SManga.COMPLETED
                    "hiatus" -> SManga.ON_HIATUS
                    "cancelled" -> SManga.CANCELLED
                    else -> manga.status
                }
                initialized = true
            }
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters) {
            if (fetchedChapters.isEmpty() && html.isNotBlank()) {
                val chaptersMatch = CHAPTERS_REGEX.find(html) ?: CAPITULOS_REGEX.find(html)
                if (chaptersMatch != null) {
                    val jsonString = chaptersMatch.groupValues[1]
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\")
                    try {
                        fetchedChapters = json.decodeFromString<List<ChapterDto>>(jsonString)
                            .map { it.toSChapter(slug) }
                            .sortedByDescending { it.chapter_number }
                    } catch (_: Exception) {}
                }
            }

            if (fetchedChapters.isNotEmpty()) fetchedChapters else chapters
        } else {
            chapters
        }

        SMangaUpdate(updatedDetails, updatedChapters)
    }

    // =====================Pages========================

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        var capituloId = chapter.url.substringAfter("?capituloId=", "")
            .ifEmpty { chapter.url.substringAfter("&capituloId=", "") }
            .substringBefore("&")

        if (capituloId.isEmpty()) {
            val chapterPageUrl = "$baseUrl${chapter.url}"
            var html = try {
                val req = GET(chapterPageUrl, headers)
                val resp = client.newCall(req).execute()
                if (resp.isSuccessful) resp.body.string() else ""
            } catch (e: LycanAntiAbuseException) {
                throw e
            } catch (_: Throwable) {
                ""
            }

            if (html.isNotEmpty() && isAntiAbuseBlock(html)) {
                throw LycanAntiAbuseException()
            }

            if (html.isEmpty()) {
                try {
                    html = webFetchHtml(chapterPageUrl)
                } catch (e: LycanAntiAbuseException) {
                    throw e
                } catch (_: Throwable) {}
            }

            capituloId = EXTRACT_CAPITULO_ID_REGEX.find(html)?.groupValues?.get(1) ?: ""
        }

        if (capituloId.isEmpty()) {
            throw Exception("Nenhuma página encontrada!")
        }

        val apiUrl = "$baseUrl/api/chapters/$capituloId/view-pages"
        var pages = emptyList<String>()

        // 1. Requisição direta via OkHttp (compatível com Suwayomi + FlareSolverr / proxy)
        try {
            val req = GET(
                url = apiUrl,
                headers = headers.newBuilder()
                    .set("Accept", "application/json, text/plain, */*")
                    .set("Referer", "$baseUrl${chapter.url}")
                    .build(),
            )
            val response = client.newCall(req).execute()
            if (response.isSuccessful) {
                val body = response.body.string()
                if (isAntiAbuseBlock(body)) {
                    throw LycanAntiAbuseException()
                }
                pages = extractPageUrls(body, baseUrl, json)
            }
        } catch (e: LycanAntiAbuseException) {
            throw e
        } catch (_: Throwable) {
            // Se falhar via OkHttp (ex: 403 Cloudflare no Android sem FlareSolverr), tenta fallback
        }

        // 2. Fallback via WebView (funciona no Android / Mobile contornando Cloudflare com same-origin fetch)
        if (pages.isEmpty()) {
            try {
                val webBody = webFetch(apiUrl)
                pages = extractPageUrls(webBody, baseUrl, json)
            } catch (e: LycanAntiAbuseException) {
                throw e
            } catch (_: Throwable) {
                // WebView não disponível no ambiente (ex: Suwayomi sem WebView nativo) ou erro
            }
        }

        if (pages.isNotEmpty()) {
            return@withContext pages.mapIndexed { index, imageUrl ->
                Page(index, imageUrl = imageUrl)
            }
        }

        throw Exception("Nenhuma página encontrada!")
    }

    private suspend fun webFetch(url: String): String = runWebView(timeout = 30.seconds) {
        userAgent = headers["User-Agent"]!!
        poll(1.seconds) {
            evaluateJs(
                """
                (function() {
                    if (document.title.indexOf('Just a moment') !== -1) return;
                    if (document.title.indexOf('Bloqueado') !== -1 || (document.body && document.body.innerText.indexOf('Acesso bloqueado temporariamente') !== -1)) {
                        window.__blocked = true;
                        return;
                    }
                    if (window.__fetching) return;
                    window.__fetching = true;
                    fetch('$url')
                        .then(function(res) { return res.text(); })
                        .then(function(text) {
                            window.__fetchResult = text;
                        })
                        .catch(function(err) {
                            window.__fetching = false;
                        });
                })()
                """.trimIndent(),
            )
            evaluateJs("window.__blocked ? 'BLOCKED' : (window.__fetchResult || null)") { value ->
                val result = value.parseAs<String?>()
                if (result == "BLOCKED") {
                    resolve("BLOCKED")
                } else if (!result.isNullOrBlank()) {
                    resolve(result)
                }
            }
        }
        loadUrl("$baseUrl/")
    }.also {
        if (it == "BLOCKED" || isAntiAbuseBlock(it)) {
            throw LycanAntiAbuseException()
        }
    }

    private suspend fun webFetchPost(url: String, jsonBody: String): String = runWebView(timeout = 30.seconds) {
        userAgent = headers["User-Agent"]!!
        val escapedJson = jsonBody.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
        poll(1.seconds) {
            evaluateJs(
                """
                (function() {
                    if (document.title.indexOf('Just a moment') !== -1) return;
                    if (document.title.indexOf('Bloqueado') !== -1 || (document.body && document.body.innerText.indexOf('Acesso bloqueado temporariamente') !== -1)) {
                        window.__blocked = true;
                        return;
                    }
                    if (window.__fetchingPost) return;
                    window.__fetchingPost = true;
                    fetch('$url', {
                        method: 'POST',
                        headers: {
                            'Content-Type': 'application/json',
                            'Accept': 'application/json, text/plain, */*'
                        },
                        body: '$escapedJson'
                    })
                        .then(function(res) { return res.text(); })
                        .then(function(text) {
                            window.__postResult = text;
                        })
                        .catch(function(err) {
                            window.__fetchingPost = false;
                        });
                })()
                """.trimIndent(),
            )
            evaluateJs("window.__blocked ? 'BLOCKED' : (window.__postResult || null)") { value ->
                val result = value.parseAs<String?>()
                if (result == "BLOCKED") {
                    resolve("BLOCKED")
                } else if (!result.isNullOrBlank()) {
                    resolve(result)
                }
            }
        }
        loadUrl("$baseUrl/")
    }.also {
        if (it == "BLOCKED" || isAntiAbuseBlock(it)) {
            throw LycanAntiAbuseException()
        }
    }

    private suspend fun webFetchHtml(url: String): String = runWebView(timeout = 30.seconds) {
        userAgent = headers["User-Agent"]!!
        poll(1.seconds) {
            evaluateJs(
                """
                (function() {
                    if (document.title.indexOf('Just a moment') !== -1) return null;
                    if (document.title.indexOf('Bloqueado') !== -1 || (document.body && document.body.innerText.indexOf('Acesso bloqueado temporariamente') !== -1)) {
                        return 'BLOCKED';
                    }
                    var script = document.querySelector('script[type="application/ld+json"]');
                    if (script || document.querySelector('h1')) {
                        return document.documentElement.outerHTML;
                    }
                    return null;
                })()
                """.trimIndent(),
            ) { value ->
                val html = value.parseAs<String?>()
                if (html == "BLOCKED") {
                    resolve("BLOCKED")
                } else if (!html.isNullOrBlank() && !html.contains("<title>Just a moment...")) {
                    resolve(html)
                }
            }
        }
        loadUrl(url)
    }.also {
        if (it == "BLOCKED" || isAntiAbuseBlock(it)) {
            throw LycanAntiAbuseException()
        }
    }

    override fun imageRequest(page: Page): Request {
        val imageUrl = page.imageUrl
            ?: throw Exception("URL da página não informada")
        return buildLycanImageRequest(imageUrl, baseUrl, headers)
    }

    private fun SManga.slug(): String = url.substringBefore("?").substringAfterLast("/")

    companion object {
        private const val PAGE_LIMIT = 20
        private const val SEARCH_CACHE_TTL = 3 * 60 * 1000L // 3 minutes
        private val SERIES_REGEX = """\\"series\\"\s*:\s*(\[\{.*?\}\])""".toRegex()
        private val RANKING_ALL_REGEX = """\\"lidas:all\\"\s*:\s*\{.*?\\"rows\\"\s*:\s*(\[\{.*?\}\])\s*\}""".toRegex()
        private val RANKING_WEEKLY_REGEX = """\\"lidas:weekly\\"\s*:\s*\{.*?\\"rows\\"\s*:\s*(\[\{.*?\}\])\s*\}""".toRegex()
        private val TOTAL_PAGES_REGEX = """\\"totalPages\\"\s*:\s*(\d+)""".toRegex()
        private val EXTRACT_CAPITULO_ID_REGEX = """(?:/api/chapters/|capituloId[^0-9]{1,20})(\d+)""".toRegex()
        private val CHAPTERS_REGEX = """\\?"chapters\\?"\s*:\s*(\[\{.*?\}\])""".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val CAPITULOS_REGEX = """\\?"capitulos\\?"\s*:\s*(\[\{.*?\}\])""".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val JSON_LD_REGEX = """<script\s+type="application/ld\+json"[^>]*>(.*?)</script>""".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val GENRES_REGEX = """\\"genre\\":\[(.*?)\]""".toRegex()
        private val STATUS_REGEX = """\\"status\\"\s*:\s*\\"([^"]+)\\"""".toRegex(RegexOption.IGNORE_CASE)
        private val OG_TITLE_REGEX = """property="og:title"\s*content="([^"]+)"""".toRegex()
        private val OG_IMAGE_REGEX = """property="og:image"\s*content="([^"]+)"""".toRegex()
        private val OG_DESC_REGEX = """property="og:description"\s*content="([^"]+)"""".toRegex()
        private val META_DESC_REGEX = """<meta\s+name="description"\s+content="([^"]+)"""".toRegex(RegexOption.IGNORE_CASE)
    }
}

private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
private val PRE_REGEX = """<pre[^>]*>(.*?)</pre>""".toRegex(RegexOption.DOT_MATCHES_ALL)
private val PAGES_ARRAY_REGEX = """\\?"pages\\?"\s*:\s*\[(.*?)\]""".toRegex(RegexOption.DOT_MATCHES_ALL)
private val IMAGE_URL_REGEX = """"(https?:[^"]+)"""".toRegex()

internal fun extractPageUrls(
    body: String,
    baseUrl: String = "https://lycantoons.com",
    json: Json = Json { ignoreUnknownKeys = true },
): List<String> {
    val trimmed = body.trim()
    var rawUrls: List<String> = emptyList()

    // 1. JSON puro
    if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
        try {
            val dto = json.decodeFromString<ChapterPagesDto>(trimmed)
            if (dto.pages.isNotEmpty()) rawUrls = dto.pages
        } catch (_: Exception) {}
    }

    // 2. Encapsulado em <pre> (retorno do FlareSolverr / Chromium para application/json)
    if (rawUrls.isEmpty()) {
        val preMatch = PRE_REGEX.find(body)
        if (preMatch != null) {
            val preContent = preMatch.groupValues[1].trim()
            try {
                val dto = json.decodeFromString<ChapterPagesDto>(preContent)
                if (dto.pages.isNotEmpty()) rawUrls = dto.pages
            } catch (_: Exception) {}
        }
    }

    // 3. Objeto JSON externo contendo "pages"
    if (rawUrls.isEmpty()) {
        val firstBrace = body.indexOf('{')
        val lastBrace = body.lastIndexOf('}')
        if (firstBrace != -1 && lastBrace > firstBrace) {
            val candidate = body.substring(firstBrace, lastBrace + 1)
            try {
                val dto = json.decodeFromString<ChapterPagesDto>(candidate)
                if (dto.pages.isNotEmpty()) rawUrls = dto.pages
            } catch (_: Exception) {}
        }
    }

    // 4. Regex fallback diretamente no array "pages": [...]
    if (rawUrls.isEmpty()) {
        val pagesMatch = PAGES_ARRAY_REGEX.find(body)
        if (pagesMatch != null) {
            val urls = IMAGE_URL_REGEX.findAll(pagesMatch.groupValues[1])
                .map { it.groupValues[1].replace("\\/", "/") }
                .toList()
            if (urls.isNotEmpty()) rawUrls = urls
        }
    }

    return rawUrls.mapNotNull { normalizeImageUrl(it, baseUrl) }
}

fun buildLycanImageRequest(
    imageUrl: String,
    baseUrl: String = "https://lycantoons.com",
    baseHeaders: Headers? = null,
): Request {
    val normalizedUrl = normalizeImageUrl(imageUrl, baseUrl)
        ?: throw Exception("URL da página inválida: $imageUrl")

    val headersBuilder = (baseHeaders?.newBuilder() ?: Headers.Builder())
        .removeAll("Origin")
        .set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
        .set("Referer", "$baseUrl/")
        .set("Sec-Fetch-Dest", "image")
        .set("Sec-Fetch-Mode", "no-cors")
        .set("Sec-Fetch-Site", "same-site")

    return Request.Builder().url(normalizedUrl).headers(headersBuilder.build()).build()
}

fun isImageResource(request: Request): Boolean {
    val path = request.url.encodedPath.lowercase()
    val accept = request.header("Accept")?.lowercase().orEmpty()
    return path.endsWith(".webp") ||
        path.endsWith(".jpg") ||
        path.endsWith(".jpeg") ||
        path.endsWith(".png") ||
        path.endsWith(".avif") ||
        path.endsWith(".gif") ||
        path.contains("/file/") ||
        accept.startsWith("image/")
}

class LycanAntiAbuseException(message: String = ANTI_ABUSE_ERROR_MESSAGE) : IOException(message)

const val ANTI_ABUSE_ERROR_MESSAGE = "Acesso temporariamente bloqueado pela proteção anti-abuso da Lycan Toons. Aguarde alguns minutos antes de tentar novamente no aplicativo."

fun isAntiAbuseBlock(body: String): Boolean = body.contains("Acesso bloqueado temporariamente", ignoreCase = true) ||
    body.contains("Bloqueado — LycanToons", ignoreCase = true) ||
    (body.contains("banido temporariamente", ignoreCase = true) && body.contains("navegação suspeita", ignoreCase = true)) ||
    body.contains("proteção anti-abuso", ignoreCase = true)

class LycanToonsInterceptor(private val baseUrl: String) : okhttp3.Interceptor {
    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        val host = request.url.host
        val isCdn = host.startsWith("cdn.") || host.contains("cdn")
        val isImageReq = isCdn || isImageResource(request)

        val builder = request.newBuilder()

        if (request.header("User-Agent").isNullOrBlank()) {
            builder.header("User-Agent", DEFAULT_USER_AGENT)
        }

        if (request.header("Referer").isNullOrBlank()) {
            builder.header("Referer", "$baseUrl/")
        }

        if (isImageReq) {
            // CRITICAL: cdn.lycantoons.com returns HTTP 403 if Origin header is present.
            // Standard browsers never send Origin for <img> requests.
            builder.removeHeader("Origin")

            // Cloudflare WAF on cdn.lycantoons.com requires Sec-Fetch metadata for image requests.
            builder.header("Sec-Fetch-Dest", "image")
            builder.header("Sec-Fetch-Mode", "no-cors")
            builder.header("Sec-Fetch-Site", "same-site")
            builder.header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
        } else if (host.contains("lycantoons.com")) {
            if (request.method.equals("POST", ignoreCase = true)) {
                // Same-origin POST to Next.js API endpoints expects Origin: https://lycantoons.com
                builder.header("Origin", baseUrl)
                if (request.header("Sec-Fetch-Site").isNullOrBlank()) {
                    builder.header("Sec-Fetch-Site", "same-origin")
                }
                if (request.header("Sec-Fetch-Mode").isNullOrBlank()) {
                    builder.header("Sec-Fetch-Mode", "cors")
                }
                if (request.header("Sec-Fetch-Dest").isNullOrBlank()) {
                    builder.header("Sec-Fetch-Dest", "empty")
                }
            } else {
                builder.removeHeader("Origin")
            }
        }

        val response = chain.proceed(builder.build())

        // Immediate fail-fast on Cloudflare WAF anti-abuse block page
        if (!isImageReq && (response.code == 403 || response.code == 429)) {
            val peekedBody = runCatching { response.peekBody(4096).string() }.getOrNull().orEmpty()
            if (isAntiAbuseBlock(peekedBody)) {
                throw LycanAntiAbuseException()
            }
        }

        if (!response.isSuccessful && response.code == 403 && isImageReq) {
            System.err.println("LycanToons CDN HTTP 403 Forbidden: ${request.url}")
        }

        return response
    }
}
