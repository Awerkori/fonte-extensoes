package eu.kanade.tachiyomi.extension.pt.drakz

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
internal class WorkDto(
    val id: String,
    val title: String,
    @SerialName("cover_url") val coverUrl: String? = null,
    val author: String? = null,
    val genres: List<String> = emptyList(),
    val rating: Float = 0F,
    val status: String? = null,
    val synopsis: String? = null,
) {
    fun toSManga(signedCoverUrl: String? = coverUrl?.takeIf { it.startsWith("http") }) = SManga.create().apply {
        url = id
        title = this@WorkDto.title
        thumbnail_url = signedCoverUrl
        author = this@WorkDto.author?.takeIf(String::isNotBlank)
        genre = genres.joinToString().takeIf(String::isNotBlank)
        description = synopsis?.takeIf(String::isNotBlank)
        status = when (this@WorkDto.status) {
            "Em andamento" -> SManga.ONGOING
            "Completo" -> SManga.COMPLETED
            "Hiato" -> SManga.ON_HIATUS
            "Cancelado" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }
}

@Serializable
internal class ViewDto(
    @SerialName("manhwa_id") val manhwaId: String,
    @SerialName("view_count") val viewCount: Int = 0,
)

@Serializable
internal class RatingDto(
    @SerialName("manhwa_id") val manhwaId: String,
    val rating: Double,
)

@Serializable
internal class ChapterSummaryDto(
    @SerialName("manhwa_id") val manhwaId: String,
    @SerialName("latest_dates") private val latestDates: List<String> = emptyList(),
) {
    val latestDate get() = latestDates.maxOrNull().orEmpty()
}

@Serializable
internal class ChapterDto(
    @SerialName("chapter_number") private val chapterNumber: Float,
    private val title: String? = null,
    @SerialName("created_at") private val createdAt: String? = null,
) {
    fun toSChapter(manhwaId: String) = SChapter.create().apply {
        val number = chapterNumber.display()
        url = "$manhwaId/$number"
        name = title?.takeIf(String::isNotBlank) ?: "Capítulo $number"
        chapter_number = chapterNumber
        date_upload = createdAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrDefault(0L) } ?: 0L
    }
}

@Serializable
internal class PageDto(
    @SerialName("page_number") val pageNumber: Int,
    @SerialName("image_url") private val imageUrl: String,
) {
    val storagePath get() = imageUrl.removePrefix("chapter-pages/")
}

@Serializable
internal class SignedUrlsRequest(val paths: List<String>, val expiresIn: Int)

@Serializable
internal class SignedUrlDto(val path: String, @SerialName("signedURL") val signedUrl: String? = null)

@Serializable
internal class EmptyRequestDto

@Serializable
internal class FilterData(val genres: List<String>)

private fun Float.display() = toString().removeSuffix(".0")
