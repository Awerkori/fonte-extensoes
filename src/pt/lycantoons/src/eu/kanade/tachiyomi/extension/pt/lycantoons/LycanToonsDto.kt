package eu.kanade.tachiyomi.extension.pt.lycantoons

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Instant

fun isRscPointer(text: String?): Boolean {
    if (text.isNullOrBlank()) return false
    val trimmed = text.trim()
    return trimmed.startsWith("$") && (trimmed.matches(RSC_REGEX) || trimmed.length <= 6)
}

fun cleanSynopsis(text: String?): String? {
    if (text.isNullOrBlank()) return null
    if (isRscPointer(text)) return null
    return text.trim()
}

fun normalizeImageUrl(url: String?, baseUrl: String = "https://lycantoons.com"): String? {
    if (url.isNullOrBlank()) return null
    val trimmed = url.trim().replace("\\/", "/").trimEnd('\\', '"', '\'')
    return when {
        trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
        trimmed.startsWith("//") -> "https:$trimmed"
        trimmed.startsWith("/file/") -> "https://cdn.lycantoons.com$trimmed"
        trimmed.startsWith("/") -> "${baseUrl.trimEnd('/')}$trimmed"
        trimmed.startsWith("file/") -> "https://cdn.lycantoons.com/$trimmed"
        else -> "${baseUrl.trimEnd('/')}/$trimmed"
    }
}

private val RSC_REGEX = Regex("""^\${'$'}L?\d+$""")

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
    val canonicalUrl: String get() = "/series/$slug"
    val normalizedThumbnailUrl: String? get() = normalizeImageUrl(coverUrl, "https://lycantoons.com")
    val cleanedDescription: String? get() = cleanSynopsis(description ?: synopsis)

    fun toSManga() = SManga.create().apply {
        title = this@SeriesDto.title
        url = canonicalUrl
        thumbnail_url = normalizedThumbnailUrl
        author = this@SeriesDto.author?.takeIf { it.isNotBlank() && it != "-" }
        artist = this@SeriesDto.artist?.takeIf { it.isNotBlank() && it != "-" }
        genre = this@SeriesDto.genre?.takeIf { it.isNotEmpty() }
            ?.map { tagMapping[it] ?: it }
            ?.joinToString()
        description = cleanedDescription
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
    val canonicalUrl: String get() = "/series/$slug"
    val normalizedThumbnailUrl: String? get() = normalizeImageUrl(coverUrl, "https://lycantoons.com")

    fun toSManga() = SManga.create().apply {
        title = this@RankingRowDto.title
        url = canonicalUrl
        thumbnail_url = normalizedThumbnailUrl
        initialized = true
    }
}

@Serializable
class ChapterDto(
    val id: Int? = null,
    val numero: JsonElement,
    val createdAt: String? = null,
    val pageCount: Int? = null,
) {
    val chapterName: String get() = "Capítulo ${numero.jsonPrimitive.content}"
    val chapterNumber: Float get() = numero.jsonPrimitive.content.toFloatOrNull() ?: -1f

    fun canonicalUrl(slug: String): String {
        val numberString = numero.jsonPrimitive.content
        val capituloParam = id?.let { "capituloId=$it" }
        val pagesParam = pageCount?.let { "pages=$it" }
        val query = listOfNotNull(capituloParam, pagesParam).joinToString("&").let { if (it.isNotEmpty()) "?$it" else "" }
        return "/series/$slug/$numberString$query"
    }

    fun toSChapter(slug: String) = SChapter.create().apply {
        name = chapterName
        url = canonicalUrl(slug)
        date_upload = Instant.tryParse(createdAt)
        chapter_number = chapterNumber
    }
}

@Serializable
class ChapterApiResponse(
    val chapters: List<ChapterDto> = emptyList(),
    val total: Int? = null,
)

@Serializable
class BookSchemaDto(
    val name: String? = null,
    val description: String? = null,
    val genre: JsonElement? = null,
) {
    fun getGenres(): List<String> {
        val element = genre ?: return emptyList()
        return try {
            if (element is JsonArray) {
                element.map { it.jsonPrimitive.content }
            } else {
                listOf(element.jsonPrimitive.content)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

@Serializable
class ChapterPagesDto(
    val pages: List<String> = emptyList(),
)

@Serializable
class SeriesSearchRequest(
    val search: String = "",
    val limit: Int = 20,
    val page: Int = 1,
    val seriesType: String? = null,
    val status: String? = null,
    val genre: List<String>? = null,
)

@Serializable
class SeriesSearchResponse(
    val series: List<SeriesDto> = emptyList(),
    val pagination: PaginationDto? = null,
)

@Serializable
class PaginationDto(
    val currentPage: Int = 1,
    val totalPages: Int = 1,
    val totalItems: Int = 0,
    val itemsPerPage: Int = 20,
    val hasNextPage: Boolean = false,
    val hasPrevPage: Boolean = false,
)

private fun parseStatus(status: String?) = when (status?.lowercase()) {
    "ongoing" -> SManga.ONGOING
    "completed" -> SManga.COMPLETED
    "hiatus" -> SManga.ON_HIATUS
    "cancelled" -> SManga.CANCELLED
    else -> SManga.UNKNOWN
}
