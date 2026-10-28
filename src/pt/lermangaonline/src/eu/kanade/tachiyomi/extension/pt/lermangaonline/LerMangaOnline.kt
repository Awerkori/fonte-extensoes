package eu.kanade.tachiyomi.extension.pt.lermangaonline

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

@Source
abstract class LerMangaOnline : KeiSource() {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        rateLimit(permits = 5)
    }

    override fun Headers.Builder.configureHeaders(): Headers.Builder = apply {
        set("User-Agent", USER_AGENT)
        set("Referer", "$baseUrl/")
        set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
        set("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
    }

    override fun imageRequest(page: Page): Request {
        val imageUrl = page.imageUrl
        if (imageUrl.isNullOrBlank()) {
            return super.imageRequest(page)
        }
        return buildLerMangaOnlineImageRequest(imageUrl, baseUrl, headers)
    }

    // ============================== Popular ==============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        if (page == 1) {
            val mangas = mutableListOf<SManga>()

            // 1. Top ranking items from aside.mlo-ranking on homepage (most viewed manga)
            try {
                val homeDoc = client.get(baseUrl, headers = headers).asJsoup()
                homeDoc.select("aside.mlo-ranking ol li").forEach { li ->
                    val link = li.selectFirst("h3 a") ?: li.selectFirst("a.mlo-rank-cover") ?: return@forEach
                    val title = li.selectFirst("h3 a")?.text() ?: link.text()
                    val href = link.attr("abs:href").ifEmpty { return@forEach }
                    val img = li.selectFirst("img")
                    mangas.add(
                        SManga.create().apply {
                            this.url = cleanMangaUrl(href, baseUrl)
                            this.title = cleanMangaTitle(title)
                            this.thumbnail_url = extractCoverUrl(img, baseUrl)
                        },
                    )
                }

                // Featured mangas from homepage sections
                homeDoc.select("section.mlo-featured-section .mlo-card, .mlo-featured .mlo-card").forEach { card ->
                    val manga = parseMangaCard(card, baseUrl)
                    if (manga != null) mangas.add(manga)
                }
            } catch (_: Exception) {}

            // 2. Supplement with items from /manga/ to provide a full page of 24+ works
            try {
                val catalogDoc = client.get("$baseUrl/manga/", headers = headers).asJsoup()
                catalogDoc.select("article.mlo-card").mapNotNull { card ->
                    parseMangaCard(card, baseUrl)
                }.forEach { mangas.add(it) }
            } catch (_: Exception) {}

            val distinctMangas = mangas.distinctBy { it.url }
            if (distinctMangas.isNotEmpty()) {
                return MangasPage(distinctMangas, hasNextPage = true)
            }
        }

        // For page > 1: catalog from /manga/page/$page/
        val url = "$baseUrl/manga/page/$page/"
        val document = client.get(url, headers = headers).asJsoup()
        val mangas = document.select("article.mlo-card").mapNotNull { card ->
            parseMangaCard(card, baseUrl)
        }.distinctBy { it.url }

        val hasNextPage = document.selectFirst(".page-numbers a.next, .page-numbers a:contains(Próximo), a.next") != null
        return MangasPage(mangas, hasNextPage)
    }

    // ============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = if (page == 1) {
            "$baseUrl/manga/"
        } else {
            "$baseUrl/manga/page/$page/"
        }

        val document = client.get(url, headers = headers).asJsoup()
        val mangas = document.select("article.mlo-card").mapNotNull { card ->
            parseMangaCard(card, baseUrl)
        }.distinctBy { it.url }

        val hasNextPage = document.selectFirst(".page-numbers a.next, .page-numbers a:contains(Próximo), a.next") != null
        return MangasPage(mangas, hasNextPage)
    }

    // ============================== Search & Filters =====================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val trimmedQuery = query.trim()

        if (trimmedQuery.isNotEmpty()) {
            val encodedQuery = encodeQuery(trimmedQuery)
            val url = if (page == 1) {
                "$baseUrl/?s=$encodedQuery"
            } else {
                "$baseUrl/page/$page/?s=$encodedQuery"
            }

            val document = client.get(url, headers = headers).asJsoup()
            val mangas = document.select("article.mlo-card, article.mlo-release-card, .mlo-search-results article, article").mapNotNull { card ->
                parseMangaCard(card, baseUrl)
            }.distinctBy { it.url }

            val hasNextPage = document.selectFirst(".page-numbers a.next, a.next") != null
            return MangasPage(mangas, hasNextPage)
        }

        var selectedGenre: String? = null
        var selectedType: String? = null

        for (filter in filters) {
            when (filter) {
                is GenreFilter -> {
                    if (filter.selected.isNotEmpty() && filter.selected != "ALL") {
                        selectedGenre = filter.selected
                    }
                }
                is TypeFilter -> {
                    if (filter.selected.isNotEmpty() && filter.selected != "ALL") {
                        selectedType = filter.selected
                    }
                }
                else -> {}
            }
        }

        val targetUrl = when {
            selectedGenre != null -> {
                if (page == 1) "$baseUrl/genero/$selectedGenre/" else "$baseUrl/genero/$selectedGenre/page/$page/"
            }
            selectedType == "manhwa" -> {
                if (page == 1) "$baseUrl/manhwa/" else "$baseUrl/manhwa/page/$page/"
            }
            else -> {
                if (page == 1) "$baseUrl/manga/" else "$baseUrl/manga/page/$page/"
            }
        }

        val document = client.get(targetUrl, headers = headers).asJsoup()
        val mangas = document.select("article.mlo-card, article.mlo-release-card, article").mapNotNull { card ->
            parseMangaCard(card, baseUrl)
        }.distinctBy { it.url }

        val hasNextPage = document.selectFirst(".page-numbers a.next, a.next") != null
        return MangasPage(mangas, hasNextPage)
    }

    // ============================== Manga by URL =========================

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val path = url.encodedPath
        if (path.startsWith("/manga/")) {
            val slug = path.substringAfter("/manga/").trim('/')
            if (slug.isNotEmpty()) {
                return SManga.create().apply {
                    this.url = "/manga/$slug/"
                    this.title = slug
                }
            }
        }
        return null
    }

    // ============================== Manga Details & Chapters =============

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val fullUrl = "$baseUrl${if (manga.url.startsWith("/")) "" else "/"}${manga.url}"
        val document = client.get(fullUrl, headers = headers).asJsoup()

        val sManga = if (fetchDetails) {
            manga.apply {
                val h1 = document.selectFirst("h1.mlo-manga-title, .mlo-manga-head h1, h1")
                if (h1 != null) {
                    title = cleanMangaTitle(h1.text())
                }

                val coverImg = document.selectFirst("img.mlo-manga-cover, .mlo-manga-cover img, .mlo-cover img")
                val coverUrl = extractCoverUrl(coverImg, baseUrl)
                if (!coverUrl.isNullOrBlank()) {
                    thumbnail_url = coverUrl
                }

                // Description: skip editorial intro if story text exists
                val descParas = document.select(".mlo-prose p:not(.mlo-editorial-intro), .mlo-synopsis p")
                val descText = descParas.joinToString("\n\n") { it.text().trim() }.ifBlank {
                    document.selectFirst(".mlo-prose, .mlo-synopsis")?.text()?.trim()
                }
                description = descText?.ifEmpty { null }

                // Genres
                val genreElements = document.select(".mlo-genres a, a[href*='/genero/']")
                val genreList = genreElements.map { it.text().trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("+18") }
                    .distinct()
                genre = genreList.joinToString(", ").ifEmpty { null }

                // Metadata list
                document.select("dl div").forEach { div ->
                    val dt = div.selectFirst("dt")?.text()?.trim()?.lowercase(Locale.ROOT) ?: return@forEach
                    val dd = div.selectFirst("dd")?.text()?.trim() ?: return@forEach

                    when {
                        dt.contains("status") -> status = parseStatus(dd)
                        dt.contains("tipo") && (genre == null || !genre!!.contains(dd, ignoreCase = true)) -> {
                            val currentGenre = genre
                            genre = if (currentGenre.isNullOrEmpty()) dd else "$currentGenre, $dd"
                        }
                        dt.contains("autor") -> author = dd
                        dt.contains("artista") -> artist = dd
                    }
                }
            }
        } else {
            manga
        }

        val chapterList = if (fetchChapters) {
            fetchFullChapterList(document, fullUrl)
        } else {
            chapters
        }

        return SMangaUpdate(sManga, chapterList)
    }

    private suspend fun fetchFullChapterList(initialDoc: Document, mangaUrl: String): List<SChapter> {
        val chapters = mutableListOf<SChapter>()
        val seenUrls = mutableSetOf<String>()

        fun extractFromDoc(doc: Document) {
            doc.select("li[data-chapter-number]").forEach { li ->
                val link = li.selectFirst("a") ?: return@forEach
                val href = link.attr("abs:href").ifEmpty { return@forEach }
                val relativeUrl = cleanChapterUrl(href, baseUrl)
                if (!seenUrls.add(relativeUrl)) return@forEach

                val rawNumber = li.attr("data-chapter-number").toDoubleOrNull()
                val strong = li.selectFirst("strong")
                val rawTitle = strong?.text() ?: link.text()
                val timeEl = li.selectFirst("time")
                val dateStr = timeEl?.attr("datetime") ?: timeEl?.text()

                chapters.add(
                    SChapter.create().apply {
                        url = relativeUrl
                        name = cleanChapterName(rawNumber, rawTitle)
                        chapter_number = rawNumber?.toFloat() ?: -1f
                        date_upload = parseIsoDate(dateStr)
                    },
                )
            }
        }

        extractFromDoc(initialDoc)

        // Try fast AJAX batch loading if config is present
        val config = extractChapterConfig(initialDoc)
        val ajaxUrl = config?.ajaxUrl
        val mangaId = config?.mangaId
        val total = config?.total?.toIntOrNull() ?: 0
        val perPage = config?.perPage?.toIntOrNull() ?: 25

        if (!ajaxUrl.isNullOrEmpty() && !mangaId.isNullOrEmpty()) {
            if (total > perPage) {
                val totalPages = (total + perPage - 1) / perPage
                try {
                    val batches = coroutineScope {
                        (2..totalPages).map { page ->
                            async {
                                val url = "$ajaxUrl?action=mlo_chapter_batch_1138&manga_id=$mangaId&page=$page"
                                try {
                                    val resp = client.get(url, headers = headers).body.string()
                                    val batchDto = json.decodeFromString<LerMangaOnlineChapterBatchDto>(resp)
                                    page to (batchDto.data?.html ?: "")
                                } catch (_: Exception) {
                                    page to ""
                                }
                            }
                        }.awaitAll()
                    }

                    batches.sortedBy { it.first }.forEach { (_, html) ->
                        if (html.isNotBlank()) {
                            val fragmentDoc = Jsoup.parseBodyFragment(html, baseUrl)
                            extractFromDoc(fragmentDoc)
                        }
                    }

                    if (chapters.size >= total.coerceAtMost(chapters.size)) {
                        return chapters
                    }
                } catch (_: Exception) {
                    // Fall back to standard pagination if AJAX fails
                }
            } else {
                // All chapters fit on page 1
                return chapters
            }
        }

        // Fallback: standard pagination via ?cap_page=
        var hasNext = initialDoc.selectFirst(".mlo-chapter-pagination a.next, a.mlo-chapter-loadmore__button[data-mlo-chapter-next]") != null
        val baseCleanUrl = mangaUrl.substringBefore("?").trimEnd('/')
        var page = 2

        while (hasNext) {
            val pageUrl = "$baseCleanUrl/?cap_page=$page"
            try {
                val pageDoc = client.get(pageUrl, headers = headers).asJsoup()
                val initialSize = chapters.size
                extractFromDoc(pageDoc)

                if (chapters.size == initialSize) {
                    break
                }

                hasNext = pageDoc.selectFirst(".mlo-chapter-pagination a.next, a.mlo-chapter-loadmore__button[data-mlo-chapter-next]") != null
                page++
            } catch (_: Exception) {
                break
            }
        }

        return chapters
    }

    private fun extractChapterConfig(doc: Document): LerMangaOnlineChapterConfigDto? {
        val regex = Regex("""var\s+MLO_CHAPTER_LOAD_1138\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
        for (script in doc.select("script:not([src])")) {
            val data = script.data()
            if (data.contains("MLO_CHAPTER_LOAD_1138")) {
                val match = regex.find(data)
                if (match != null) {
                    return try {
                        json.decodeFromString<LerMangaOnlineChapterConfigDto>(match.groupValues[1])
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        }
        return null
    }

    // ============================== Pages ================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = chapter.url
        val fullUrl = if (chapterUrl.startsWith("http")) {
            chapterUrl
        } else {
            "$baseUrl${if (chapterUrl.startsWith("/")) "" else "/"}$chapterUrl"
        }

        val response = client.get(fullUrl, headers = headers)
        val html = response.body.string()
        val document = Jsoup.parse(html, baseUrl)

        // 1. Check for inline JSON script with {"pages": [...], "originals": [...]}
        for (script in document.select("script:not([src])")) {
            val text = script.data().trim()
            if (text.contains("\"pages\":")) {
                val firstBrace = text.indexOf('{')
                val lastBrace = text.lastIndexOf('}')
                if (firstBrace != -1 && lastBrace > firstBrace) {
                    val jsonStr = text.substring(firstBrace, lastBrace + 1)
                    try {
                        val readerDto = json.decodeFromString<LerMangaOnlineReaderDto>(jsonStr)
                        // Prioritize 'originals' as they directly load with HTTP 200 without 403 proxy blocks
                        val pageUrls = if (readerDto.originals.isNotEmpty()) {
                            readerDto.originals
                        } else {
                            readerDto.pages
                        }

                        val validPages = pageUrls.filter { it.isNotBlank() }
                        if (validPages.isNotEmpty()) {
                            return validPages.mapIndexed { index, url -> Page(index, imageUrl = url.trim()) }
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        // 2. Fallback: Parse from HTML figures
        val pages = mutableListOf<Page>()

        document.select(".mlo-reader-page, figure[data-page]").forEachIndexed { index, figure ->
            val img = figure.selectFirst("img") ?: return@forEachIndexed
            val rawFallback = img.attr("data-mlo-page-urls")
            var imageUrl: String? = null

            if (rawFallback.isNotBlank()) {
                val unescaped = rawFallback.replace("""\/""", "/")
                val match = Regex("""https?://[^\s"'\]]+""").find(unescaped)
                if (match != null) {
                    imageUrl = match.value
                }
            }

            if (imageUrl.isNullOrBlank()) {
                imageUrl = img.attr("abs:src").ifEmpty { img.attr("src") }
            }

            if (!imageUrl.isNullOrBlank()) {
                pages.add(Page(index, imageUrl = imageUrl.trim()))
            }
        }

        if (pages.isNotEmpty()) {
            return pages
        }

        throw Exception("Nenhuma página encontrada para este capítulo.")
    }

    // ============================== Filter Fetching ======================

    override val supportsFilterFetching: Boolean get() = true

    override suspend fun fetchFilterData(): JsonElement {
        val document = client.get("$baseUrl/generos/", headers = headers).asJsoup()
        val genreDtos = document.select("a[href*='/genero/']").mapNotNull { a ->
            val href = a.attr("abs:href")
            val slug = href.substringAfter("/genero/").trim('/')
            val text = a.text().trim()
            val name = text.replace(Regex("""\s*\d+\s*obras.*$""", RegexOption.IGNORE_CASE), "").trim()
            if (slug.isNotEmpty() && name.isNotEmpty() && !name.startsWith("+18")) {
                LerMangaOnlineGenreDto(name, slug)
            } else {
                null
            }
        }.distinctBy { it.slug }

        val jsonString = json.encodeToString(genreDtos)
        return json.parseToJsonElement(jsonString)
    }

    override fun getFilterList(data: JsonElement?): FilterList = LerMangaOnlineFilters.get(data)

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${if (manga.url.startsWith("/")) "" else "/"}${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl${if (chapter.url.startsWith("/")) "" else "/"}${chapter.url}"
}

