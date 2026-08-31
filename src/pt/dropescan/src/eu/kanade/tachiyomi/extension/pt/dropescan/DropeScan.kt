package eu.kanade.tachiyomi.extension.pt.dropescan

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import java.net.URLEncoder
import java.time.Instant

@Source
abstract class DropeScan : KeiSource() {
    private val apiBase = "https://api.dropescan.com/v1"
    private val imageBase = "https://bucket-1.dropescan.com/"

    override suspend fun getPopularManga(page: Int): MangasPage {
        val offset = (page - 1) * PAGE_SIZE
        val response = client.get("$apiBase/obras?limit=$PAGE_SIZE&offset=$offset&sortBy=views")
        val works = response.parseAs<WorkListResponse>().data.map(::toManga)
        return MangasPage(works, works.size == PAGE_SIZE)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val firstApiPage = (page - 1) * RECENT_API_PAGES + 1
        val responses = buildList {
            for (apiPage in firstApiPage until firstApiPage + RECENT_API_PAGES) {
                val data = client.get("$apiBase/chapters/recent?page=$apiPage").parseAs<RecentResponse>().data
                add(data)
                if (data.meta?.NextPageUrl == null) break
            }
        }
        val works = responses.flatMap { it.data }.map(::toManga).distinctBy { it.url }
        val hasNext = responses.lastOrNull()?.meta?.NextPageUrl != null
        return MangasPage(works, hasNext)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val offset = (page - 1) * PAGE_SIZE
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val works = client.get("$apiBase/obras?query=$encoded&limit=$PAGE_SIZE&offset=$offset")
            .parseAs<WorkListResponse>().data.map(::toManga)
        return MangasPage(works, works.size == PAGE_SIZE)
    }

    override suspend fun getMangaByUrl(url: HttpUrl) = SManga.create().apply {
        this.url = url.encodedPath
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val id = manga.url.substringAfterLast('/').takeIf { it.isNotBlank() } ?: return SMangaUpdate(manga, chapters)
        val work = client.get("$apiBase/obra/$id").parseAs<WorkResponse>().data
        val updated = manga.apply {
            title = work.Title
            description = work.Synopsis.orEmpty()
            thumbnail_url = coverUrl(work)
            author = work.Authors.mapNotNull { it.AuthorName }.joinToString()
            artist = work.Artists.mapNotNull { it.ArtistName }.joinToString()
            genre = work.Genres.mapNotNull { it.GenreName }.joinToString()
            status = when (work.Status?.lowercase()) {
                "ongoing", "em andamento" -> SManga.ONGOING
                "completed", "completo" -> SManga.COMPLETED
                "hiatus", "on hiatus" -> SManga.ON_HIATUS
                "cancelled", "canceled" -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
        }
        val resultChapters = if (fetchChapters) {
            client.get("$apiBase/obras/$id/chapters").parseAs<ChaptersResponse>().data.chapters
                .map { toChapter(it, id) }
                .sortedByDescending { it.chapter_number }
        } else {
            chapters
        }
        return SMangaUpdate(updated, resultChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val id = chapter.url.trimEnd('/').substringBeforeLast('/').substringAfterLast('/').takeIf { it.isNotBlank() }
            ?: return emptyList()
        val chapter = client.get("$apiBase/chapter/$id").parseAs<ChapterResponse>().data.chapter
        val pages = chapter["ChapterContent"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject }
        return pages.sortedBy {
            it["pageNumber"]?.jsonPrimitive?.intOrNull
                ?: it["pageNumber"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                ?: Int.MAX_VALUE
        }.mapIndexedNotNull { index, page ->
            val source = page["source"]?.jsonPrimitive?.contentOrNull ?: return@mapIndexedNotNull null
            Page(index, imageUrl = if (source.startsWith("http")) source else "$imageBase${source.trimStart('/')}")
        }
    }

    override fun getMangaUrl(manga: SManga) = "$baseUrl${manga.url}"
    override fun getChapterUrl(chapter: SChapter) = "$baseUrl${chapter.url}"

    private fun toManga(work: WorkDto) = SManga.create().apply {
        url = "/obras/${work.ObraId}"
        title = work.Title
        thumbnail_url = coverUrl(work)
    }

    private fun toChapter(chapter: ChapterDto, obraId: String? = chapter.ChapterObraId) = SChapter.create().apply {
        url = "/obras/${obraId.orEmpty()}/${chapter.ChapterId}/1"
        chapter_number = chapter.ChapterNumber?.replace(',', '.')?.toFloatOrNull() ?: -1f
        name = chapter.ChapterName?.takeIf { it.isNotBlank() } ?: "Capítulo ${chapter.ChapterNumber.orEmpty()}"
        date_upload = chapter.CreatedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrDefault(0L) } ?: 0L
    }

    private fun coverUrl(work: WorkDto): String? {
        val source = work.Banners.firstOrNull { it.isPrimary }?.Source ?: work.Banners.firstOrNull()?.Source
        return source?.let { if (it.startsWith("http")) it else "$imageBase$it" }
    }

    private companion object {
        const val PAGE_SIZE = 24
        const val RECENT_API_PAGES = 2
    }
}
