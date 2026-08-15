package eu.kanade.tachiyomi.extension.pt.corujatoon

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Serializable
internal class SeriesListDto(
    val series: List<SeriesDto>,
    val pagination: PaginationDto,
)

@Serializable
internal class SeriesResponseDto(val series: SeriesDto)

@Serializable
internal class PaginationDto(val page: Int, val totalPages: Int)

@Serializable
internal class SeriesDto(
    val id: String,
    val title: String,
    val slug: String,
    val description: String? = null,
    val cover: String? = null,
    val status: String? = null,
    val type: String? = null,
    val artist: String? = null,
    val author: String? = null,
    @SerialName("SeriesGenre") val genres: List<SeriesGenreDto> = emptyList(),
    @SerialName("Chapter") val chapter: List<ChapterDto> = emptyList(),
) {
    fun toSManga() = SManga.create().apply {
        url = "$slug|$id"
        title = this@SeriesDto.title.trim()
        thumbnail_url = this@SeriesDto.cover
        description = this@SeriesDto.description
        author = this@SeriesDto.author?.takeIf(String::isNotBlank)
        artist = this@SeriesDto.artist?.takeIf(String::isNotBlank)
        genre = genres.mapNotNull { it.Genre?.name }.joinToString()
        status = this@SeriesDto.status.toStatus()
    }
}

@Serializable
internal class SeriesGenreDto(val Genre: GenreDto? = null)

@Serializable
internal class GenreDto(val name: String? = null)

@Serializable
internal class ChapterDto(
    val id: String,
    val number: Double,
    val title: String? = null,
    val publishedAt: String? = null,
) {
    fun toSChapter(slug: String) = SChapter.create().apply {
        url = "$slug/$id"
        name = title?.takeIf(String::isNotBlank) ?: "Capítulo ${number.display()}"
        chapter_number = number.toFloat()
        date_upload = publishedAt?.let(::parseDate) ?: 0L
        memo = buildJsonObject {
            put("id", id)
            put("slug", slug)
            put("number", number.display())
        }
    }
}

@Serializable
internal class ChapterResponseDto(val chapter: ChapterPagesDto)

@Serializable
internal class ChapterPagesDto(val pages: List<String>)

private fun String?.toStatus(): Int = when (this?.uppercase(Locale.ROOT)) {
    "ONGOING" -> SManga.ONGOING
    "COMPLETED", "FINISHED" -> SManga.COMPLETED
    "HIATUS" -> SManga.ON_HIATUS
    "CANCELLED", "CANCELED" -> SManga.CANCELLED
    else -> SManga.UNKNOWN
}

private fun Double.display(): String = if (this % 1 == 0.0) toInt().toString() else toString()

private fun parseDate(value: String): Long? = runCatching {
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.parse(value)?.time
}.getOrNull()
