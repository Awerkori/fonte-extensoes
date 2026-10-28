package eu.kanade.tachiyomi.extension.pt.manhq

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
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
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.Normalizer
import java.util.Locale
import kotlin.math.min

private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
private const val PAGE_SIZE = 24
private const val CACHE_LIFETIME_MS = 60_000L

@Source
abstract class ManHQ : KeiSource() {

    private val apiUrl = "https://api.manhq.com.br"

    private var cachedCatalog: List<ManHQSeriesDto>? = null
    private var lastCatalogFetch: Long = 0L

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        rateLimit(permits = 2)
    }

    override fun Headers.Builder.configureHeaders(): Headers.Builder = apply {
        set("User-Agent", USER_AGENT)
        set("Referer", "$baseUrl/")
        set("Origin", baseUrl)
        set("Accept", "application/json, text/plain, */*")
        set("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
    }

    override fun imageRequest(page: Page): Request {
        val imageUrl = page.imageUrl ?: return super.imageRequest(page)
        return GET(
            imageUrl,
            headers.newBuilder()
                .set("Referer", "$baseUrl/")
                .set("Origin", baseUrl)
                .build(),
        )
    }

    private fun getCatalog(): List<ManHQSeriesDto> {
        val now = System.currentTimeMillis()
        val currentCache = cachedCatalog
        if (currentCache != null && (now - lastCatalogFetch) < CACHE_LIFETIME_MS) {
            return currentCache
        }

        val response = client.newCall(GET("$apiUrl/catalog/series?limit=100", headers)).execute()
        val catalogDto = response.parseAs<ManHQCatalogDto>()
        cachedCatalog = catalogDto.items
        lastCatalogFetch = now
        return catalogDto.items
    }

    // ============================== Popular ==============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        if (page > 1) {
            return MangasPage(emptyList(), hasNextPage = false)
        }

        val response = client.newCall(GET("$apiUrl/discover/popular?limit=50", headers)).execute()
        val popularList = response.parseAs<List<ManHQSeriesDto>>()

        val mangas = popularList.map { toSManga(it) }
        return MangasPage(mangas, hasNextPage = false)
    }

    // ============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val allSeries = getCatalog()
        return paginateList(allSeries, page)
    }

    // ============================== Search & Filters =====================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val trimmedQuery = query.trim()

        var selectedFormat = ""
        var selectedStatus = ""
        var selectedSort = "updated"
        val includedGenres = mutableListOf<String>()
        val excludedGenres = mutableListOf<String>()

        for (filter in filters) {
            when (filter) {
                is ManHQFilters.FormatFilter -> selectedFormat = filter.selected
                is ManHQFilters.StatusFilter -> selectedStatus = filter.selected
                is ManHQFilters.SortFilter -> selectedSort = filter.selected
                is ManHQFilters.GenreGroup -> {
                    for (genreChip in filter.state) {
                        when (genreChip.state) {
                            Filter.TriState.STATE_INCLUDE -> includedGenres.add(genreChip.value)
                            Filter.TriState.STATE_EXCLUDE -> excludedGenres.add(genreChip.value)
                            else -> {}
                        }
                    }
                }
                else -> {}
            }
        }

        if (trimmedQuery.length >= 2) {
            val searchUrl = HttpUrl.Builder()
                .scheme("https")
                .host("api.manhq.com.br")
                .addPathSegment("search")
                .addQueryParameter("q", trimmedQuery)
                .addQueryParameter("page", page.toString())
                .addQueryParameter("limit", PAGE_SIZE.toString())
                .build()

            val response = client.newCall(GET(searchUrl, headers)).execute()
            val searchDto = response.parseAs<ManHQSearchResponseDto>()

            var items = searchDto.items
            if (selectedFormat.isNotEmpty()) {
                items = items.filter { it.workType.equals(selectedFormat, ignoreCase = true) }
            }
            if (selectedStatus.isNotEmpty()) {
                items = items.filter { matchesStatus(it.status, selectedStatus) }
            }
            if (includedGenres.isNotEmpty() || excludedGenres.isNotEmpty()) {
                items = items.filter { series ->
                    val seriesTags = parseTagList(series.tags)
                    val includes = includedGenres.all { tag -> seriesTags.contains(tag) }
                    val excludes = excludedGenres.any { tag -> seriesTags.contains(tag) }
                    includes && !excludes
                }
            }

            val mangas = items.map { toSManga(it) }
            val pagination = searchDto.pagination
            val hasNextPage = if (pagination != null) pagination.page < pagination.totalPages else false
            return MangasPage(mangas, hasNextPage)
        }

        val allSeries = getCatalog()
        val normalizedQuery = normalizeText(trimmedQuery)

        val filtered = allSeries.filter { series ->
            if (selectedFormat.isNotEmpty() && !series.workType.equals(selectedFormat, ignoreCase = true)) {
                return@filter false
            }

            if (selectedStatus.isNotEmpty() && !matchesStatus(series.status, selectedStatus)) {
                return@filter false
            }

            if (includedGenres.isNotEmpty() || excludedGenres.isNotEmpty()) {
                val seriesTags = parseTagList(series.tags)
                if (includedGenres.isNotEmpty() && !includedGenres.all { tag -> seriesTags.contains(tag) }) {
                    return@filter false
                }
                if (excludedGenres.isNotEmpty() && excludedGenres.any { tag -> seriesTags.contains(tag) }) {
                    return@filter false
                }
            }

            if (normalizedQuery.isNotEmpty()) {
                val haystack = normalizeText(
                    listOfNotNull(
                        series.title,
                        series.author,
                        series.artist,
                        series.tags,
                    ).joinToString(" "),
                )
                if (!haystack.contains(normalizedQuery)) return@filter false
            }

            true
        }

        val sorted = when (selectedSort) {
            "popular" -> filtered.sortedWith(
                compareByDescending<ManHQSeriesDto> { it.totalReads ?: 0 }
                    .thenByDescending { it.uniqueReaders ?: 0 }
                    .thenBy { it.title },
            )
            "az" -> filtered.sortedBy { it.title.lowercase(Locale.ROOT) }
            else -> filtered // default: ordered by updatedAt descending from API
        }

        return paginateList(sorted, page)
    }

    private fun paginateList(items: List<ManHQSeriesDto>, page: Int): MangasPage {
        val fromIndex = (page - 1) * PAGE_SIZE
        if (fromIndex >= items.size) {
            return MangasPage(emptyList(), hasNextPage = false)
        }
        val toIndex = min(fromIndex + PAGE_SIZE, items.size)
        val pageItems = items.subList(fromIndex, toIndex).map { toSManga(it) }
        val hasNextPage = toIndex < items.size
        return MangasPage(pageItems, hasNextPage)
    }

    private fun toSManga(series: ManHQSeriesDto): SManga = SManga.create().apply {
        url = "/serie/${series.id}"
        title = series.title
        thumbnail_url = buildCoverUrl(series.coverUrl, series.id)
    }

    private fun buildCoverUrl(coverUrl: String?, seriesId: String): String = eu.kanade.tachiyomi.extension.pt.manhq.buildCoverUrl(apiUrl, coverUrl, seriesId)

    // ============================== Manga by URL =========================

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val path = url.encodedPath
        if (path.contains("/serie/")) {
            val id = path.substringAfter("/serie/").substringBefore("/")
            if (id.isNotEmpty()) {
                return SManga.create().apply {
                    this.url = "/serie/$id"
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
        val seriesId = manga.url.removePrefix("/serie/").substringBefore("/")
        val response = client.newCall(GET("$apiUrl/series/$seriesId", headers)).execute()
        val seriesDto = response.parseAs<ManHQSeriesDto>()

        val sManga = if (fetchDetails) {
            manga.apply {
                title = seriesDto.title
                thumbnail_url = buildCoverUrl(seriesDto.coverUrl, seriesId)
                author = seriesDto.author
                artist = seriesDto.artist ?: seriesDto.author
                genre = formatGenres(seriesDto.tags, seriesDto.workType)
                status = parseStatus(seriesDto.status)
                description = buildDescription(seriesDto)
            }
        } else {
            manga
        }

        val chapterList = if (fetchChapters) {
            seriesDto.medias
                .filter { it.isReady != false }
                .sortedWith(
                    compareByDescending<ManHQMediaDto> { it.number ?: -1f }
                        .thenByDescending { it.id },
                )
                .map { media ->
                    SChapter.create().apply {
                        url = "/read/${media.id}"
                        name = formatChapterName(media.number, media.title, media.isOneShot)
                        chapter_number = media.number ?: -1f
                        date_upload = 0L
                    }
                }
        } else {
            chapters
        }

        return SMangaUpdate(sManga, chapterList)
    }

    // ============================== Pages ================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterId = chapter.url.removePrefix("/read/").substringBefore("/")
        val response = client.newCall(GET("$apiUrl/read/$chapterId/info", headers)).execute()
        val chapterInfo = response.parseAs<ManHQChapterInfoDto>()

        if (chapterInfo.pageCount <= 0) {
            throw Exception("Nenhuma página encontrada para este capítulo.")
        }

        return (1..chapterInfo.pageCount).map { pageNum ->
            Page(pageNum - 1, imageUrl = "$apiUrl/read/$chapterId/page/$pageNum")
        }
    }

    // ============================== Filter Helpers =======================

    override fun getFilterList(data: kotlinx.serialization.json.JsonElement?): FilterList = ManHQFilters.get()

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${if (manga.url.startsWith("/")) "" else "/"}${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl${if (chapter.url.startsWith("/")) "" else "/"}${chapter.url}"

    private fun buildDescription(series: ManHQSeriesDto): String {
        val parts = mutableListOf<String>()
        val desc = series.description?.trim().orEmpty()
        if (desc.isNotEmpty()) {
            parts.add(desc)
        }
        val extraInfo = mutableListOf<String>()
        series.workType?.let { extraInfo.add("Formato: ${it.uppercase(Locale.ROOT)}") }
        series.chaptersCount?.let { extraInfo.add("Capítulos: $it") }
        if (extraInfo.isNotEmpty()) {
            if (parts.isNotEmpty()) parts.add("")
            parts.add(extraInfo.joinToString(" • "))
        }
        return parts.joinToString("\n")
    }
}

// ============================== Package-level Helpers ======================

internal fun buildCoverUrl(apiUrl: String, coverUrl: String?, seriesId: String): String {
    if (!coverUrl.isNullOrBlank()) {
        val path = if (coverUrl.startsWith("http://") || coverUrl.startsWith("https://")) {
            coverUrl.substringAfter("manhq.com.br")
        } else {
            coverUrl
        }
        val cleanPath = path.removePrefix("/api")
        return "$apiUrl${if (cleanPath.startsWith("/")) "" else "/"}$cleanPath"
    }
    return "$apiUrl/public/series/$seriesId/cover"
}

internal fun parseStatus(status: String?): Int = when (status?.lowercase(Locale.ROOT)) {
    "releasing", "ongoing" -> SManga.ONGOING
    "completed" -> SManga.COMPLETED
    "hiatus", "on_hiatus" -> SManga.ON_HIATUS
    "cancelled", "canceled" -> SManga.CANCELLED
    else -> SManga.UNKNOWN
}

internal fun matchesStatus(status: String?, filterStatus: String): Boolean = when (filterStatus) {
    "ongoing" -> status.equals("ongoing", ignoreCase = true) || status.equals("releasing", ignoreCase = true)
    "completed" -> status.equals("completed", ignoreCase = true)
    else -> true
}

internal fun parseTagList(tags: String?): List<String> {
    if (tags.isNullOrBlank()) return emptyList()
    return tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}

internal fun formatGenres(tags: String?, workType: String?): String? {
    val tagList = parseTagList(tags).toMutableList()
    if (!workType.isNullOrBlank()) {
        val prettyWorkType = when (workType.lowercase(Locale.ROOT)) {
            "manga" -> "Mangá"
            "manhwa" -> "Manhwa"
            "webtoon" -> "Webtoon"
            "comic" -> "Quadrinho"
            else -> workType.replaceFirstChar { it.uppercase() }
        }
        if (!tagList.contains(prettyWorkType)) {
            tagList.add(0, prettyWorkType)
        }
    }
    return tagList.joinToString(", ").ifEmpty { null }
}

internal fun formatChapterName(number: Float?, rawTitle: String?, isOneShot: Boolean?): String {
    val cleanTitle = rawTitle?.trim().orEmpty()

    if (cleanTitle.equals("prólogo", ignoreCase = true) || cleanTitle.equals("prologo", ignoreCase = true)) {
        return "Prólogo"
    }
    if (cleanTitle.equals("especial", ignoreCase = true)) {
        return "Especial"
    }
    if (isOneShot == true && (number == null || number == 0f)) {
        return if (cleanTitle.isNotEmpty() && !cleanTitle.startsWith("Capítulo", ignoreCase = true)) {
            cleanTitle
        } else {
            "One-shot"
        }
    }

    val numStr = number?.let {
        if (it % 1.0f == 0.0f) it.toInt().toString() else it.toString()
    }

    if (cleanTitle.startsWith("Capítulo ", ignoreCase = true)) {
        val afterCapitulo = cleanTitle.substring(9).trim()
        return "Cap. $afterCapitulo"
    }

    if (numStr != null) {
        if (cleanTitle.isNotEmpty() && !cleanTitle.equals("Capítulo", ignoreCase = true) && !cleanTitle.equals("Cap. $numStr", ignoreCase = true)) {
            return "Cap. $numStr - $cleanTitle"
        }
        return "Cap. $numStr"
    }

    return cleanTitle.ifEmpty { "Capítulo" }
}

internal fun normalizeText(text: String): String {
    val withoutAccents = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
    return withoutAccents.lowercase(Locale.ROOT)
}
