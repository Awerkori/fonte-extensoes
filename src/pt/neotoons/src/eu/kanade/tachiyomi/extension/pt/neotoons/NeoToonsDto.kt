package eu.kanade.tachiyomi.extension.pt.neotoons

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Serializable
class MangaPageDto(
    val items: List<MangaDto> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val totalPages: Int = 1,
)

@Serializable
class MangaDto(
    val id: String = "",
    val title: String,
    val slug: String,
    val alternativeTitles: List<String> = emptyList(),
    val synopsis: String? = null,
    val coverUrl: String? = null,
    val bannerUrl: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val status: String? = null,
    val demographic: String? = null,
    val releaseYear: Int? = null,
    val viewsCount: Int = 0,
    val ratingAverage: String? = null,
    val genres: List<MangaGenreRelationDto> = emptyList(),
    val chapters: List<ChapterDto> = emptyList(),
    val _count: MangaCountDto? = null,
)

@Serializable
class MangaCountDto(
    val chapters: Int = 0,
    val bookmarks: Int = 0,
    val ratings: Int = 0,
)

@Serializable
class MangaGenreRelationDto(
    val genre: GenreDto? = null,
)

@Serializable
class GenreDto(
    val id: String = "",
    val name: String,
    val slug: String,
)

@Serializable
class ChapterDto(
    val id: String = "",
    val chapterNumber: String,
    val title: String? = null,
    val volume: String? = null,
    val releasedAt: String? = null,
    val createdAt: String? = null,
)

@Serializable
class ChapterReadResponseDto(
    val id: String = "",
    val chapterNumber: String = "",
    val title: String? = null,
    val pages: List<ChapterPageDto> = emptyList(),
)

@Serializable
class ChapterPageDto(
    val id: String = "",
    val pageNumber: Int = 0,
    val imageUrl: String = "",
)

fun MangaDto.isTextNovel(): Boolean {
    // Only exclude raw unformatted scraper dumps that have 0 chapters
    val chapterCount = _count?.chapters ?: chapters.size
    if (chapterCount == 0 && RAW_NOVEL_DUMP_REGEX.matches(title)) {
        return true
    }
    return false
}

private val RAW_NOVEL_DUMP_REGEX = Regex("""(?i)^\d+(\.\d+)?[km]?novel[a-z0-9].*""")

fun MangaDto.toSManga(baseUrl: String): SManga = SManga.create().apply {
    title = this@toSManga.title
    thumbnail_url = coverUrl?.takeIf { it.isNotBlank() } ?: ""
    url = "/obras/$slug"
    author = this@toSManga.author?.takeIf { it.isNotBlank() }
    artist = this@toSManga.artist?.takeIf { it.isNotBlank() } ?: author
    description = buildString {
        synopsis?.takeIf { it.isNotBlank() }?.let { append(it.trim()) }
        if (alternativeTitles.isNotEmpty()) {
            if (isNotEmpty()) append("\n\n")
            append("Títulos alternativos: ").append(alternativeTitles.joinToString(", "))
        }
        releaseYear?.let {
            if (isNotEmpty()) append("\n\n")
            append("Ano de lançamento: ").append(it)
        }
    }
    genre = genres.mapNotNull { it.genre?.name }.joinToString(", ").takeIf { it.isNotBlank() }
    status = parseStatus(this@toSManga.status)
}

fun ChapterDto.toSChapter(mangaSlug: String): SChapter = SChapter.create().apply {
    val numStr = chapterNumber.trim()
    val cleanTitle = title?.trim()?.takeIf { it.isNotBlank() }
    name = when {
        cleanTitle != null && cleanTitle.contains(numStr) -> cleanTitle
        cleanTitle != null -> "Capítulo $numStr - $cleanTitle"
        else -> "Capítulo $numStr"
    }
    url = "/obras/$mangaSlug/$numStr"
    chapter_number = numStr.toFloatOrNull() ?: -1f
    date_upload = parseIsoDate(releasedAt ?: createdAt)
}

private fun parseStatus(status: String?): Int = when (status?.uppercase()) {
    "ONGOING" -> SManga.ONGOING
    "COMPLETED" -> SManga.COMPLETED
    "HIATUS", "ON_HIATUS" -> SManga.ON_HIATUS
    "CANCELLED", "CANCELED" -> SManga.CANCELLED
    else -> SManga.UNKNOWN
}

internal fun parseIsoDate(dateStr: String?): Long {
    if (dateStr.isNullOrBlank()) return 0L
    return try {
        val datePart = dateStr.substringBefore(".")
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        sdf.parse(datePart)?.time ?: 0L
    } catch (_: Exception) {
        0L
    }
}