internal fun buildLerMangaOnlineImageRequest(imageUrl: String, baseUrl: String, baseHeaders: Headers? = null): Request {
    val trimmed = imageUrl.trim().replace(" ", "%20")
    val fullUrl = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        trimmed
    } else {
        "$baseUrl${if (trimmed.startsWith("/")) "" else "/"}$trimmed"
    }

    val uri = fullUrl.toHttpUrlOrNull() ?: fullUrl.toHttpUrl()
    val builder = baseHeaders?.newBuilder() ?: Headers.Builder()

    builder
        .set("User-Agent", USER_AGENT)
        .set("Accept", "image/webp,image/png,image/jpeg,image/*;q=0.8")
        .set("Sec-Fetch-Dest", "image")
        .set("Sec-Fetch-Mode", "no-cors")
        .removeAll("Origin")

    val host = uri.host.lowercase()
    when {
        host.contains("lermangaonline") -> {
            builder.set("Referer", "$baseUrl/")
            builder.set("Sec-Fetch-Site", "same-site")
        }
        else -> {
            builder.removeAll("Referer")
            builder.set("Sec-Fetch-Site", "cross-site")
        }
    }

    return Request.Builder()
        .url(uri)
        .headers(builder.build())
        .get()
        .build()
}

internal fun cleanMangaTitle(rawTitle: String): String = rawTitle.trim()
    .replace(Regex("""^(?:Ler|Leia)\s+""", RegexOption.IGNORE_CASE), "")
    .replace(Regex("""\s+Online(?:\s+Gr[áa]tis)?$""", RegexOption.IGNORE_CASE), "")
    .trim()

