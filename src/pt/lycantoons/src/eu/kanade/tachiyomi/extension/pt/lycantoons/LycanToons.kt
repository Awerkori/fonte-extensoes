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
import keiyoushi.utils.runWebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors

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
                    (s.synopsis?.lowercase()?.contains(q) == true) ||
                    (s.description?.lowercase()?.contains(q) == true)
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
        val request = GET("$baseUrl/series/$slug", headers)
        val response = client.newCall(request).execute()
        val html = response.body.string()

        val updatedDetails = if (fetchDetails) {
            SManga.create().apply {
                title = OG_TITLE_REGEX.find(html)?.groupValues?.get(1) ?: manga.title
                thumbnail_url = OG_IMAGE_REGEX.find(html)?.groupValues?.get(1) ?: manga.thumbnail_url
                description = OG_DESC_REGEX.find(html)?.groupValues?.get(1) ?: manga.description

                val genresMatch = GENRES_REGEX.find(html)
                if (genresMatch != null) {
                    val g = genresMatch.groupValues[1]
                    genre = """"([^"]+)"""".toRegex().findAll(g).map { it.groupValues[1] }
                        .map { tagMapping[it] ?: it }
                        .joinToString()
                } else {
                    genre = manga.genre
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
            val chaptersMatch = CHAPTERS_REGEX.find(html) ?: CAPITULOS_REGEX.find(html)
            if (chaptersMatch != null) {
                val jsonString = chaptersMatch.groupValues[1]
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                json.decodeFromString<List<ChapterDto>>(jsonString)
                    .map { it.toSChapter(slug) }
                    .sortedByDescending { it.chapter_number }
            } else {
                chapters
            }
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
                    html = webFetch(chapterPageUrl)
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

    private suspend fun webFetch(url: String): String = runWebView {
        userAgent = headers["User-Agent"]!!
        jsBridge("lycanResult") { resolve(it) }
        jsBridge("lycanError") { reject(IOException(it)) }
        onPageFinished {
            evaluateJs(
                """
                fetch('$url')
                    .then(res => res.text())
                    .then(res => lycanResult.resolve(res))
                    .catch(err => lycanError.reject(err.message));
                """.trimIndent(),
            )
        }
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
        private val CAPITULO_ID_REGEX = """\\?"capituloId\\?"\s*:\s*(\d+)""".toRegex()
        private val EXTRACT_CAPITULO_ID_REGEX = """(?:/api/chapters/|capituloId[^0-9]{1,20})(\d+)""".toRegex()
        private val PRE_REGEX = """<pre[^>]*>(.*?)</pre>""".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val PAGES_ARRAY_REGEX = """\\?"pages\\?"\s*:\s*\[(.*?)\]""".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val IMAGE_URL_REGEX = """"(https?:[^"]+)"""".toRegex()
        private val CHAPTERS_REGEX = """\\"chapters\\"\s*:\s*(\[\{.*?\}\])""".toRegex()
        private val CAPITULOS_REGEX = """\\"capitulos\\"\s*:\s*(\[\{.*?\}\])""".toRegex()
        private val GENRES_REGEX = """\\"genre\\":\[(.*?)\]""".toRegex()
        private val STATUS_REGEX = """\\"status\\"\s*:\s*\\"([^"]+)\\"""".toRegex(RegexOption.IGNORE_CASE)
        private val OG_TITLE_REGEX = """property="og:title"\s*content="([^"]+)"""".toRegex()
        private val OG_IMAGE_REGEX = """property="og:image"\s*content="([^"]+)"""".toRegex()
        private val OG_DESC_REGEX = """property="og:description"\s*content="([^"]+)"""".toRegex()
    }
}
