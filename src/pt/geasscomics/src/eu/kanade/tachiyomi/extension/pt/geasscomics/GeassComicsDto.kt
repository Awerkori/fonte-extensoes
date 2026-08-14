package eu.kanade.tachiyomi.extension.pt.geasscomics

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import keiyoushi.utils.tryParseDateTime
import kotlinx.serialization.Serializable
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.time.Instant

@Serializable
class ApiResponse<T>(
    val data: T,
)

@Serializable
class WorkPageDto(
    val items: List<WorkDto> = emptyList(),
    val pageCount: Int = 1,
)

@Serializable
class WorkDto(
    val slug: String,
    private val title: String,
    private val cover: String? = null,
    private val kind: String? = null,
    private val status: String? = null,
    private val tags: List<String> = emptyList(),
    private val isNsfw: Boolean = false,
    private val author: String? = null,
    private val synopsis: String? = null,
    val chapters: List<ChapterDto> = emptyList(),
) {
    fun toSManga(details: Boolean = false) = SManga.create().apply {
        url = slug
        title = this@WorkDto.title
        thumbnail_url = cover
        description = synopsis
        author = this@WorkDto.author?.takeIf(String::isNotBlank)
        genre = buildList {
            kind?.takeIf(String::isNotBlank)?.replaceFirstChar(Char::uppercase)?.let(::add)
            addAll(tags)
        }.distinct().joinToString().takeIf(String::isNotBlank)
        status = when (this@WorkDto.status?.lowercase()) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            "cancelled" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
        initialized = details
    }
}

@Serializable
class ChapterDto(
    private val number: Double,
    private val title: String? = null,
    private val releasedAt: String? = null,
) {
    fun toSChapter(mangaSlug: String) = SChapter.create().apply {
        val chapterNumber = number.toString().removeSuffix(".0")
        url = "$mangaSlug/$chapterNumber"
        name = title
            ?.takeIf { it.isNotBlank() && !it.equals("Capítulo $chapterNumber", ignoreCase = true) }
            ?.let { "Capítulo $chapterNumber - $it" }
            ?: "Capítulo $chapterNumber"
        chapter_number = number.toFloat()
        date_upload = releasedAt?.let(::parseChapterDate) ?: 0L
    }
}

@Serializable
class ReaderChapterDto(
    val pages: List<String> = emptyList(),
)

@Serializable
class FilterDataDto(
    val genres: List<FilterOptionDto>,
    val tags: List<FilterOptionDto>,
)

@Serializable
class FilterOptionDto(
    val slug: String,
    val label: String,
    val isNsfw: Boolean = false,
)

private val legacyDateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

private fun parseChapterDate(value: String): Long = Instant.tryParse(value)
    .takeIf { it != 0L }
    ?: legacyDateFormat.tryParseDateTime(value, ZoneOffset.UTC)
