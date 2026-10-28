package eu.kanade.tachiyomi.extension.pt.sukimangas

import eu.kanade.tachiyomi.source.model.Filter
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
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.min

private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
private const val PAGE_SIZE = 24
private const val CACHE_LIFETIME_MS = 60_000L

@Source
abstract class SukiMangas : KeiSource() {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private var cachedCatalog: List<SukiMangasMangaDto>? = null
    private var lastCatalogFetch: Long = 0L

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        rateLimit(permits = 5)
    }

    override fun Headers.Builder.configureHeaders(): Headers.Builder = apply {
        set("User-Agent", USER_AGENT)
        set("Referer", "$baseUrl/")
        set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,application/json,image/webp,*/*;q=0.8")
        set("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
    }

    override fun imageRequest(page: Page): Request {
        val imageUrl = page.imageUrl
        if (imageUrl.isNullOrBlank()) {
            return super.imageRequest(page)
        }
        return buildSukiMangasImageRequest(imageUrl, baseUrl, headers)
    }

    private suspend fun getCatalog(): List<SukiMangasMangaDto> {
        val now = System.currentTimeMillis()
        val currentCache = cachedCatalog
        if (currentCache != null && (now - lastCatalogFetch) < CACHE_LIFETIME_MS) {
            return currentCache
        }

        val response = client.get("$baseUrl/api/mangas", headers = headers)
        val catalogDto = json.decodeFromString<SukiMangasCatalogDto>(response.body.string())
        cachedCatalog = catalogDto.mangas
        lastCatalogFetch = now
        return catalogDto.mangas
    }

    // ============================== Popular ==============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val allMangas = getCatalog()
        val filtered = allMangas.filter { it.type != "novel" }

        // SukiMangas "Em alta": viewsWeek descending (weekly views), with total views as tiebreaker
        val sorted = filtered.sortedWith(
            compareByDescending<SukiMangasMangaDto> { it.viewsWeek }
                .thenByDescending { it.views }
                .thenBy { it.title },
        )

        return paginateList(sorted, page)
    }

    // ============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val allMangas = getCatalog()
        val filtered = allMangas.filter { it.type != "novel" }

        // SukiMangas "Lançamentos recentes": latest chapter's releaseDate desc, then createdAt desc, then number desc
        val sorted = filtered
            .map { manga -> manga to getLatestChapter(manga) }
            .filter { it.second != null }
            .sortedWith(
                compareByDescending<Pair<SukiMangasMangaDto, SukiMangasChapterDto?>> { it.second?.releaseDate ?: "" }
                    .thenByDescending { it.second?.createdAt ?: "" }
                    .thenByDescending { it.second?.number ?: 0.0 },
            )
            .map { it.first }

        return paginateList(sorted, page)
    }

    // ============================== Search & Filters =====================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val allMangas = getCatalog()

        var selectedSort = "popular"
        var selectedStatus = ""
        var selectedType = "manga_only"
        var selectedSize = ""
        val includedGenres = mutableListOf<String>()
        val excludedGenres = mutableListOf<String>()

        for (filter in filters) {
            when (filter) {
                is SukiMangasFilters.SortFilter -> selectedSort = filter.selected
                is SukiMangasFilters.StatusFilter -> selectedStatus = filter.selected
                is SukiMangasFilters.TypeFilter -> selectedType = filter.selected
                is SukiMangasFilters.SizeFilter -> selectedSize = filter.selected
                is SukiMangasFilters.GenreGroup -> {
                    for (genreChip in filter.state) {
                        when (genreChip.state) {
                            Filter.TriState.STATE_INCLUDE -> includedGenres.add(genreChip.name)
                            Filter.TriState.STATE_EXCLUDE -> excludedGenres.add(genreChip.name)
                            else -> {}
                        }
                    }
                }
                else -> {}
            }
        }

        val normalizedQuery = normalizeText(query.trim())

        val matched = allMangas.filter { manga ->
            when (selectedType) {
                "manga_only" -> if (manga.type == "novel") return@filter false
                "novel_only" -> if (manga.type != "novel") return@filter false
                else -> {}
            }

            if (selectedStatus.isNotEmpty() && !manga.status.equals(selectedStatus, ignoreCase = true)) {
                return@filter false
            }

            if (selectedSize.isNotEmpty()) {
                val count = manga.chapters.size
                val matchesSize = when (selectedSize) {
                    "curta" -> count <= 20
                    "media" -> count in 21..60
                    "longa" -> count >= 61
                    else -> true
                }
                if (!matchesSize) return@filter false
            }

            if (includedGenres.isNotEmpty() && !includedGenres.all { tag -> manga.genres.contains(tag) }) {
                return@filter false
            }

            if (excludedGenres.isNotEmpty() && excludedGenres.any { tag -> manga.genres.contains(tag) }) {
                return@filter false
            }

            if (normalizedQuery.isNotEmpty()) {
                val haystack = normalizeText(
                    listOfNotNull(
                        manga.title,
                        manga.titleOriginal,
                        manga.author,
                        manga.group,
                        manga.genres.joinToString(" "),
                    ).joinToString(" "),
                )
                if (!haystack.contains(normalizedQuery)) return@filter false
            }

            true
        }

        val sorted = when (selectedSort) {
            "recent" ->
                matched
                    .map { manga -> manga to getLatestChapter(manga) }
                    .sortedWith(
                        compareByDescending<Pair<SukiMangasMangaDto, SukiMangasChapterDto?>> { it.second?.releaseDate ?: "" }
                            .thenByDescending { it.second?.createdAt ?: "" }
                            .thenByDescending { it.second?.number ?: 0.0 },
                    )
                    .map { it.first }
            "views" -> matched.sortedByDescending { it.views }
            "rating" -> matched.sortedByDescending { it.rating ?: 0.0 }
            "chapters" -> matched.sortedByDescending { it.chapters.size }
            "az" -> matched.sortedBy { it.title.lowercase(Locale.ROOT) }
            else -> matched.sortedWith(
                compareByDescending<SukiMangasMangaDto> { it.viewsWeek }
                    .thenByDescending { it.views }
                    .thenBy { it.title },
            )
        }

        return paginateList(sorted, page)
    }

    private fun paginateList(items: List<SukiMangasMangaDto>, page: Int): MangasPage {
        val fromIndex = (page - 1) * PAGE_SIZE
        if (fromIndex >= items.size) {
            return MangasPage(emptyList(), hasNextPage = false)
        }
        val toIndex = min(fromIndex + PAGE_SIZE, items.size)
        val pageItems = items.subList(fromIndex, toIndex).map { toSManga(it) }
        val hasNextPage = toIndex < items.size
        return MangasPage(pageItems, hasNextPage)
    }

    private fun toSManga(manga: SukiMangasMangaDto): SManga = SManga.create().apply {
        url = "/manga?id=${manga.id}"
        title = manga.title
        thumbnail_url = manga.cover ?: manga.coverThumb
    }

    // ============================== Manga by URL =========================

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val path = url.encodedPath
        if (path.startsWith("/manga")) {
            val id = url.queryParameter("id") ?: path.substringAfter("/manga/").trim('/')
            if (id.isNotEmpty()) {
                return SManga.create().apply {
                    this.url = "/manga?id=$id"
                    this.title = id
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
        val mangaId = extractMangaId(manga.url)
        val response = client.get("$baseUrl/api/mangas/$mangaId", headers = headers)
        val detailsDto = json.decodeFromString<SukiMangasDetailsDto>(response.body.string())
        val mangaDto = detailsDto.manga ?: throw Exception("Obra não encontrada.")

        val sManga = if (fetchDetails) {
            manga.apply {
                title = mangaDto.title
                thumbnail_url = mangaDto.cover ?: mangaDto.coverThumb
                author = mangaDto.author
                artist = mangaDto.artist ?: mangaDto.author
                genre = mangaDto.genres.joinToString(", ").ifEmpty { null }
                status = parseStatus(mangaDto.status)
                description = buildDescription(mangaDto)
            }
        } else {
            manga
        }

        val chapterList = if (fetchChapters) {
            mangaDto.chapters
                .filter { !it.hasContent || it.pages.isNotEmpty() }
                .sortedWith(
                    compareByDescending<SukiMangasChapterDto> { it.number ?: -1.0 }
                        .thenByDescending { it.releaseDate ?: "" },
                )
                .map { ch ->
                    SChapter.create().apply {
                        url = "/reader?ch=${ch.id}&id=$mangaId"
                        name = formatChapterName(ch.number, ch.title)
                        chapter_number = ch.number?.toFloat() ?: -1f
                        date_upload = parseIsoDate(ch.createdAt ?: ch.releaseDate)
                    }
                }
        } else {
            chapters
        }

        return SMangaUpdate(sManga, chapterList)
    }

    // ============================== Pages ================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = chapter.url
        val uri = chapterUrl.toHttpUrlOrNull()
            ?: "$baseUrl${if (chapterUrl.startsWith("/")) "" else "/"}$chapterUrl".toHttpUrl()
        val mangaId = uri.queryParameter("id") ?: chapterUrl.substringAfter("id=").substringBefore("&")
        val chapterId = uri.queryParameter("ch") ?: chapterUrl.substringAfter("ch=").substringBefore("&")

        val response = client.get("$baseUrl/api/mangas/$mangaId", headers = headers)
        val detailsDto = json.decodeFromString<SukiMangasDetailsDto>(response.body.string())
        val mangaDto = detailsDto.manga ?: throw Exception("Obra não encontrada.")

        val chapterDto = mangaDto.chapters.find { it.id == chapterId }
            ?: throw Exception("Capítulo não encontrado.")

        if (chapterDto.pages.isEmpty()) {
            throw Exception("Nenhuma página ilustrada encontrada para este capítulo.")
        }

        return chapterDto.pages.mapIndexed { index, url ->
            Page(index, imageUrl = url.trim())
        }
    }

    // ============================== Filter Helpers =======================

    override fun getFilterList(data: kotlinx.serialization.json.JsonElement?): FilterList = SukiMangasFilters.get()

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${if (manga.url.startsWith("/")) "" else "/"}${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl${if (chapter.url.startsWith("/")) "" else "/"}${chapter.url}"

    private fun getLatestChapter(manga: SukiMangasMangaDto): SukiMangasChapterDto? {
        if (manga.chapters.isEmpty()) return null
        return manga.chapters.maxWithOrNull(
            compareBy<SukiMangasChapterDto> { it.number ?: 0.0 }
                .thenBy { it.releaseDate ?: "" },
        )
    }
}

internal fun extractMangaId(url: String): String = if (url.contains("id=")) {
    url.substringAfter("id=").substringBefore("&").trim('/')
} else {
    url.substringAfter("/manga/").substringBefore("?").trim('/')
}

internal fun buildSukiMangasImageRequest(imageUrl: String, baseUrl: String, baseHeaders: Headers? = null): Request {
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
        host.contains("sukimangas") -> {
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

internal fun formatChapterName(number: Double?, rawTitle: String?): String {
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
            !extra.matches(redundantSecond) &&
            !extra.equals("Capítulo", ignoreCase = true)
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

internal fun parseStatus(status: String?): Int = when (status?.trim()?.lowercase(Locale.ROOT)) {
    "em andamento", "em lançamento" -> SManga.ONGOING
    "finalizado", "completo" -> SManga.COMPLETED
    "em hiatus", "hiato", "pausado" -> SManga.ON_HIATUS
    "cancelado" -> SManga.CANCELLED
    else -> SManga.UNKNOWN
}

internal fun parseIsoDate(dateStr: String?): Long {
    if (dateStr.isNullOrBlank()) return 0L
    return try {
        val trimmed = dateStr.trim()
        if (trimmed.length == 10 && trimmed.count { it == '-' } == 2) {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            sdf.parse(trimmed)?.time ?: 0L
        } else {
            val datePart = trimmed.substringBefore("+").substringBefore("Z").substringBefore(".")
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            sdf.parse(datePart)?.time ?: 0L
        }
    } catch (_: Exception) {
        0L
    }
}

internal fun buildDescription(mangaDto: SukiMangasMangaDto): String? {
    val desc = StringBuilder()
    if (!mangaDto.synopsis.isNullOrBlank()) {
        desc.append(mangaDto.synopsis.trim())
    }
    val metaParts = mutableListOf<String>()
    if (!mangaDto.titleOriginal.isNullOrBlank()) {
        metaParts.add("Título original: ${mangaDto.titleOriginal.trim()}")
    }
    if (!mangaDto.group.isNullOrBlank()) {
        metaParts.add("Scan: ${mangaDto.group.trim()}")
    }
    if (mangaDto.year != null && mangaDto.year > 0) {
        metaParts.add("Ano: ${mangaDto.year}")
    }
    if (mangaDto.rating != null && mangaDto.rating > 0.0) {
        metaParts.add("Avaliação: ★ ${mangaDto.rating}")
    }
    if (metaParts.isNotEmpty()) {
        if (desc.isNotEmpty()) desc.append("\n\n")
        desc.append(metaParts.joinToString("\n"))
    }
    return desc.toString().ifEmpty { null }
}

internal fun normalizeText(text: String?): String {
    if (text.isNullOrBlank()) return ""
    return Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("""\p{M}"""), "")
        .lowercase(Locale.ROOT)
}