internal fun cleanChapterName(number: Double?, rawTitle: String?): String {
    val numStr = if (number != null && number >= 0.0) {
        formatNumberString(number)
    } else {
        null
    }

    var cleanTitle = rawTitle?.replace(Regex("""\s+"""), " ")?.trim()?.ifEmpty { null }
    if (cleanTitle != null) {
        cleanTitle = cleanTitle
            .replace(Regex("""\b(?:NOVO|NOVA|HOT)\b""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^▣\s*"""), "")
            .trim()
    }

    if (cleanTitle != null) {
        val lower = cleanTitle.lowercase(Locale.ROOT)
        if (lower == "prólogo" || lower == "prologo" ||
            lower == "especial" || lower == "one-shot" ||
            lower == "oneshot" || lower == "extra"
        ) {
            return cleanTitle
        }
    }

    if (numStr != null && numStr.isNotEmpty()) {
        val redundantPrefixRegex = Regex(
            """^(?:Cap[ií]tulo|Cap\.?)\s*""" + Regex.escape(numStr) + """\s*[:\-\s]*""",
            RegexOption.IGNORE_CASE,
        )
        val extra = cleanTitle?.replace(redundantPrefixRegex, "")
            ?.trim()
            ?.trim('-', ':', ' ')

        val redundantSecond = Regex(
            """^(?:Cap[ií]tulo|Cap\.?)\s*""" + Regex.escape(numStr) + """\s*$""",
            RegexOption.IGNORE_CASE,
        )

        return if (!extra.isNullOrEmpty() &&
            !extra.equals(numStr, ignoreCase = true) &&
            !extra.matches(redundantSecond)
        ) {
            "Cap. $numStr - $extra"
        } else {
            "Cap. $numStr"
        }
    }

    return cleanTitle ?: "Capítulo"
}

