package eu.kanade.tachiyomi.extension.pt.cerisescans

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat

private val microsecondsRegex = Regex("""(\.\d{3})\d+""")

@Serializable
internal class ComicListResponse(
    private val data: List<ComicDto>,
    private val totalPages: Int? = null,
) {
    fun toMangasPage(
        baseUrl: String,
        currentPage: Int = 1,
        hasNextPage: Boolean = totalPages?.let { currentPage < it } ?: false,
    ): MangasPage = MangasPage(
        data.filter { it.isAvailable() }.map { it.toSManga(baseUrl) },
        hasNextPage,
    )
}

@Serializable
internal class ComicDto(
    private val id: String,
    private val slug: String,
    private val title: String,
    private val description: String? = null,
    @SerialName("cover_image") private val coverImage: String? = null,
    private val author: String? = null,
    private val artist: String? = null,
    private val status: String? = null,
    private val genres: List<String> = emptyList(),
    private val type: String? = null,
    @SerialName("is_private") private val isPrivate: Boolean = false,
    private val notice: String? = null,
    private val lastChapters: List<ChapterDto> = emptyList(),
) {
    fun isAvailable(): Boolean = !isPrivate

    fun toSManga(baseUrl: String, initialized: Boolean = false): SManga = SManga.create().apply {
        title = this@ComicDto.title
        url = "/comic/$slug?id=$id"
        thumbnail_url = coverImage?.toAbsoluteUrl(baseUrl)
        description = buildDescription()
        author = this@ComicDto.author?.takeIf { it.isNotEmpty() }
        artist = this@ComicDto.artist?.takeIf { it.isNotEmpty() }
        genre = genres.joinToString().takeIf { it.isNotEmpty() }
        status = this@ComicDto.status.toMangaStatus()
        this.initialized = initialized
    }

    fun toSChapterList(dateFormat: SimpleDateFormat): List<SChapter> = lastChapters
        .filter { it.isAvailable() }
        .map { it.toSChapter(slug, dateFormat) }
        .sortedWith(compareByDescending<SChapter> { it.chapter_number }.thenByDescending { it.date_upload })

    private fun buildDescription(): String? = buildString {
        description?.takeIf { it.isNotEmpty() }?.let { append(it) }

        notice?.takeIf { it.isNotEmpty() }?.let {
            if (isNotEmpty()) append("\n\n")
            append("Aviso: $it")
        }

        type?.takeIf { it.isNotEmpty() }?.let {
            if (isNotEmpty()) append("\n\n")
            append("Tipo: $it")
        }
    }.takeIf { it.isNotEmpty() }
}

@Serializable
internal class ChapterDto(
    private val id: String,
    private val number: String,
    private val title: String? = null,
    @SerialName("created_at") private val createdAt: String? = null,
    @SerialName("is_private") private val isPrivate: Boolean = false,
) {
    fun isAvailable(): Boolean = !isPrivate

    fun toSChapter(mangaSlug: String, dateFormat: SimpleDateFormat): SChapter = SChapter.create().apply {
        val chapterNumber = number.removeSuffix(".0")
        name = title?.takeIf { it.isNotEmpty() } ?: "Capitulo $chapterNumber"
        url = "/read/$mangaSlug/$chapterNumber?id=$id"
        chapter_number = number.toFloatOrNull() ?: 0F
        date_upload = dateFormat.tryParse(createdAt?.normalizeApiDate())
    }
}

private fun String?.toMangaStatus(): Int = when (this?.lowercase()) {
    "ongoing" -> SManga.ONGOING
    "completed" -> SManga.COMPLETED
    "hiatus" -> SManga.ON_HIATUS
    "cancelled", "dropped" -> SManga.CANCELLED
    else -> SManga.UNKNOWN
}

private fun String.normalizeApiDate(): String = replace(microsecondsRegex, "$1")

internal fun String.toAbsoluteUrl(baseUrl: String): String = when {
    startsWith("http") -> this
    startsWith("//") -> "https:$this"
    startsWith("/") -> "$baseUrl$this"
    else -> "$baseUrl/$this"
}
