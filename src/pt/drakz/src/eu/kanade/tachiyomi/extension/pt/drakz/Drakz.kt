package eu.kanade.tachiyomi.extension.pt.drakz

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.toJsonRequestBody
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Source
abstract class Drakz : KeiSource() {
    override fun Headers.Builder.configureHeaders(): Headers.Builder = set("apikey", API_KEY)
        .set("Authorization", "Bearer $API_KEY")
        .set("Accept-Profile", "public")
        .set("X-Client-Info", CLIENT_INFO)

    override suspend fun getPopularManga(page: Int): MangasPage {
        val works = getWorks()
        val views = client.get("$REST_URL/manhwa_view_counts?select=manhwa_id,view_count")
            .parseAs<List<ViewDto>>()
            .associate { it.manhwaId to it.viewCount }
        val ratings = client.get("$REST_URL/ratings?select=manhwa_id,rating")
            .parseAs<List<RatingDto>>()
            .groupBy(RatingDto::manhwaId)
        val maxViews = views.values.maxOrNull()?.coerceAtLeast(1) ?: 1
        val maxRatingCount = ratings.values.maxOfOrNull(List<RatingDto>::size)?.coerceAtLeast(1) ?: 1
        return paginate(
            works.sortedByDescending { work ->
                val workRatings = ratings[work.id]
                val averageRating = workRatings?.map(RatingDto::rating)?.average() ?: work.rating.toDouble()
                val ratingCount = workRatings?.size ?: 0
                (views[work.id] ?: 0).toDouble() / maxViews * 50 +
                    averageRating / 10 * 30 +
                    ratingCount.toDouble() / maxRatingCount * 20
            },
            page,
        )
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val latest = client.post(
            "$REST_URL/rpc/get_manhwa_chapter_summaries",
            headers.newBuilder().set("Content-Profile", "public").build(),
            EmptyRequestDto().toJsonRequestBody(),
        ).parseAs<List<ChapterSummaryDto>>().associateBy(ChapterSummaryDto::manhwaId)
        return paginate(
            getWorks().sortedByDescending { latest[it.id]?.latestDate ?: "" },
            page,
        )
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val genre = filters.firstInstanceOrNull<GenreFilter>()?.selectedValue
        return paginate(
            getWorks().filter { work ->
                (genre == null || genre in work.genres) &&
                    (query.isBlank() || work.title.contains(query, true) || work.author.orEmpty().contains(query, true))
            },
            page,
        )
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.pathSegments.firstOrNull() != "manhwa") return null
        return getWork(url.pathSegments.getOrNull(1))?.let { toManga(it) }?.apply { initialized = true }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val work = getWork(manga.url) ?: error("Obra não encontrada")
        val chapterList = if (fetchChapters) getChapters(work.id) else chapters
        return SMangaUpdate(if (fetchDetails) toManga(work) else manga, chapterList)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val (manhwaId, chapterNumber) = chapter.url.split('/', limit = 2)
        val pages = client.get(
            "$REST_URL/chapter_pages?select=page_number,image_url&manhwa_id=eq.$manhwaId&chapter_id=eq.$chapterNumber&order=page_number.asc",
        ).parseAs<List<PageDto>>()
        require(pages.isNotEmpty()) { "O capítulo não possui páginas públicas." }
        val paths = pages.map(PageDto::storagePath)
        val signedUrls = signUrls("chapter-pages", paths)
        return pages.mapIndexed { index, page ->
            val signedUrl = signedUrls[page.storagePath] ?: error("Página sem URL assinada.")
            Page(index, imageUrl = "$SUPABASE_URL/storage/v1$signedUrl")
        }
    }

    override fun getMangaUrl(manga: SManga) = "$baseUrl/manhwa/${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String {
        val (manhwaId, chapterNumber) = chapter.url.split('/', limit = 2)
        return "$baseUrl/manhwa/$manhwaId/read/$chapterNumber"
    }

    override val supportsFilterFetching get() = true

    override suspend fun fetchFilterData(): JsonElement = FilterData(
        getWorks().flatMap(WorkDto::genres).distinct().sorted(),
    ).toJsonElement()

    override fun getFilterList(data: JsonElement?) = FilterList(
        GenreFilter(data?.parseAs<FilterData>()?.genres.orEmpty()),
    )

    private suspend fun getWorks(): List<WorkDto> = client.get(
        "$REST_URL/manhwas?select=*&is_active=eq.true&order=created_at.desc",
    ).parseAs()

    private suspend fun getWork(id: String?) = id?.takeIf(String::isNotBlank)?.let {
        client.get("$REST_URL/manhwas?select=*&id=eq.$it").parseAs<List<WorkDto>>().firstOrNull()
    }

    private suspend fun getChapters(manhwaId: String) = client.get(
        "$REST_URL/manhwa_chapters?select=chapter_number,title,created_at&manhwa_id=eq.$manhwaId&order=chapter_number.desc",
    ).parseAs<List<ChapterDto>>().map { it.toSChapter(manhwaId) }

    private suspend fun paginate(works: List<WorkDto>, page: Int): MangasPage {
        val from = (page - 1) * PAGE_SIZE
        val items = works.drop(from).take(PAGE_SIZE)
        val signedUrls = resolveCoverUrls(items)
        return MangasPage(
            items.map { work ->
                work.toSManga(signedUrls[work.id] ?: work.coverUrl?.takeUnless { storageLocation(it) != null })
            },
            works.size > from + PAGE_SIZE,
        )
    }

    private suspend fun toManga(work: WorkDto): SManga {
        val signedUrl = resolveCoverUrls(listOf(work))[work.id]
        return work.toSManga(signedUrl ?: work.coverUrl?.takeUnless { storageLocation(it) != null })
    }

    private suspend fun resolveCoverUrls(works: List<WorkDto>): Map<String, String> {
        val locations = works.mapNotNull { work ->
            storageLocation(work.coverUrl)?.let { work.id to it }
        }
        val signedByLocation = locations.map(Pair<String, StorageLocation>::second)
            .groupBy(StorageLocation::bucket)
            .flatMap { (bucket, bucketLocations) ->
                signUrls(bucket, bucketLocations.map(StorageLocation::path)).map { (path, signedUrl) ->
                    StorageLocation(bucket, path) to "$SUPABASE_URL/storage/v1$signedUrl"
                }
            }.toMap()
        return locations.mapNotNull { (id, location) ->
            signedByLocation[location]?.let { id to it }
        }.toMap()
    }

    private fun storageLocation(rawValue: String?): StorageLocation? {
        val value = rawValue?.replace(LEGACY_SUPABASE_HOST, SUPABASE_HOST)?.trim().orEmpty()
        if (value.isEmpty()) return null
        val url = value.toHttpUrlOrNull()
        if (url != null) {
            if (url.host != SUPABASE_HOST) return null
            val segments = url.pathSegments
            val objectIndex = segments.indexOf("object")
            if (objectIndex < 0 || segments.getOrNull(objectIndex + 1) !in STORAGE_ACCESS_TYPES) return null
            if (segments.getOrNull(objectIndex + 1) == "sign" && url.queryParameter("token") != null) return null
            val bucket = segments.getOrNull(objectIndex + 2) ?: return null
            val path = segments.drop(objectIndex + 3).joinToString("/").takeIf(String::isNotBlank) ?: return null
            return StorageLocation(bucket, path)
        }
        return STORAGE_BUCKETS.firstNotNullOfOrNull { bucket ->
            value.removePrefix("/").removePrefix("$bucket/")
                .takeIf { value.removePrefix("/").startsWith("$bucket/") && it.isNotBlank() }
                ?.let { path -> StorageLocation(bucket, path) }
        }
    }

    private suspend fun signUrls(bucket: String, paths: List<String>): Map<String, String> {
        val uniquePaths = paths.distinct()
        if (uniquePaths.isEmpty()) return emptyMap()
        return client.post(
            "$SUPABASE_URL/storage/v1/object/sign/$bucket",
            SignedUrlsRequest(uniquePaths, SIGNED_URL_EXPIRY).toJsonRequestBody(),
        ).parseAs<List<SignedUrlDto>>().mapNotNull { signed ->
            signed.signedUrl?.let { signed.path to it }
        }.toMap()
    }

    private companion object {
        const val PAGE_SIZE = 24
        const val SUPABASE_URL = "https://dmeabheryitrjbmpbvxi.supabase.co"
        const val SUPABASE_HOST = "dmeabheryitrjbmpbvxi.supabase.co"
        const val LEGACY_SUPABASE_HOST = "wpczgwxsriezaubncuom.supabase.co"
        const val REST_URL = "$SUPABASE_URL/rest/v1"
        const val API_KEY = "sb_publishable_kVrTIIaLKBDTy1iuZm3YHg_Hw8vmpb8"
        const val CLIENT_INFO = "supabase-js-web/2.106.2"
        const val SIGNED_URL_EXPIRY = 60 * 60 * 24 * 7
        val STORAGE_ACCESS_TYPES = setOf("public", "authenticated", "sign")
        val STORAGE_BUCKETS = listOf("manhwa-covers", "chapter-pages", "user-uploads")
    }
}

private data class StorageLocation(val bucket: String, val path: String)

private class GenreFilter(options: List<String>) : Filter.Select<String>("Gênero", arrayOf("Todos") + options.toTypedArray()) {
    val selectedValue get() = values[state].takeUnless { it.isBlank() || it == "Todos" }
}