internal fun formatNumberString(number: Double): String = if (number % 1.0 == 0.0) {
    number.toLong().toString()
} else {
    number.toString()
}

internal fun extractCoverUrl(img: Element?, baseUrl: String): String? {
    if (img == null) return null
    val rawFallback = img.attr("data-mlo-cover-urls")
    if (rawFallback.isNotBlank()) {
        val unescaped = rawFallback.replace("""\/""", "/")
        val match = Regex("""https?://[^\s"'\]]+""").find(unescaped)
        if (match != null) {
            return match.value
        }
    }
    val absSrc = img.attr("abs:src")
    if (absSrc.isNotBlank()) return absSrc
    val src = img.attr("src")
    if (src.isNotBlank()) {
        return if (src.startsWith("http")) src else "$baseUrl${if (src.startsWith("/")) "" else "/"}$src"
    }
    return null
}

internal fun parseStatus(status: String?): Int = when (status?.trim()?.lowercase(Locale.ROOT)) {
    "em andamento", "em lançamento" -> SManga.ONGOING
    "completo", "finalizado" -> SManga.COMPLETED
    "em hiato", "hiato", "pausado" -> SManga.ON_HIATUS
    "cancelado" -> SManga.CANCELLED
    else -> SManga.UNKNOWN
}

internal fun parseIsoDate(dateStr: String?): Long {
    if (dateStr.isNullOrBlank()) return 0L
    return try {
        val datePart = dateStr.substringBefore("+").substringBefore("Z").substringBefore(".")
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        sdf.parse(datePart)?.time ?: 0L
    } catch (_: Exception) {
        0L
    }
}

