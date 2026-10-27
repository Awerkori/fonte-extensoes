package eu.kanade.tachiyomi.extension.pt.gomangas

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
import java.net.URLDecoder

private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
private const val PAGE_LIMIT = 28

@Source
abstract class GoMangas : KeiSource() {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        rateLimit(permits = 3)
    }

    override fun Headers.Builder.configureHeaders(): Headers.Builder = apply {
        set("User-Agent", USER_AGENT)
        set("Referer", "$baseUrl/")
    }

    override fun imageRequest(page: Page): Request {
        val imageUrl = page.imageUrl
        if (imageUrl.isNullOrBlank()) {
            return super.imageRequest(page)
        }
        return buildGoMangasImageRequest(imageUrl, baseUrl, headers)
    }

    // ============================== Popular ==============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$baseUrl/mangas".toHttpUrl().newBuilder()
            .addQueryParameter("order", "popular")
            .addQueryParameter("page", page.toString())
            .build()

        val response = client.get(url, headers = headers)
        val doc = Jsoup.parse(response.body.string(), baseUrl)
        return parseMangasPage(doc, page)
    }

    // ============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$baseUrl/mangas".toHttpUrl().newBuilder()
            .addQueryParameter("order", "recentes")
            .addQueryParameter("page", page.toString())
            .build()

        val response = client.get(url, headers = headers)
        val doc = Jsoup.parse(response.body.string(), baseUrl)
        return parseMangasPage(doc, page)
    }

    // ============================== Search & Filters =====================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val trimmedQuery = query.trim()

        val url = if (trimmedQuery.isNotEmpty()) {
            "$baseUrl/busca".toHttpUrl().newBuilder().apply {
                addQueryParameter("q", trimmedQuery)
                addQueryParameter("page", page.toString())
                applyFilters(this, filters)
            }.build()
        } else {
            "$baseUrl/mangas".toHttpUrl().newBuilder().apply {
                addQueryParameter("page", page.toString())
                applyFilters(this, filters)
            }.build()
        }

        val response = client.get(url, headers = headers)
        val doc = Jsoup.parse(response.body.string(), baseUrl)
        return parseMangasPage(doc, page)
    }

    private fun applyFilters(builder: HttpUrl.Builder, filters: FilterList) {
        for (filter in filters) {
            when (filter) {
                is SortFilter -> {
                    if (filter.selected.isNotEmpty()) {
                        builder.setQueryParameter("order", filter.selected)
                    }
                }
                is TypeFilter -> {
                    if (filter.selected.isNotEmpty()) {
                        builder.setQueryParameter("type", filter.selected)
                    }
                }
                is StatusFilter -> {
                    if (filter.selected.isNotEmpty()) {
                        builder.setQueryParameter("status", filter.selected)
                    }
                }
                is GenreFilter -> {
                    if (filter.selected.isNotEmpty()) {
                        builder.setQueryParameter("genre", filter.selected)
                    }
                }
                else -> {}
            }
        }
    }

    private fun parseMangasPage(doc: Document, page: Int): MangasPage {
        val cards = doc.select("a[href^='/manga/']")
        val mangas = mutableListOf<SManga>()
        val seenUrls = mutableSetOf<String>()

        for (card in cards) {
            val href = card.attr("href").trim()
            if (href.isNotEmpty() && seenUrls.add(href)) {
                val manga = parseCardElement(card, baseUrl)
                mangas.add(manga)
            }
        }

        val hasNextPage = mangas.size >= PAGE_LIMIT &&
            (doc.selectFirst("a[href*='page=${page + 1}']") != null || page < 25)

        return MangasPage(mangas, hasNextPage)
    }

    // ============================== Manga by URL =========================

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val path = url.encodedPath
        if (path.startsWith("/manga/")) {
            val slug = path.substringAfter("/manga/").substringBefore("/").trim()
            if (slug.isNotEmpty()) {
                return SManga.create().apply {
                    this.url = "/manga/$slug"
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
        val fullUrl = if (manga.url.startsWith("http")) manga.url else "$baseUrl${manga.url}"
        val response = client.get(fullUrl, headers = headers)
        val html = response.body.string()
        val doc = Jsoup.parse(html, fullUrl)

        val sManga = if (fetchDetails) parseMangaDetails(doc, manga, html, baseUrl) else manga
        val chapterList = if (fetchChapters) parseChapterList(html, doc) else chapters

        return SMangaUpdate(sManga, chapterList)
    }

    private fun parseMangaDetails(doc: Document, manga: SManga, html: String, baseUrl: String): SManga = SManga.create().apply {
        this.url = manga.url
        this.title = doc.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: manga.title

        val descMeta = doc.selectFirst("meta[name='description']")?.attr("content")?.trim()
            ?: doc.selectFirst("meta[property='og:description']")?.attr("content")?.trim()
        this.description = descMeta?.ifEmpty { null }

        // Resolve cover: preserve valid catalog cover or extract real illustrated cover; NEVER use opengraph-image
        this.thumbnail_url = extractMangaCover(doc, manga, html)

        var parsedAuthor: String? = null
        val parsedGenres = mutableListOf<String>()

        val schemaMatch = SCHEMA_BOOK_REGEX.find(html)
        if (schemaMatch != null) {
            try {
                val rawJson = schemaMatch.groupValues[1]
                    .replace("&quot;", "\"")
                    .replace("\\\"", "\"")
                val schemaDto = json.decodeFromString<BookSchemaDto>(rawJson)
                parsedAuthor = schemaDto.author?.name?.takeIf { it.isNotBlank() }
                parsedGenres.addAll(schemaDto.genre.filter { it.isNotBlank() })
            } catch (_: Exception) {}
        }

        if (parsedAuthor == null) {
            val authorText = doc.select("p:contains(por )").text()
            if (authorText.contains("por ")) {
                parsedAuthor = authorText.substringAfter("por ").trim().ifEmpty { null }
            }
        }

        this.author = parsedAuthor
        this.artist = parsedAuthor

        if (parsedGenres.isEmpty()) {
            val genreBadges = doc.select("a[href*='genre=']")
            for (g in genreBadges) {
                val gName = g.text().trim()
                if (gName.isNotEmpty()) parsedGenres.add(gName)
            }
        }

        this.genre = parsedGenres.distinct().joinToString(", ").ifEmpty { null }

        this.status = when {
            html.contains("Em andamento") -> SManga.ONGOING
            html.contains("Completo") -> SManga.COMPLETED
            html.contains("Hiato") -> SManga.ON_HIATUS
            html.contains("Cancelado") -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    private fun parseChapterList(html: String, doc: Document): List<SChapter> {
        val jsonMatch = CHAPTERS_JSON_REGEX.find(html)
        if (jsonMatch != null) {
            try {
                val unescaped = jsonMatch.groupValues[1]
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                val dtoList = json.decodeFromString<List<GoMangasChapterDto>>(unescaped)
                val chapters = dtoList
                    .map { it.toSChapter() }
                    .distinctBy { it.url }

                if (chapters.isNotEmpty()) {
                    return chapters
                }
            } catch (_: Exception) {}
        }

        // Fallback: Parse from HTML anchor elements
        val anchors = doc.select("a[href^='/ler/']")
        val chapters = mutableListOf<SChapter>()
        val seenUrls = mutableSetOf<String>()

        for (a in anchors) {
            val href = a.attr("href").trim()
            if (href.isNotEmpty() && seenUrls.add(href)) {
                val fullText = a.text().trim()
                if (fullText.contains("Iniciar leitura") || fullText.contains("Último cap.")) {
                    continue
                }
                val tnumText = a.selectFirst("span.tnum")?.text()?.trim()
                val titleText = a.selectFirst("span.truncate")?.text()?.trim()
                val dateText = a.selectFirst("span.text-paper-500, span.shrink-0:not(.tnum)")?.text()?.trim()
                val slug = href.substringAfter("/ler/").trim()
                val chapterNum = parseChapterNumberFromText(slug, tnumText ?: fullText)
                val numStr = if (tnumText != null) {
                    tnumText.replace(Regex("""^Cap\.?\s*""", RegexOption.IGNORE_CASE), "").trim()
                } else if (chapterNum >= 0f) {
                    formatNumberString(chapterNum)
                } else {
                    ""
                }
                val chapterName = formatChapterName(
                    numberLabel = numStr,
                    number = if (chapterNum >= 0f) chapterNum.toDouble() else null,
                    rawTitle = titleText,
                )
                val uploadDate = parsePortugueseDate(dateText)
                chapters.add(
                    SChapter.create().apply {
                        this.url = href
                        this.name = chapterName
                        this.chapter_number = chapterNum
                        this.date_upload = uploadDate
                    },
                )
            }
        }

        return chapters
    }

    // ============================== Pages ================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = chapter.url
        val fullUrl = if (chapterUrl.startsWith("http")) chapterUrl else "$baseUrl$chapterUrl"

        val response = client.get(fullUrl, headers = headers)
        val html = response.body.string()

        // 1. Primary: Extract from Next.js RSC JSON array
        val jsonMatch = PAGES_JSON_REGEX.find(html)
        if (jsonMatch != null) {
            try {
                val unescaped = jsonMatch.groupValues[1]
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                val pagesDto = json.decodeFromString<List<GoMangasPageDto>>(unescaped)
                val pages = pagesDto
                    .filter { it.imageUrl.isNotBlank() && !it.imageUrl.contains("/covers/") }
                    .mapIndexed { index, p -> Page(index, imageUrl = p.imageUrl.trim()) }

                if (pages.isNotEmpty()) {
                    return pages
                }
            } catch (_: Exception) {}
        }

        // 2. Fallback: Parse URLs via Regex from payload
        val foundUrls = PAGE_URL_REGEX.findAll(html)
            .map { it.value.replace("\\\"", "").replace("\\", "").trim() }
            .filter { it.isNotBlank() && !it.contains("/covers/") }
            .distinct()
            .toList()

        if (foundUrls.isNotEmpty()) {
            return foundUrls.mapIndexed { index, url -> Page(index, imageUrl = url) }
        }

        throw Exception("Nenhuma página encontrada para este capítulo.")
    }

    // ============================== Filters ==============================

    override fun getFilterList(data: JsonElement?): FilterList = GoMangasFilters.get()

    companion object {
        private val CHAPTERS_JSON_REGEX = Regex("""\\?"chapters\\?":\s*(\[\s*\{\\?"id\\?":.+?\}\])""")
        private val PAGES_JSON_REGEX = Regex("""\\?"pages\\?":\s*(\[\s*\{\\?"imageUrl\\?":.+?\}\])""")
        private val PAGE_URL_REGEX = Regex("""https://imagens\.gomangas\.com/manga/[^"'\s\\]+\.(?:webp|jpg|png|jpeg)""")
        private val SCHEMA_BOOK_REGEX = Regex("""(\{"@context":"https?://schema\.org","@type":"Book"[^<>]+\})""")
    }
}

internal fun parseCardElement(element: Element, baseUrl: String): SManga = SManga.create().apply {
    this.url = element.attr("href").trim()
    this.title = element.selectFirst("h3")?.text()?.trim()
        ?: element.selectFirst("img")?.attr("alt")?.removePrefix("Capa de ")?.trim()
        ?: ""
    this.thumbnail_url = extractCoverUrl(element)
}

internal fun extractCoverUrl(element: Element): String {
    val img = if (element.tagName() == "img") element else element.selectFirst("img") ?: return ""
    val rawSrc = img.attr("abs:src")
        .ifEmpty { img.attr("src") }
        .ifEmpty { img.attr("srcset") }
        .trim()
    return cleanCoverUrl(rawSrc)
}

internal fun cleanCoverUrl(rawUrl: String): String {
    if (rawUrl.isBlank()) return ""
    var cleaned = rawUrl.replace("&amp;", "&").replace("\\", "").trim()
    if (cleaned.contains("url=")) {
        val encoded = cleaned.substringAfter("url=").substringBefore("&")
        try {
            cleaned = URLDecoder.decode(encoded, "UTF-8")
        } catch (_: Exception) {}
    } else if (cleaned.startsWith("http%3A", ignoreCase = true) || cleaned.startsWith("https%3A", ignoreCase = true)) {
        try {
            cleaned = URLDecoder.decode(cleaned, "UTF-8")
        } catch (_: Exception) {}
    }
    return cleaned
}

internal fun isValidCoverUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val trimmed = url.trim()
    if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return false
    val lower = trimmed.lowercase()
    if (lower.contains("opengraph-image") ||
        lower.contains("/opengraph") ||
        lower.contains("banner") ||
        lower.contains("/cards/") ||
        lower.contains("manifest")
    ) {
        return false
    }
    return lower.contains("/covers/") ||
        lower.endsWith(".jpg") ||
        lower.endsWith(".jpeg") ||
        lower.endsWith(".webp") ||
        lower.endsWith(".png") ||
        lower.endsWith(".avif")
}

internal fun extractMangaCover(doc: Document, manga: SManga, html: String): String? = extractMangaCover(doc, manga.url, manga.thumbnail_url, html)

internal fun extractMangaCover(doc: Document, mangaUrl: String, existingThumbnail: String?, html: String): String? {
    if (isValidCoverUrl(existingThumbnail)) {
        return existingThumbnail
    }

    val mainImg = doc.selectFirst("img[alt^='Capa de '], img[alt*='Capa de']")
    if (mainImg != null) {
        val cover = extractCoverUrl(mainImg)
        if (isValidCoverUrl(cover)) {
            return cover
        }
    }

    val anyCoverImg = doc.selectFirst("img[src*='covers'], img[srcset*='covers']")
    if (anyCoverImg != null) {
        val cover = extractCoverUrl(anyCoverImg)
        if (isValidCoverUrl(cover)) {
            return cover
        }
    }

    val slug = mangaUrl.substringAfter("/manga/").substringBefore("/").trim()
    if (slug.isNotEmpty()) {
        val rawMatch = Regex(
            """https://imagens\.gomangas\.com/covers/""" + Regex.escape(slug) + """\.(?:jpg|webp|png|jpeg)""",
            RegexOption.IGNORE_CASE,
        ).find(html)
        if (rawMatch != null && isValidCoverUrl(rawMatch.value)) {
            return rawMatch.value
        }

        val encMatch = Regex(
            """https%3A%2F%2Fimagens\.gomangas\.com%2Fcovers%2F""" + Regex.escape(slug) + """\.(?:jpg|webp|png|jpeg)""",
            RegexOption.IGNORE_CASE,
        ).find(html)
        if (encMatch != null) {
            val decoded = cleanCoverUrl(encMatch.value)
            if (isValidCoverUrl(decoded)) {
                return decoded
            }
        }

        return "https://imagens.gomangas.com/covers/$slug.jpg"
    }

    return null
}

internal fun parseChapterNumberFromText(slug: String, text: String): Float {
    val match = Regex("""(?:capitulo|cap\.?)\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(text)
        ?: Regex("""capitulo-(\d+(?:-\d+)?)""", RegexOption.IGNORE_CASE).find(slug)
    if (match != null) {
        val raw = match.groupValues[1].replace("-", ".")
        return raw.toFloatOrNull() ?: -1f
    }
    return -1f
}

internal fun buildGoMangasImageRequest(imageUrl: String, baseUrl: String, baseHeaders: Headers? = null): Request {
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
        .set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
        .set("Sec-Fetch-Dest", "image")
        .set("Sec-Fetch-Mode", "no-cors")
        .removeAll("Origin")

    if (uri.host.contains("gomangas.com")) {
        builder.set("Referer", "$baseUrl/")
        builder.set("Sec-Fetch-Site", "same-site")
    } else {
        builder.removeAll("Referer")
        builder.set("Sec-Fetch-Site", "cross-site")
    }

    return Request.Builder()
        .url(uri)
        .headers(builder.build())
        .get()
        .build()
}
