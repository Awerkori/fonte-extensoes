package eu.kanade.tachiyomi.extension.pt.mangalivre

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.toJsonString
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.text.Normalizer

@Serializable
class WrapperDto(
    val mangas: List<MangaDto>,
    val pagination: PaginationDto,
) {
    val hasNextPage get() = pagination.hasNextPage
}

@Serializable
class PaginationDto(
    val hasNextPage: Boolean,
)

@Serializable
class MangaDto(
    val id: String,
    val title: String,
    @SerialName("coverUrl")
    val thumbnailUrl: String? = null,
    val authors: List<String>? = null,
    val artists: List<String>? = null,
    val genres: List<String>? = null,
    val description: String? = null,
    val alternativeTitle: String? = null,
    val chapters: List<ChapterDto>? = null,
    val status: String? = null,
) {
    fun toSManga(useAlternativeTitle: Boolean) = SManga.create().apply {
        this.title = if (useAlternativeTitle && !alternativeTitle.isNullOrBlank()) alternativeTitle else this@MangaDto.title
        this.thumbnail_url = this@MangaDto.thumbnailUrl
        this.description = buildString {
            if (!this@MangaDto.description.isNullOrBlank()) {
                append(this@MangaDto.description)
            }

            if (!alternativeTitle.isNullOrBlank()) {
                appendLine("${"\n".repeat(3)} Nome alternativo: $alternativeTitle")
            }
        }
        author = authors?.joinToString()
        artist = artists?.joinToString()
        genre = genres?.joinToString()
        this@MangaDto.status?.let {
            status = when (it.lowercase()) {
                "ongoing" -> SManga.ONGOING
                "completed" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }
        this.url = id
    }

    fun toSChapterList(): List<SChapter> = chapters?.map { it.toSChapter(getSlug(), id) } ?: emptyList()

    private fun getSlug(): String {
        val noDiacritics = Normalizer.normalize(title, Normalizer.Form.NFD)
            .replace(MARKS_REGEX, "")
        return noDiacritics.lowercase()
            .replace(NON_ALPHA_REGEX, "-")
            .trim('-')
    }

    companion object {
        private val MARKS_REGEX = "\\p{InCombiningDiacriticalMarks}+".toRegex()
        private val NON_ALPHA_REGEX = "[^a-z0-9]+".toRegex()
    }
}

@Serializable
class ChapterReferenceDto(
    val mangaId: String,
    val chapterId: String,
    val pageCount: Int? = null,
)

@Serializable
class ReaderAccessResponseDto(
    val chapter: ReaderChapterDto? = null,
    val protectedPages: Boolean? = null,
    val firstHandle: HandleDto? = null,
    val grant: GrantDto? = null,
    val pageCount: Int? = null,
)

@Serializable
class ReaderChapterDto(
    val pages: List<String> = emptyList(),
    val id: String? = null,
    val number: String? = null,
    val pageCount: Int? = null,
    val mangaId: String? = null,
)

@Serializable
class HandleDto(
    val token: String,
    val pageNumber: Int? = null,
    val pageCount: Int? = null,
)

@Serializable
class GrantDto(
    val token: String,
    val expiresAt: Long? = null,
)

@Serializable
class PageResponseDto(
    val imageUrl: String? = null,
    val directImage: Boolean? = null,
    val pageNumber: Int? = null,
    val pageCount: Int? = null,
    val nextHandle: HandleDto? = null,
)

@Serializable
class ReaderAccessErrorDto(
    val error: String? = null,
    val requiresTurnstile: Boolean? = null,
)

@Serializable
class ChapterDto(
    val id: String,
    val number: String,
    val timestamp: Long,
    val pageCount: Int? = null,
) {
    fun toSChapter(slug: String, mangaId: String) = SChapter.create().apply {
        name = "Capítulo $number"
        date_upload = timestamp
        val ref = ChapterReferenceDto(mangaId, id, pageCount)
        url = "/$slug/$number#${ref.toJsonString()}"
        memo = buildJsonObject {
            put("mangaId", JsonPrimitive(mangaId))
            put("chapterId", JsonPrimitive(id))
            if (pageCount != null) {
                put("pageCount", JsonPrimitive(pageCount))
            }
        }
    }
}
