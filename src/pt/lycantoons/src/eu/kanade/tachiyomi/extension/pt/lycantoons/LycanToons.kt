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
import kotlinx.serialization.json.jsonPrimitive
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Source
abstract class LycanToons : KeiSource() {

    override fun OkHttpClient.Builder.configureClient() = apply {
        interceptors().removeAll { it.javaClass.simpleName == "CloudflareInterceptor" }
        // Rule 1: API & HTML pages: 1 request every 2 seconds
        rateLimit(permits = 1, period = 2.seconds) {
            !it.host.startsWith("cdn.") && !it.encodedPath.contains("/file/")
        }
        // Rule 2: CDN Images (Covers & Reader Pages): smooth 300ms pacing, max 2 permits per second
        rateLimit(permits = 2, period = 1.seconds, interval = 300.milliseconds) {
            it.host.startsWith("cdn.") || it.encodedPath.contains("/file/")
        }
        dns { hostname ->
            val addresses = Dns.SYSTEM.lookup(hostname)
            addresses.sortedBy { it !is Inet4Address }
        }
        addInterceptor(LycanToonsInterceptor(baseUrl))
    }

    override fun Headers.Builder.configureHeaders(): Headers.Builder = this
        .removeAll("Origin")
        .set("User-Agent", DEFAULT_USER_AGENT)
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .set("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
        .set("Referer", "$baseUrl/")

    private val isAndroidEnv: Boolean by lazy {
        try {
            Class.forName("android.webkit.WebView")
            android.os.Build.VERSION.SDK_INT > 0
        } catch (_: Throwable) {
            false
        }
    }

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

    private class MangaDetailsCacheEntry(val data: SMangaUpdate, val timestamp: Long)

    private val mangaDetailsCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, MangaDetailsCacheEntry>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MangaDetailsCacheEntry>?): Boolean = size > 32
        },
    )
    private val detailsCacheTtl = 5 * 60 * 1000L // 5 minutes

    private class ChapterPagesCacheEntry(val pages: List<Page>, val timestamp: Long)

    private val chapterPagesCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, ChapterPagesCacheEntry>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ChapterPagesCacheEntry>?): Boolean = size > 64
        },
    )
    private val pagesCacheTtl = 10 * 60 * 1000L // 10 minutes

    // =====================Popular=====================

    override suspend fun getPopularManga(page: Int): MangasPage = withContext(Dispatchers.IO) {
        if (page > 1) {
            return@withContext MangasPage(emptyList(), false)
        }

        var html = ""
        try {
            val request = GET("$baseUrl/ranking/obras", headers)
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body.string()
                if (isAntiAbuseBlock(body)) {
                    System.err.println("[LycanToons] getPopularManga: Bloqueio anti-abuso detectado no corpo da resposta 200.")
                    throw LycanAntiAbuseException()
                }
                html = body
            } else {
                System.err.println("[LycanToons] getPopularManga (/ranking/obras): HTTP ${response.code}")
            }
        } catch (e: LycanAntiAbuseException) {
            throw e
        } catch (e: Throwable) {
            System.err.println("[LycanToons] getPopularManga: Falha na requisição: ${e.javaClass.simpleName} - ${e.message}")
        }

        if (html.isBlank() || html.contains("challenges.cloudflare.com")) {
            if (isAndroidEnv) {
                try {
                    val webHtml = webFetchHtml("$baseUrl/ranking/obras")
                    if (isAntiAbuseBlock(webHtml)) {
                        System.err.println("[LycanToons] getPopularManga (WebView): Bloqueio anti-abuso detectado.")
                        throw LycanAntiAbuseException()
                    }
                    html = webHtml
                } catch (e: LycanAntiAbuseException) {
                    throw e
                } catch (e: Throwable) {
                    System.err.println("[LycanToons] getPopularManga (WebView): ${e.javaClass.simpleName} - ${e.message}")
                }
            }
        }

        val match = RANKING_ALL_REGEX.find(html) ?: RANKING_WEEKLY_REGEX.find(html)
        if (match != null) {
            val jsonString = match.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
            try {
                val series = json.decodeFromString<List<RankingRowDto>>(jsonString)
                if (series.isNotEmpty()) {
                    return@withContext MangasPage(series.map { it.toSManga() }, false)
                }
            } catch (e: Throwable) {
                System.err.println("[LycanToons] getPopularManga: Falha ao decodificar JSON do ranking: ${e.javaClass.simpleName} - ${e.message}")
            }
        } else if (html.isNotEmpty()) {
            System.err.println("[LycanToons] getPopularManga: Padrão do ranking não encontrado no HTML (${html.length} chars). Alternando para getLatestUpdates.")
        }

        // Fallback to Latest Updates if Ranking page cannot be parsed
        return@withContext getLatestUpdates(1)
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

        // 1. Direct OkHttp POST /api/series
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
                    System.err.println("[LycanToons] getSearchMangaList: Bloqueio anti-abuso detectado em /api/series.")
                    throw LycanAntiAbuseException()
                }
                responseBody = body
            } else {
                System.err.println("[LycanToons] getSearchMangaList (/api/series): HTTP ${resp.code}")
            }
        } catch (e: LycanAntiAbuseException) {
            throw e
        } catch (e: Throwable) {
            System.err.println("[LycanToons] getSearchMangaList (/api/series): Falha na requisição: ${e.javaClass.simpleName} - ${e.message}")
        }

        // 2. Fallback via WebView on Android
        if (responseBody.isNullOrBlank() && isAndroidEnv) {
            try {
                val webBody = webFetchPost("$baseUrl/api/series", jsonPayload)
                if (isAntiAbuseBlock(webBody)) {
                    System.err.println("[LycanToons] getSearchMangaList (WebView /api/series): Bloqueio anti-abuso detectado.")
                    throw LycanAntiAbuseException()
                }
                responseBody = webBody
            } catch (e: LycanAntiAbuseException) {
                throw e
            } catch (e: Throwable) {
                System.err.println("[LycanToons] getSearchMangaList (WebView /api/series): ${e.javaClass.simpleName} - ${e.message}")
            }
        }

        // 3. Fallback to Next.js page HTML parsing (GET /series?page=$page)
        if (responseBody.isNullOrBlank() && search.isEmpty() && genreList == null && selectedType == null && selectedStatus == null) {
            try {
                val req = GET("$baseUrl/series?page=$page", headers)
                val resp = client.newCall(req).execute()
                if (resp.isSuccessful) {
                    val html = resp.body.string()
                    if (isAntiAbuseBlock(html)) {
                        System.err.println("[LycanToons] getSearchMangaList (fallback /series): Bloqueio anti-abuso detectado.")
                        throw LycanAntiAbuseException()
                    }
                    val match = SERIES_REGEX.find(html)
                    if (match != null) {
                        val jsonString = match.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
                        try {
                            val series = json.decodeFromString<List<SeriesDto>>(jsonString)
                            val totalPages = TOTAL_PAGES_REGEX.find(html)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                            val result = MangasPage(series.map { it.toSManga() }, page < totalPages)
                            searchCache[cacheKey] = SearchCacheEntry(result, System.currentTimeMillis())
                            return@withContext result
                        } catch (e: Throwable) {
                            System.err.println("[LycanToons] getSearchMangaList (fallback /series): Falha ao decodificar JSON: ${e.javaClass.simpleName} - ${e.message}")
                        }
                    } else {
                        System.err.println("[LycanToons] getSearchMangaList (fallback /series): Padrão SERIES_REGEX não encontrado no HTML (${html.length} chars).")
                    }
                } else {
                    System.err.println("[LycanToons] getSearchMangaList (fallback /series): HTTP ${resp.code}")
                }
            } catch (e: LycanAntiAbuseException) {
                throw e
            } catch (e: Throwable) {
                System.err.println("[LycanToons] getSearchMangaList (fallback /series): Falha na requisição: ${e.javaClass.simpleName} - ${e.message}")
            }
        }

        if (!responseBody.isNullOrBlank()) {
            try {
                val searchResponse = json.decodeFromString<SeriesSearchResponse>(responseBody)
                val mangas = searchResponse.series.map { it.toSManga() }
                val hasNext = searchResponse.pagination?.hasNextPage ?: (mangas.size >= PAGE_LIMIT)
                val result = MangasPage(mangas, hasNext)
                searchCache[cacheKey] = SearchCacheEntry(result, System.currentTimeMillis())
                return@withContext result
            } catch (e: Throwable) {
                System.err.println("[LycanToons] getSearchMangaList: Falha ao decodificar SeriesSearchResponse: ${e.javaClass.simpleName} - ${e.message}")
            }
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

        val cached = mangaDetailsCache[slug]
        if (cached != null && (System.currentTimeMillis() - cached.timestamp) < detailsCacheTtl) {
            return@withContext cached.data
        }

        var fetchedChapters: List<SChapter> = emptyList()

        // 1. Direct internal API call for chapters
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
                    if (apiBody.startsWith("{") && !isAntiAbuseBlock(apiBody)) {
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

        // 2. Fetch HTML for details (or for chapters fallback)
        var html = ""
        val needsHtml = fetchDetails || (fetchChapters && fetchedChapters.isEmpty())

        if (needsHtml) {
            try {
                val req = GET("$baseUrl/series/$slug", headers)
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

            if (html.isBlank() && isAndroidEnv) {
                try {
                    val webHtml = webFetchHtml("$baseUrl/series/$slug")
                    if (isAntiAbuseBlock(webHtml)) {
                        throw LycanAntiAbuseException()
                    }
                    html = webHtml
                } catch (e: LycanAntiAbuseException) {
                    throw e
                } catch (_: Throwable) {
                    // WebView not available or timeout
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

        val result = SMangaUpdate(updatedDetails, updatedChapters)
        if (updatedChapters.isNotEmpty() || updatedDetails.initialized) {
            mangaDetailsCache[slug] = MangaDetailsCacheEntry(result, System.currentTimeMillis())
        }
        result
    }

    // =====================Pages========================

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        val cached = chapterPagesCache[chapter.url]
        if (cached != null && (System.currentTimeMillis() - cached.timestamp) < pagesCacheTtl) {
            return@withContext cached.pages
        }

        var capituloId = extractChapterId(chapter.url)
        var pages = emptyList<String>()

        // 1. If capituloId is empty, try to resolve via API chapters endpoint
        if (capituloId.isEmpty()) {
            val path = chapter.url.substringBefore("?").trim('/')
            val parts = path.split('/')
            if (parts.size >= 3 && parts[0] == "series") {
                val slug = parts[1]
                val chapterIdentifier = parts[2]
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
                        val body = apiResp.body.string().trim()
                        if (body.startsWith("{") && !isAntiAbuseBlock(body)) {
                            val apiData = json.decodeFromString<ChapterApiResponse>(body)
                            val targetNum = chapterIdentifier
                                .removePrefix("capitulo-")
                                .removePrefix("capitulo_")
                                .toFloatOrNull()
                            val found = apiData.chapters.find { c ->
                                val numStr = runCatching { c.numero.jsonPrimitive.content }.getOrNull()
                                if (numStr == chapterIdentifier) return@find true
                                val numFloat = numStr?.toFloatOrNull()
                                targetNum != null && numFloat != null && targetNum == numFloat
                            }
                            if (found?.id != null) {
                                capituloId = found.id.toString()
                            }
                        }
                    }
                } catch (e: LycanAntiAbuseException) {
                    throw e
                } catch (_: Throwable) {}
            }
        }

        // 2. If capituloId is still empty, fetch the chapter HTML page
        if (capituloId.isEmpty()) {
            val chapterPageUrl = "$baseUrl${chapter.url}"
            var html = ""
            try {
                val req = GET(chapterPageUrl, headers)
                val resp = client.newCall(req).execute()
                if (resp.isSuccessful) {
                    val body = resp.body.string()
                    if (isAntiAbuseBlock(body)) {
                        throw LycanAntiAbuseException()
                    }
                    html = body
                }
            } catch (e: LycanAntiAbuseException) {
                throw e
            } catch (_: Throwable) {}

            if (html.isEmpty() && isAndroidEnv) {
                try {
                    val webHtml = webFetchHtml(chapterPageUrl)
                    if (isAntiAbuseBlock(webHtml)) {
                        throw LycanAntiAbuseException()
                    }
                    html = webHtml
                } catch (e: LycanAntiAbuseException) {
                    throw e
                } catch (_: Throwable) {}
            }

            if (html.isNotEmpty()) {
                val htmlPages = extractPageUrls(html, baseUrl, json)
                if (htmlPages.isNotEmpty()) {
                    pages = htmlPages
                }
                if (capituloId.isEmpty()) {
                    capituloId = EXTRACT_CAPITULO_ID_REGEX.find(html)?.groupValues?.get(1) ?: ""
                }
            }
        }

        // 3. If pages not yet extracted and capituloId is available, fetch view-pages API
        if (pages.isEmpty() && capituloId.isNotEmpty()) {
            val apiUrl = "$baseUrl/api/chapters/$capituloId/view-pages"
            try {
                val req = GET(
                    url = apiUrl,
                    headers = headers.newBuilder()
                        .set("Accept", "application/json, text/plain, */*")
                        .set("Referer", "$baseUrl${chapter.url}")
                        .set("Sec-Fetch-Site", "same-origin")
                        .set("Sec-Fetch-Mode", "cors")
                        .set("Sec-Fetch-Dest", "empty")
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
                // OkHttp challenged
            }

            // 4. Fallback via WebView on Android
            if (pages.isEmpty() && isAndroidEnv) {
                try {
                    val webBody = webFetch(apiUrl)
                    if (isAntiAbuseBlock(webBody)) {
                        throw LycanAntiAbuseException()
                    }
                    pages = extractPageUrls(webBody, baseUrl, json)
                } catch (e: LycanAntiAbuseException) {
                    throw e
                } catch (_: Throwable) {
                    // WebView not available
                }
            }
        }

        if (pages.isNotEmpty()) {
            val result = pages.mapIndexed { index, imageUrl ->
                Page(index, imageUrl = imageUrl)
            }
            chapterPagesCache[chapter.url] = ChapterPagesCacheEntry(result, System.currentTimeMillis())
            return@withContext result
        }

        throw Exception("Nenhuma página encontrada!")
    }

    private suspend fun webFetch(url: String): String {
        if (!isAndroidEnv) return ""
        return runWebView(timeout = 30.seconds) {
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
    }

    private suspend fun webFetchPost(url: String, jsonBody: String): String {
        if (!isAndroidEnv) return ""
        return runWebView(timeout = 30.seconds) {
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
    }

    private suspend fun webFetchHtml(url: String): String {
        if (!isAndroidEnv) return ""
        return runWebView(timeout = 30.seconds) {
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
        internal val CAPITULO_ID_PARAM_REGEX = """[?&]capituloId=(\d+)""".toRegex()
        internal val ID_PARAM_REGEX = """[?&]id=(\d+)""".toRegex()
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

internal fun extractChapterId(chapterUrl: String): String {
    val capituloIdMatch = LycanToons.CAPITULO_ID_PARAM_REGEX.find(chapterUrl)?.groupValues?.get(1)
    if (!capituloIdMatch.isNullOrEmpty()) return capituloIdMatch

    val idMatch = LycanToons.ID_PARAM_REGEX.find(chapterUrl)?.groupValues?.get(1)
    if (!idMatch.isNullOrEmpty()) return idMatch

    return ""
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
        .set("Accept", "image/avif,image/webp,image/png,image/svg+xml,image/*;q=0.8,*/*;q=0.5")
        .set("Accept-Language", "pt-BR,pt;q=0.8,en-US;q=0.5,en;q=0.3")
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

object LycanCircuitBreaker {
    @Volatile
    private var blockedUntil: Long = 0L

    val isBlocked: Boolean
        get() = System.currentTimeMillis() < blockedUntil

    val remainingSeconds: Long
        get() = maxOf(0L, (blockedUntil - System.currentTimeMillis()) / 1000L)

    fun trip(durationMinutes: Long = 10L) {
        blockedUntil = System.currentTimeMillis() + (durationMinutes * 60 * 1000L)
    }

    fun reset() {
        blockedUntil = 0L
    }
}

class LycanToonsInterceptor(private val baseUrl: String) : okhttp3.Interceptor {
    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        val request = chain.request()

        if (LycanCircuitBreaker.isBlocked) {
            val sec = LycanCircuitBreaker.remainingSeconds
            System.err.println("[LycanToons] Circuit breaker ATIVO (${sec}s restantes): requisição para ${request.url.encodedPath} bloqueada localmente para preservar o IP.")
            throw LycanAntiAbuseException("Acesso temporariamente bloqueado pela proteção anti-abuso da Lycan Toons. Circuit breaker ativo localmente por mais ${sec}s.")
        }

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
            builder.header("Accept", "image/avif,image/webp,image/png,image/svg+xml,image/*;q=0.8,*/*;q=0.5")
            if (request.header("Accept-Language").isNullOrBlank()) {
                builder.header("Accept-Language", "pt-BR,pt;q=0.8,en-US;q=0.5,en;q=0.3")
            }
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
                if (request.url.encodedPath.startsWith("/api/")) {
                    if (request.header("Sec-Fetch-Site").isNullOrBlank()) {
                        builder.header("Sec-Fetch-Site", "same-origin")
                    }
                    if (request.header("Sec-Fetch-Mode").isNullOrBlank()) {
                        builder.header("Sec-Fetch-Mode", "cors")
                    }
                    if (request.header("Sec-Fetch-Dest").isNullOrBlank()) {
                        builder.header("Sec-Fetch-Dest", "empty")
                    }
                }
            }
        }

        val response = chain.proceed(builder.build())

        // Immediate fail-fast on Cloudflare WAF anti-abuse block page
        if (!isImageReq && (response.code == 403 || response.code == 429)) {
            val peekedBody = runCatching { response.peekBody(4096).string() }.getOrNull().orEmpty()
            if (isAntiAbuseBlock(peekedBody)) {
                LycanCircuitBreaker.trip(10)
                System.err.println("[LycanToons] HTTP ${response.code} em ${request.url.encodedPath}: Bloqueio anti-abuso detectado pelo site. Circuit breaker ativado por 10 minutos.")
                throw LycanAntiAbuseException()
            } else {
                val titleMatch = Regex("<title>([^<]+)</title>", RegexOption.IGNORE_CASE).find(peekedBody)
                val pageTitle = titleMatch?.groupValues?.getOrNull(1)?.trim() ?: "sem título"
                System.err.println("[LycanToons] HTTP ${response.code} em ${request.url.encodedPath}: Resposta de proteção Cloudflare (title: '$pageTitle').")
            }
        }

        if (!response.isSuccessful && response.code == 403 && isImageReq) {
            System.err.println("[LycanToons] CDN HTTP 403 Forbidden em ${request.url.encodedPath}")
            val peeked = runCatching { response.peekBody(1024).string() }.getOrNull().orEmpty()
            if (isAntiAbuseBlock(peeked)) {
                LycanCircuitBreaker.trip(10)
                System.err.println("[LycanToons] CDN HTTP 403 contém bloqueio anti-abuso. Ativando circuit breaker por 10 minutos.")
            }
        }

        return response
    }
}
