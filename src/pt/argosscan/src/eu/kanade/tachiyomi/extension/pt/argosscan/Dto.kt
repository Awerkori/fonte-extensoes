package eu.kanade.tachiyomi.extension.pt.argosscan

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

@Serializable
class ProjectResponseDto(
    private val items: List<ProjectDto> = emptyList(),
) {
    fun allItems(): List<ProjectDto> = items

    fun toSMangaList(query: String = "", ids: Set<String> = emptySet()): List<SManga> = items.filter { ids.isEmpty() || it.id in ids }
        .filter { it.type?.equals("Novel", ignoreCase = true) != true }
        .filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }
        .map { it.toSManga() }

    fun toSMangaById(ids: Set<String>, includeNovels: Boolean = true): Map<String, SManga> = items
        .filter { it.id in ids && (includeNovels || !it.type.equals("Novel", ignoreCase = true)) }
        .associate { it.id to it.toSManga() }
}

@Serializable
class ProjectDto(
    val id: String,
    val title: String,
    val slug: String,
    val type: String? = null,
    private val description: String? = null,
    val status: String? = null,
    @SerialName("cover_latest_url") private val coverLatestUrl: String? = null,
    @SerialName("updated_at") private val updatedAt: String? = null,
    @SerialName("created_at") private val createdAt: String? = null,
    private val authors: List<AuthorDto> = emptyList(),
    private val tags: List<TagDto> = emptyList(),
) {
    fun latestUpdateInstant(): Instant? = updatedAt.parseInstant() ?: createdAt.parseInstant()

    fun toSManga() = SManga.create().apply {
        url = "/manga/$slug"
        title = this@ProjectDto.title
        thumbnail_url = coverLatestUrl
        description = this@ProjectDto.description
        status = when (this@ProjectDto.status?.lowercase()) {
            "completo" -> SManga.COMPLETED
            "em lançamento" -> SManga.ONGOING
            "em pausa" -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
        // Fixed: The API returns the roles in English ("Author", "Artist"), not just Portuguese
        author = authors.filter { it.role.equals("Author", true) || it.role.equals("autor", true) }
            .joinToString { it.name }
            .takeIf { it.isNotBlank() }
        artist = authors.filter { it.role.equals("Artist", true) || it.role.equals("artista", true) }
            .joinToString { it.name }
            .takeIf { it.isNotBlank() }
        genre = tags.joinToString { it.name }.takeIf { it.isNotBlank() }
    }
}

private fun String?.parseInstant(): Instant? = runCatching {
    this?.let { OffsetDateTime.parse(it, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant() }
}.getOrNull()

@Serializable
class AuthorDto(
    val name: String,
    val role: String? = null,
)

@Serializable
class TagDto(
    val name: String,
)

@Serializable
class ChapterResponseDto(
    private val items: List<ChapterDto> = emptyList(),
) {
    fun itemCount(): Int = items.size

    fun sortedForLatest(): List<ChapterDto> = items.sortedByDescending { it.publishedInstant() }

    fun dedupedProjectIds(): List<String> = items.asSequence()
        .filter { it.status?.equals("published", ignoreCase = true) != false }
        .sortedByDescending { it.publishedInstant() }
        .mapNotNull { it.projectId }
        .distinct()
        .toList()

    fun projectIdsPage(page: Int, pageSize: Int): List<String> = items.asSequence()
        .filter { it.status?.equals("published", ignoreCase = true) != false }
        .sortedByDescending { it.publishedInstant() }
        .mapNotNull { it.projectId }
        .distinct()
        .drop((page - 1) * pageSize)
        .take(pageSize)
        .toList()

    fun hasNextPage(page: Int, pageSize: Int): Boolean = items.asSequence()
        .filter { it.status?.equals("published", ignoreCase = true) != false }
        .sortedByDescending { it.publishedInstant() }
        .mapNotNull { it.projectId }
        .distinct()
        .drop(page * pageSize)
        .iterator()
        .hasNext()

    fun toSChapterList(projectId: String, dateFormat: SimpleDateFormat): List<SChapter> = items.sortedWith(compareByDescending<ChapterDto> { it.volumeNumber }.thenByDescending { it.chapterNumber })
        .map { it.toSChapter(projectId, dateFormat) }

    fun getImagesForChapter(chapterId: String): List<Page> {
        val chapter = items.find { it.id == chapterId }
            ?: throw Exception("Capítulo não encontrado.")
        return chapter.images?.mapIndexed { i, img ->
            Page(i, imageUrl = img.fileUrl)
        } ?: emptyList()
    }
}

private fun ChapterDto.publishedInstant(): Instant = sequenceOf(publishedAt, createdAt)
    .filterNotNull()
    .mapNotNull { value ->
        runCatching {
            OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
        }.getOrNull()
    }
    .firstOrNull() ?: Instant.MIN

private class ChapterDateInfo(val kind: String)

private fun ChapterDto.dateInfo(): ChapterDateInfo {
    if (publishedAt != null && runCatching { OffsetDateTime.parse(publishedAt, DateTimeFormatter.ISO_OFFSET_DATE_TIME) }.isSuccess) {
        return ChapterDateInfo("published")
    }
    if (createdAt != null && runCatching { OffsetDateTime.parse(createdAt, DateTimeFormatter.ISO_OFFSET_DATE_TIME) }.isSuccess) {
        return ChapterDateInfo("created")
    }
    return ChapterDateInfo("invalid")
}

@Serializable
class ChapterDto(
    val id: String = "",
    @SerialName("project_id") val projectId: String? = null,
    private val title: String? = null,
    val type: String? = null,
    private val path: String? = null,
    private val content: String? = null,
    val status: String? = null,
    @SerialName("published_revision_id") private val publishedRevisionId: String? = null,
    @SerialName("chapter_number") val chapterNumber: Float? = null,
    @SerialName("volume_number") val volumeNumber: Int? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("scheduled_at") val scheduledAt: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    val images: List<ImageDto>? = null,
) {
    fun isNovelText(): Boolean = type?.let { value ->
        value.contains("novel", ignoreCase = true) || value.contains("text", ignoreCase = true)
    } == true

    fun vipMessage(now: Instant = Instant.now()): String {
        val release = scheduledAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val remaining = release?.let { Duration.between(now, it) }?.takeIf { !it.isNegative && !it.isZero }
        return if (remaining == null) {
            "🔒 Capítulo temporariamente exclusivo para VIP."
        } else {
            "🔒 Capítulo temporariamente exclusivo para VIP. Liberação gratuita em ${remaining.toCountdown()}."
        }
    }

    fun toPages(): List<Page> = images?.takeIf { it.isNotEmpty() }?.mapIndexed { index, image ->
        Page(index, imageUrl = image.fileUrl)
    } ?: throw IllegalStateException("Capítulo não possui páginas disponíveis no endpoint individual.")
    fun toSChapter(projectId: String, dateFormat: SimpleDateFormat) = SChapter.create().apply {
        url = "$id|$projectId"
        name = buildString {
            if (volumeNumber != null) append("Vol. $volumeNumber ")
            append("Cap. ")
            append(chapterNumber?.toString()?.removeSuffix(".0") ?: "0")
            if (!this@ChapterDto.title.isNullOrBlank()) append(" - ${this@ChapterDto.title}")
        }.trim()
        date_upload = createdAt?.substringBefore(".")?.let {
            dateFormat.tryParse(it)
        } ?: 0L
    }
}

private fun Duration.toCountdown(): String {
    val totalMinutes = toMinutes()
    val days = totalMinutes / (24 * 60)
    val hours = (totalMinutes % (24 * 60)) / 60
    val minutes = totalMinutes % 60
    return buildList {
        if (days > 0) add("${days}d")
        if (hours > 0) add("${hours}h")
        if (minutes > 0 || isEmpty()) add("${minutes}min")
    }.joinToString(" ")
}

@Serializable
class ImageDto(
    @SerialName("file_url") val fileUrl: String,
)
