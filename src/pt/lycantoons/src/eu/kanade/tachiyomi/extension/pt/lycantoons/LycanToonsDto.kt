package eu.kanade.tachiyomi.extension.pt.lycantoons

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Instant

@Serializable
class SeriesDto(
    val title: String,
    val slug: String,
    val coverUrl: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val description: String? = null,
    val synopsis: String? = null,
    val genre: List<String>? = null,
    val status: String? = null,
    val seriesType: String? = null,
) {
    fun toSManga() = SManga.create().apply {
        title = this@SeriesDto.title
        url = "/series/$slug"
        thumbnail_url = coverUrl
        author = this@SeriesDto.author?.takeIf { it.isNotBlank() && it != "-" }
        artist = this@SeriesDto.artist?.takeIf { it.isNotBlank() && it != "-" }
        genre = this@SeriesDto.genre?.takeIf { it.isNotEmpty() }
            ?.map { tagMapping[it] ?: it }
            ?.joinToString()
        description = this@SeriesDto.description ?: this@SeriesDto.synopsis
        status = parseStatus(this@SeriesDto.status)
        initialized = true
    }
}

@Serializable
class RankingRowDto(
    val title: String,
    val slug: String,
    val coverUrl: String? = null,
) {
    fun toSManga() = SManga.create().apply {
        title = this@RankingRowDto.title
        url = "/series/$slug"
        thumbnail_url = coverUrl
        initialized = true
    }
}

@Serializable
class ChapterDto(
    val id: Int? = null,
    private val numero: JsonElement,
    private val createdAt: String? = null,
    private val pageCount: Int? = null,
) {
    fun toSChapter(slug: String) = SChapter.create().apply {
        val numberString = numero.jsonPrimitive.content
        name = "Capítulo $numberString"
        val capituloParam = id?.let { "capituloId=$it" }
        val pagesParam = pageCount?.let { "pages=$it" }
        val query = listOfNotNull(capituloParam, pagesParam).joinToString("&").let { if (it.isNotEmpty()) "?$it" else "" }
        url = "/series/$slug/$numberString$query"
        date_upload = Instant.tryParse(createdAt)
        chapter_number = numberString.toFloatOrNull() ?: -1f
    }
}

@Serializable
class ChapterPagesDto(
    val pages: List<String> = emptyList(),
)

private fun parseStatus(status: String?) = when (status?.lowercase()) {
    "ongoing" -> SManga.ONGOING
    "completed" -> SManga.COMPLETED
    "hiatus" -> SManga.ON_HIATUS
    "cancelled" -> SManga.CANCELLED
    else -> SManga.UNKNOWN
}
