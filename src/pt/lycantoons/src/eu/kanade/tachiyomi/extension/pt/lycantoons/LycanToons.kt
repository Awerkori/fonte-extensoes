package eu.kanade.tachiyomi.extension.pt.lycantoons

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.applicationContext
import keiyoushi.utils.parseAs
import keiyoushi.utils.runWebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.parser.Parser
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds

@Source
abstract class LycanToons : KeiSource() {

    override fun OkHttpClient.Builder.configureClient() = apply {
        rateLimit(2)
        addInterceptor { chain ->
            val request = chain.request()
            val host = request.url.host
            if (host.contains("lycantoons.com")) {
                val isCdn = host.startsWith("cdn.")
                val builder = request.newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .header("Origin", baseUrl)
                    .header("Sec-Fetch-Site", if (isCdn) "same-site" else "same-origin")
                    .header("Sec-Fetch-Mode", if (isCdn) "no-cors" else "cors")
                    .header("Sec-Fetch-Dest", if (isCdn) "image" else "empty")

                if (request.header("Referer") == null) {
                    builder.header("Referer", "$baseUrl/")
                }
                if (isCdn) {
                    builder.header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                }
                chain.proceed(builder.build())
            } else {
                chain.proceed(request)
            }
        }
    }

    override fun Headers.Builder.configureHeaders(): Headers.Builder = this
        .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .set("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)
        .set("Sec-Fetch-Site", "same-site")
        .set("Sec-Fetch-Mode", "no-cors")
        .set("Sec-Fetch-Dest", "image")

    private val json = Json { ignoreUnknownKeys = true }

    private val catalogClient by lazy { client.newBuilder().build() }
    private val fetchDispatcher by lazy { Executors.newFixedThreadPool(4) }

    @Volatile
    private var cachedCatalog: List<SeriesDto>? = null
    private var lastCatalogFetch = 0L

    // =====================Popular=====================

    override suspend fun getPopularManga(page: Int): MangasPage = withContext(Dispatchers.IO) {
        if (page > 1) {
            return@withContext MangasPage(emptyList(), false)
        }

        val request = GET("$baseUrl/ranking/obras", headers)
        val response = client.newCall(request).execute()
        val html = response.body.string()

        val match = RANKING_ALL_REGEX.find(html) ?: RANKING_WEEKLY_REGEX.find(html)
        val jsonString = match?.groupValues?.get(1)?.replace("\\\"", "\"")?.replace("\\\\", "\\")
            ?: return@withContext MangasPage(emptyList(), false)

        val series = json.decodeFromString<List<RankingRowDto>>(jsonString)
        MangasPage(series.map { it.toSManga() }, false)
    }

    // =====================Latest=====================

    override suspend fun getLatestUpdates(page: Int): MangasPage = withContext(Dispatchers.IO) {
        val request = GET("$baseUrl/series?page=$page", headers)
        val response = client.newCall(request).execute()
        val html = response.body.string()

        val match = SERIES_REGEX.find(html)
        val jsonString = match?.groupValues?.get(1)?.replace("\\\"", "\"")?.replace("\\\\", "\\")
            ?: return@withContext MangasPage(emptyList(), false)

        val series = json.decodeFromString<List<SeriesDto>>(jsonString)
        val totalPages = TOTAL_PAGES_REGEX.find(html)?.groupValues?.get(1)?.toIntOrNull() ?: 1

        MangasPage(series.map { it.toSManga() }, page < totalPages)
    }

    // =====================Search=====================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = withContext(Dispatchers.IO) {
        var search = query.trim()
        val tags = filters.selectedTags().toMutableList()

        val genreEntry = tagMapping.entries.find { it.value.equals(query, ignoreCase = true) }
        if (genreEntry != null) {
            tags.add(genreEntry.key)
            search = ""
        }

        val selectedType = filters.valueOrEmpty<SeriesTypeFilter>()
        val selectedStatus = filters.valueOrEmpty<StatusFilter>()

        // Se query vazia e nenhum filtro selecionado, usa a paginação direta de /series?page={page}
        if (search.isEmpty() && tags.isEmpty() && selectedType.isEmpty() && selectedStatus.isEmpty()) {
            val request = GET("$baseUrl/series?page=$page", headers)
            val response = client.newCall(request).execute()
            val html = response.body.string()

            val match = SERIES_REGEX.find(html)
            val jsonString = match?.groupValues?.get(1)?.replace("\\\"", "\"")?.replace("\\\\", "\\")
                ?: return@withContext MangasPage(emptyList(), false)

            val series = json.decodeFromString<List<SeriesDto>>(jsonString)
            val totalPages = TOTAL_PAGES_REGEX.find(html)?.groupValues?.get(1)?.toIntOrNull() ?: 1

            return@withContext MangasPage(series.map { it.toSManga() }, page < totalPages)
        }

        // Pesquisa no catálogo completo com cache
        val allSeries = getCatalog()

        var filtered = allSeries.asSequence()

        if (search.isNotEmpty()) {
            val q = search.lowercase()
            filtered = filtered.filter { s ->
                s.title.lowercase().contains(q) ||
                    s.slug.lowercase().contains(q) ||
                    (cleanSynopsis(s.synopsis)?.lowercase()?.contains(q) == true) ||
                    (cleanSynopsis(s.description)?.lowercase()?.contains(q) == true)
            }
        }

        if (tags.isNotEmpty()) {
            filtered = filtered.filter { s ->
                val sGenres = s.genre?.map { it.lowercase() } ?: emptyList()
                tags.all { tag -> sGenres.contains(tag.lowercase()) }
            }
        }

        if (selectedType.isNotEmpty()) {
            filtered = filtered.filter { s ->
                s.seriesType.equals(selectedType, ignoreCase = true)
            }
        }

        if (selectedStatus.isNotEmpty()) {
            filtered = filtered.filter { s ->
                s.status.equals(selectedStatus, ignoreCase = true)
            }
        }

        val results = filtered.toList()
        val totalCount = results.size
        val startIndex = (page - 1) * PAGE_LIMIT

        if (startIndex >= totalCount) {
            return@withContext MangasPage(emptyList(), false)
        }

        val endIndex = minOf(startIndex + PAGE_LIMIT, totalCount)
        val pageSeries = results.subList(startIndex, endIndex)
        val hasNext = endIndex < totalCount

        MangasPage(pageSeries.map { it.toSManga() }, hasNext)
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
                    if (!body.contains("challenges.cloudflare.com") &&
                        !body.contains("<title>Just a moment...") &&
                        (body.contains("application/ld+json") || body.contains("lycantoons"))
                    ) {
                        html = body
                    }
                }
            } catch (_: Throwable) {
                // OkHttp challenged / CloudflareException
            }

            // Fallback via WebView on Android (bypasses Cloudflare Turnstile natively)
            if (html.isBlank()) {
                try {
                    html = webFetchHtml("$baseUrl/series/$slug")
                } catch (_: Throwable) {
                    // WebView not available (e.g. Suwayomi JVM) or timeout
                }
            }
        }

        val updatedDetails = if (fetchDetails && html.isNotBlank()) {
            SManga.create().apply {
                title = OG_TITLE_REGEX.find(html)?.groupValues?.get(1)?.let { Parser.unescapeEntities(it, false) } ?: manga.title
                thumbnail_url = OG_IMAGE_REGEX.find(html)?.groupValues?.get(1) ?: manga.thumbnail_url

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
            } catch (_: Throwable) {
                ""
            }

            if (html.isEmpty()) {
                try {
                    html = webFetchHtml(chapterPageUrl)
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
                pages = extractPageUrls(body)
            }
        } catch (_: Throwable) {
            // Se falhar via OkHttp (ex: 403 Cloudflare no Android sem FlareSolverr), tenta fallback
        }

        // 2. Fallback via WebView (funciona no Android / Mobile contornando Cloudflare com same-origin fetch)
        if (pages.isEmpty()) {
            try {
                val webBody = webFetch(apiUrl)
                pages = extractPageUrls(webBody)
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

    private fun extractPageUrls(body: String): List<String> {
        val trimmed = body.trim()

        // 1. JSON puro
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                val dto = json.decodeFromString<ChapterPagesDto>(trimmed)
                if (dto.pages.isNotEmpty()) return dto.pages
            } catch (_: Exception) {}
        }

        // 2. Encapsulado em <pre> (retorno do FlareSolverr / Chromium para application/json)
        val preMatch = PRE_REGEX.find(body)
        if (preMatch != null) {
            val preContent = preMatch.groupValues[1].trim()
            try {
                val dto = json.decodeFromString<ChapterPagesDto>(preContent)
                if (dto.pages.isNotEmpty()) return dto.pages
            } catch (_: Exception) {}
        }

        // 3. Objeto JSON externo contendo "pages"
        val firstBrace = body.indexOf('{')
        val lastBrace = body.lastIndexOf('}')
        if (firstBrace != -1 && lastBrace > firstBrace) {
            val candidate = body.substring(firstBrace, lastBrace + 1)
            try {
                val dto = json.decodeFromString<ChapterPagesDto>(candidate)
                if (dto.pages.isNotEmpty()) return dto.pages
            } catch (_: Exception) {}
        }

        // 4. Regex fallback diretamente no array "pages": [...]
        val pagesMatch = PAGES_ARRAY_REGEX.find(body)
        if (pagesMatch != null) {
            val urls = IMAGE_URL_REGEX.findAll(pagesMatch.groupValues[1])
                .map { it.groupValues[1].replace("\\/", "/") }
                .toList()
            if (urls.isNotEmpty()) return urls
        }

        return emptyList()
    }

    private suspend fun webFetch(url: String): String = runWebView(timeout = 30.seconds) {
        userAgent = headers["User-Agent"]!!
        poll(1.seconds) {
            evaluateJs(
                """
                (function() {
                    if (document.title.indexOf('Just a moment') !== -1) return;
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
            evaluateJs("window.__fetchResult || null") { value ->
                val result = value.parseAs<String?>()
                if (!result.isNullOrBlank()) {
                    resolve(result)
                }
            }
        }
        loadUrl("$baseUrl/")
    }

    private suspend fun webFetchHtml(url: String): String = runWebView(timeout = 30.seconds) {
        userAgent = headers["User-Agent"]!!
        poll(1.seconds) {
            evaluateJs(
                """
                (function() {
                    if (document.title.indexOf('Just a moment') !== -1) return null;
                    var script = document.querySelector('script[type="application/ld+json"]');
                    if (script || document.querySelector('h1')) {
                        return document.documentElement.outerHTML;
                    }
                    return null;
                })()
                """.trimIndent(),
            ) { value ->
                val html = value.parseAs<String?>()
                if (!html.isNullOrBlank() && !html.contains("<title>Just a moment...")) {
                    resolve(html)
                }
            }
        }
        loadUrl(url)
    }

    override fun imageRequest(page: Page): Request = GET(
        url = page.imageUrl!!,
        headers = headers.newBuilder()
            .set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .set("Referer", "$baseUrl/")
            .set("Origin", baseUrl)
            .set("Sec-Fetch-Dest", "image")
            .set("Sec-Fetch-Mode", "no-cors")
            .set("Sec-Fetch-Site", "same-site")
            .build(),
    )

    // =====================Catalog & Caching=====================

    @Synchronized
    private fun getCatalog(): List<SeriesDto> {
        val now = System.currentTimeMillis()
        if (cachedCatalog != null && (now - lastCatalogFetch) < CATALOG_CACHE_TTL) {
            return cachedCatalog!!
        }

        val cacheFile = File(applicationContext.cacheDir, "lycantoons_catalog.json")
        if (cacheFile.exists() && (now - cacheFile.lastModified()) < CATALOG_CACHE_TTL) {
            try {
                val jsonText = cacheFile.readText()
                val list = json.decodeFromString<List<SeriesDto>>(jsonText)
                if (list.isNotEmpty()) {
                    cachedCatalog = list
                    lastCatalogFetch = cacheFile.lastModified()
                    return list
                }
            } catch (_: Exception) {}
        }

        try {
            val p1Request = GET("$baseUrl/series?page=1", headers)
            val p1Response = catalogClient.newCall(p1Request).execute()
            val p1Html = p1Response.body.string()

            val totalPages = TOTAL_PAGES_REGEX.find(p1Html)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val p1Series = extractSeries(p1Html)

            val fullList = ArrayList<SeriesDto>(p1Series)

            if (totalPages > 1) {
                val completionService = ExecutorCompletionService<List<SeriesDto>>(fetchDispatcher)
                for (p in 2..totalPages) {
                    completionService.submit(
                        Callable {
                            try {
                                val req = GET("$baseUrl/series?page=$p", headers)
                                val resp = catalogClient.newCall(req).execute()
                                extractSeries(resp.body.string())
                            } catch (_: Exception) {
                                emptyList()
                            }
                        },
                    )
                }

                for (p in 2..totalPages) {
                    try {
                        val batch = completionService.take().get()
                        fullList.addAll(batch)
                    } catch (_: Exception) {}
                }
            }

            val distinctList = fullList.distinctBy { it.slug }
            if (distinctList.isNotEmpty()) {
                cachedCatalog = distinctList
                lastCatalogFetch = now
                try {
                    cacheFile.writeText(json.encodeToString(distinctList))
                } catch (_: Exception) {}
                return distinctList
            }
        } catch (_: Exception) {}

        return cachedCatalog ?: emptyList()
    }

    private fun extractSeries(html: String): List<SeriesDto> {
        val match = SERIES_REGEX.find(html) ?: return emptyList()
        val jsonString = match.groupValues[1]
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
        return try {
            json.decodeFromString<List<SeriesDto>>(jsonString)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun SManga.slug(): String = url.substringBefore("?").substringAfterLast("/")

    companion object {
        private const val PAGE_LIMIT = 20
        private const val CATALOG_CACHE_TTL = 4 * 3600 * 1000L // 4 hours

        private val SERIES_REGEX = """\\"series\\"\s*:\s*(\[\{.*?\}\])""".toRegex()
        private val RANKING_ALL_REGEX = """\\"lidas:all\\"\s*:\s*\{.*?\\"rows\\"\s*:\s*(\[\{.*?\}\])\s*\}""".toRegex()
        private val RANKING_WEEKLY_REGEX = """\\"lidas:weekly\\"\s*:\s*\{.*?\\"rows\\"\s*:\s*(\[\{.*?\}\])\s*\}""".toRegex()
        private val TOTAL_PAGES_REGEX = """\\"totalPages\\"\s*:\s*(\d+)""".toRegex()
        private val EXTRACT_CAPITULO_ID_REGEX = """(?:/api/chapters/|capituloId[^0-9]{1,20})(\d+)""".toRegex()
        private val PRE_REGEX = """<pre[^>]*>(.*?)</pre>""".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val PAGES_ARRAY_REGEX = """\\?"pages\\?"\s*:\s*\[(.*?)\]""".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val IMAGE_URL_REGEX = """"(https?:[^"]+)"""".toRegex()
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