internal fun parseMangaCard(card: Element, baseUrl: String): SManga? {
    val link = card.selectFirst("h3.mlo-card-title a, h2 a, a.mlo-card-cover, a.mlo-release-cover, a[href*='/manga/']") ?: return null
    val href = link.attr("abs:href").ifEmpty { return null }
    if (!href.contains("/manga/")) return null

    val titleEl = card.selectFirst("h3.mlo-card-title a, h2 a") ?: link
    val rawTitle = titleEl.text().ifEmpty { link.attr("aria-label") }
    val img = card.selectFirst("img")

    return SManga.create().apply {
        this.url = cleanMangaUrl(href, baseUrl)
        this.title = cleanMangaTitle(rawTitle)
        this.thumbnail_url = extractCoverUrl(img, baseUrl)
    }
}

internal fun cleanMangaUrl(url: String, baseUrl: String): String {
    val withoutDomain = if (url.startsWith(baseUrl)) url.substring(baseUrl.length) else url
    return "/${withoutDomain.trim('/')}/"
}

internal fun cleanChapterUrl(url: String, baseUrl: String): String {
    val withoutDomain = if (url.startsWith(baseUrl)) url.substring(baseUrl.length) else url
    return "/${withoutDomain.trim('/')}/"
}

private fun encodeQuery(query: String): String = try {
    URLEncoder.encode(query, "UTF-8")
} catch (_: Exception) {
    query
}
