package eu.kanade.tachiyomi.extension.pt.animy

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
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class Animy : KeiSource() {

    override fun OkHttpClient.Builder.configureClient() = rateLimit(2)

    override suspend fun getPopularManga(page: Int): MangasPage {
        val offset = (page - 1) * PAGE_LIMIT
        val url = "$baseUrl/api/v1/mangas".toHttpUrl().newBuilder()
            .addQueryParameter("sort", "popular")
            .addQueryParameter("limit", PAGE_LIMIT.toString())
            .addQueryParameter("offset", offset.toString())
            .build()

        val response = client.get(url).parseAs<AnimyMangaListDto>()
        val mangas = response.items
            .filterNot { it.isNovel() }
            .map { it.toSManga() }
        val hasNextPage = (offset + response.items.size) < response.total

        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val now = System.currentTimeMillis()
        if (page <= 1 || recentMangasCache.isEmpty() || (now - recentMangasCacheTime > CACHE_TTL_MS)) {
            val endpoint = "$baseUrl/api/v1/chapters/recent?limit=200"
            val recentChapters = client.get(endpoint).parseAs<List<AnimyRecentChapterDto>>()

            val seenIds = mutableSetOf<Int>()
            val seenSlugs = mutableSetOf<String>()
            val distinctMangas = mutableListOf<SManga>()

            for (item in recentChapters) {
                if (item.isNovel()) continue
                val slug = slugify(item.mangaTitle)
                if (!seenIds.add(item.mangaId) || !seenSlugs.add(slug)) continue

                distinctMangas.add(item.toSManga())
            }

            recentMangasCache = distinctMangas
            recentMangasCacheTime = now
        }

        val startIndex = (page - 1) * PAGE_LIMIT
        if (startIndex >= recentMangasCache.size) {
            return MangasPage(emptyList(), hasNextPage = false)
        }

        val endIndex = minOf(startIndex + PAGE_LIMIT, recentMangasCache.size)
        val pageMangas = recentMangasCache.subList(startIndex, endIndex)
        val hasNextPage = endIndex < recentMangasCache.size

        return MangasPage(pageMangas, hasNextPage)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val sort = filters.valueOrEmpty<SortFilter>().ifBlank { "popular" }
        val type = filters.valueOrEmpty<TypeFilter>()
        val status = filters.valueOrEmpty<StatusFilter>()
        val demographic = filters.valueOrEmpty<DemographicFilter>()
        val contentRating = filters.valueOrEmpty<ContentRatingFilter>()
        val genres = filters.selectedGenres()

        if (query.isBlank() && sort == "newest" && type.isBlank() && status.isBlank() &&
            demographic.isBlank() && contentRating.isBlank() && genres.isEmpty()
        ) {
            return getLatestUpdates(page)
        }

        val offset = (page - 1) * PAGE_LIMIT
        val urlBuilder = "$baseUrl/api/v1/mangas".toHttpUrl().newBuilder()
            .addQueryParameter("limit", PAGE_LIMIT.toString())
            .addQueryParameter("offset", offset.toString())

        if (query.isNotBlank()) {
            urlBuilder.addQueryParameter("search", query.trim())
        }

        urlBuilder.addQueryParameter("sort", sort)

        if (type.isNotBlank()) {
            urlBuilder.addQueryParameter("type", type)
        }

        if (status.isNotBlank()) {
            urlBuilder.addQueryParameter("status", status)
        }

        if (demographic.isNotBlank()) {
            urlBuilder.addQueryParameter("demographic", demographic)
        }

        if (contentRating.isNotBlank()) {
            urlBuilder.addQueryParameter("contentRating", contentRating)
        }

        genres.forEach { genre ->
            urlBuilder.addQueryParameter("genre", genre)
        }

        val response = client.get(urlBuilder.build()).parseAs<AnimyMangaListDto>()
        val mangas = response.items
            .filterNot { it.isNovel() }
            .map { it.toSManga() }
        val hasNextPage = (offset + response.items.size) < response.total

        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val segments = url.pathSegments
        val slugOrId = when {
            segments.contains("manga") -> {
                val idx = segments.indexOf("manga")
                segments.getOrNull(idx + 1)?.takeIf { it.isNotBlank() }
            }
            segments.contains("mangas") -> {
                val idx = segments.indexOf("mangas")
                segments.getOrNull(idx + 1)?.takeIf { it.isNotBlank() }
            }
            else -> null
        } ?: return null

        val apiUrl = if (slugOrId.toIntOrNull() != null) {
            "$baseUrl/api/v1/mangas/$slugOrId"
        } else {
            "$baseUrl/api/v1/mangas/slug/$slugOrId"
        }

        val detail = runCatching {
            client.get(apiUrl).parseAs<AnimyMangaDetailDto>()
        }.getOrNull() ?: return null

        if (detail.isNovel()) {
            return null
        }

        return detail.toSManga()
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val mangaId = manga.url.substringAfter("?id=", "").substringBefore("&").toIntOrNull()
        val apiUrl = if (mangaId != null) {
            "$baseUrl/api/v1/mangas/$mangaId"
        } else {
            val slug = manga.url.removePrefix("/manga/").substringBefore("/").substringBefore("?")
            "$baseUrl/api/v1/mangas/slug/$slug"
        }

        val detail = client.get(apiUrl).parseAs<AnimyMangaDetailDto>()

        if (fetchDetails) {
            manga.title = detail.t
            manga.thumbnail_url = detail.cover
            manga.description = if (detail.isNovel()) {
                "[NOVEL TEXTUAL - Leitor incompatível]\n\n" + detail.desc.orEmpty()
            } else {
                detail.desc.orEmpty()
            }
            manga.author = detail.au.orEmpty()
            manga.artist = detail.au.orEmpty()
            manga.genre = (detail.g + listOfNotNull(detail.type, detail.demographic)).joinToString(", ")
            manga.status = detail.status.toStatus()
            manga.url = "/manga/${slugify(detail.t)}/?id=${detail.id}"
        }

        val chapterList = if (fetchChapters) {
            val mangaSlug = slugify(detail.t)
            detail.chapters
                .sortedByDescending { it.number }
                .map { ch ->
                    SChapter.create().apply {
                        val formattedNum = ch.number.formatChapterNumber()
                        chapter_number = ch.number
                        name = formatChapterName(ch.number, ch.title)
                        url = "/manga/$mangaSlug/capitulo/$formattedNum/?id=${detail.id}"
                        date_upload = parseDate(ch.date)
                    }
                }
        } else {
            chapters
        }

        return SMangaUpdate(manga, chapterList)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val mangaId = chapter.url.substringAfter("?id=", "").substringBefore("&").toIntOrNull()
            ?: resolveMangaIdFromChapterUrl(chapter.url)

        val chapterNumber = chapter.url.substringAfter("/capitulo/", "")
            .substringBefore("/")
            .takeIf { it.isNotBlank() }
            ?: chapter.chapter_number.formatChapterNumber()

        val chapterDto = client.get("$baseUrl/api/v1/mangas/$mangaId/chapters/$chapterNumber")
            .parseAs<AnimyChapterPagesDto>()

        val pages = chapterDto.pages.orEmpty()
        if (pages.isEmpty()) {
            throw Exception("Esta obra é uma novel textual e não possui páginas ilustradas compatíveis com o leitor.")
        }

        return pages.mapIndexed { index, pageDto ->
            Page(index, imageUrl = pageDto.imageUrl)
        }
    }

    override fun imageRequest(page: Page): Request {
        val imageHeaders = headersBuilder()
            .set("Referer", "$baseUrl/")
            .build()

        return super.imageRequest(page).newBuilder()
            .headers(imageHeaders)
            .build()
    }

    override fun getFilterList(data: JsonElement?): FilterList = AnimyFilters.get()

    private suspend fun resolveMangaIdFromChapterUrl(chapterUrl: String): Int {
        val slug = chapterUrl.substringAfter("/manga/").substringBefore("/capitulo/").substringBefore("/")
        val detail = client.get("$baseUrl/api/v1/mangas/slug/$slug").parseAs<AnimyMangaDetailDto>()
        return detail.id
    }

    private fun AnimyRecentChapterDto.toSManga(): SManga = SManga.create().apply {
        title = mangaTitle
        url = "/manga/${slugify(mangaTitle)}/?id=$mangaId"
        thumbnail_url = mangaCover
    }

    private fun AnimyMangaItemDto.toSManga(): SManga = SManga.create().apply {
        title = t
        url = "/manga/${slugify(t)}/?id=$id"
        thumbnail_url = cover
    }

    private fun AnimyMangaDetailDto.toSManga(): SManga = SManga.create().apply {
        title = t
        url = "/manga/${slugify(t)}/?id=$id"
        thumbnail_url = cover
        description = desc.orEmpty()
        author = au.orEmpty()
        artist = au.orEmpty()
        genre = (g + listOfNotNull(type, demographic)).joinToString(", ")
        status = this@Animy.run { this@toSManga.status.toStatus() }
    }

    private fun String?.toStatus(): Int = when (this?.lowercase(Locale.ROOT)) {
        "ongoing" -> SManga.ONGOING
        "completed" -> SManga.COMPLETED
        "hiatus" -> SManga.ON_HIATUS
        "cancelled" -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    private fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        return runCatching {
            dateFormat.parse(dateStr)?.time ?: 0L
        }.getOrDefault(0L)
    }

    companion object {
        private const val PAGE_LIMIT = 24
        private const val CACHE_TTL_MS = 60_000L
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)

        @Volatile
        private var recentMangasCache: List<SManga> = emptyList()

        @Volatile
        private var recentMangasCacheTime: Long = 0L
    }
}
