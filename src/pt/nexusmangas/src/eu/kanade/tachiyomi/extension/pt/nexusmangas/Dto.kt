package eu.kanade.tachiyomi.extension.pt.nexusmangas

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

@Serializable
internal class TopWorksRequest(@SerialName("p_limit") val limit: Int)

@Serializable
internal class RankingDto(@SerialName("work_id") val workId: String)

@Serializable
internal class LatestReleaseDto(
    @SerialName("work_id") val workId: String,
)

@Serializable
internal class WorkDto(
    val id: String,
    val title: String,
    val slug: String,
    @SerialName("cover_url") val coverUrl: String? = null,
    val description: String? = null,
    @SerialName("alternative_title") val alternativeTitle: String? = null,
    val status: String? = null,
    val type: String? = null,
    @SerialName("content_rating") val contentRating: String? = null,
    @SerialName("release_year") val releaseYear: Int? = null,
    val author: String? = null,
    val artist: String? = null,
    @SerialName("work_genres") val workGenres: List<WorkGenreDto> = emptyList(),
    @SerialName("work_formats") val workFormats: List<WorkFormatDto> = emptyList(),
    val scan: ScanDto? = null,
) {
    fun toSManga() = SManga.create().apply {
        url = "/obra/$slug"
        title = this@WorkDto.title
        thumbnail_url = coverUrl
        description = listOfNotNull(
            description?.takeIf(String::isNotBlank),
            alternativeTitle?.takeIf(String::isNotBlank)?.let { "Título alternativo: $it" },
            releaseYear?.let { "Lançamento: $it" },
            type?.takeIf(String::isNotBlank)?.let { "Tipo: $it" },
            scan?.name?.takeIf(String::isNotBlank)?.let { "Scan: $it" },
        ).joinToString("\n\n").ifBlank { null }
        author = this@WorkDto.author
        artist = this@WorkDto.artist
        genre = (workGenres.mapNotNull { it.genre?.name } + workFormats.mapNotNull { it.format?.name }).joinToString(", ")
            .ifBlank { null }
        status = when (this@WorkDto.status) {
            "ONGOING", "EM_ANDAMENTO" -> SManga.ONGOING
            "COMPLETED", "CONCLUÍDO", "CONCLUIDO" -> SManga.COMPLETED
            "HIATUS", "ON_HIATUS", "HIATO" -> SManga.ON_HIATUS
            "CANCELLED", "CANCELED", "CANCELADO" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }
}

@Serializable
internal class WorkGenreDto(val genre: EmbeddedTagDto? = null)

@Serializable
internal class WorkFormatDto(val format: EmbeddedTagDto? = null)

@Serializable
internal class EmbeddedTagDto(val name: String? = null)

@Serializable
internal class ScanDto(val name: String? = null)

@Serializable
internal class ChapterDto(
    val id: String,
    val number: Float? = null,
    val title: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val pages: List<String> = emptyList(),
    val scan: ScanDto? = null,
) {
    fun toSChapter(slug: String) = SChapter.create().apply {
        val numberText = number?.display() ?: ""
        url = "/capitulo/$slug/$numberText#$id"
        name = title?.takeIf(String::isNotBlank) ?: "Capítulo $numberText".trim()
        chapter_number = number ?: -1F
        date_upload = createdAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
        scanlator = scan?.name?.takeIf(String::isNotBlank)
        memo = buildJsonObject { put("id", id) }
    }
}

@Serializable
internal class ReaderPagesDto(val pages: List<String> = emptyList())

@Serializable
internal class FilterOptionDto(val id: String, val name: String)

@Serializable
internal class FilterData(
    val genres: List<FilterOptionDto>,
    val formats: List<FilterOptionDto>,
)

internal class TypeFilter : Filter.Select<String>("Tipo", TYPE_OPTIONS.map { it.first }.toTypedArray()) {
    val selectedValue get() = TYPE_OPTIONS[state].second.takeIf(String::isNotEmpty)
}

internal class ContentRatingFilter : Filter.Select<String>("Classificação", CONTENT_RATING_OPTIONS.map { it.first }.toTypedArray()) {
    val selectedValue get() = CONTENT_RATING_OPTIONS[state].second.takeIf(String::isNotEmpty)
}

internal class GenreFilter(private val options: List<FilterOptionDto>) : Filter.Select<String>("Gênero", options.toOptionArray()) {
    val selectedValue get() = options.getOrNull(state - 1)?.id
}

internal class FormatFilter(private val options: List<FilterOptionDto>) : Filter.Select<String>("Formato", options.toOptionArray()) {
    val selectedValue get() = options.getOrNull(state - 1)?.id
}

private fun List<FilterOptionDto>.toOptionArray() = arrayOf("Todos") + map(FilterOptionDto::name)

private fun Float.display() = toString().removeSuffix(".0")

private val TYPE_OPTIONS = listOf(
    "Todos" to "",
    "Mangá" to "MANGA",
    "Manhwa" to "MANHWA",
    "Manhua" to "MANHUA",
    "Novel" to "NOVEL",
)

private val CONTENT_RATING_OPTIONS = listOf(
    "Todas" to "",
    "Safe" to "SAFE",
    "Sugestivo" to "SUGGESTIVE",
    "+18" to "ADULT",
)
