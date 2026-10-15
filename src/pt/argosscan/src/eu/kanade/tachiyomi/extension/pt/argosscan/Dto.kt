package eu.kanade.tachiyomi.extension.pt.argosscan

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlin.time.Instant
import java.time.Instant as JInstant

@Serializable
class Projects(
    val items: List<Project> = emptyList(),
) {
    fun toMangasPage(query: String = ""): MangasPage {
        val mangas = items.filter { it.title.contains(query, ignoreCase = true) }
            .map { it.toSManga() }
        return MangasPage(mangas, false)
    }
}

@Serializable
class Project(
    val id: String,
    val title: String,
    val slug: String,
    val type: String? = null,
    private val description: String? = null,
    private val status: String? = null,
    @SerialName("cover_latest_url") private val coverLatestUrl: String? = null,
    @SerialName("updated_at") private val updatedAt: String? = null,
    @SerialName("created_at") private val createdAt: String? = null,
    private val authors: List<Author> = emptyList(),
    private val tags: List<Tag> = emptyList(),
) {
    fun latestUpdateInstant(): JInstant? = updatedAt.parseInstant() ?: createdAt.parseInstant()

    fun toSManga() = SManga.create().apply {
        url = "/manga/$slug"
        title = this@Project.title
        thumbnail_url = coverLatestUrl
        description = this@Project.description
        status = when (this@Project.status?.lowercase()) {
            "completo" -> SManga.COMPLETED
            "em lançamento" -> SManga.ONGOING
            "em pausa" -> SManga.ON_HIATUS
            "cancelado" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
        author = authors.filter { it.role == null || it.role == "author" }
            .joinToString { it.name }
            .takeIf { it.isNotBlank() }
        artist = authors.filter { it.role == "artist" }
            .joinToString { it.name }
            .takeIf { it.isNotBlank() }
        genre = tags.joinToString { it.name }.takeIf { it.isNotBlank() }
        memo = buildJsonObject { put("projectId", id) }
    }
}

private fun String?.parseInstant(): JInstant? = runCatching {
    this?.let { OffsetDateTime.parse(it, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant() }
}.getOrNull()

@Serializable
class Author(
    val name: String,
    val role: String? = null,
)

@Serializable
class Tag(
    val name: String,
)

@Serializable
class Chapters(
    private val items: List<Chapter> = emptyList(),
) {
    fun toSChapterList(projectId: String): List<SChapter> = items
        .sortedWith(compareByDescending<Chapter> { it.volumeNumber }.thenByDescending { it.chapterNumber })
        .map { it.toSChapter(projectId) }
}

@Serializable
class Chapter(
    val id: String = "",
    @SerialName("project_id") val projectId: String? = null,
    private val title: String? = null,
    val type: String? = null,
    private val path: String? = null,
    private val content: String? = null,
    val status: String? = null,
    @SerialName("chapter_number") val chapterNumber: Float? = null,
    @SerialName("volume_number") val volumeNumber: Int? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("scheduled_at") val scheduledAt: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    val images: List<Image>? = null,
) {
    fun isNovelText(): Boolean = type?.let { value ->
        value.contains("novel", ignoreCase = true) || value.contains("text", ignoreCase = true)
    } == true

    fun vipMessage(now: JInstant = JInstant.now()): String {
        val release = scheduledAt?.let { runCatching { JInstant.parse(it) }.getOrNull() }
        val remaining = release?.let { Duration.between(now, it) }?.takeIf { !it.isNegative && !it.isZero }
        return if (remaining == null) {
            "🔒 Capítulo temporariamente exclusivo para VIP."
        } else {
            "🔒 Capítulo temporariamente exclusivo para VIP. Liberação gratuita em ${remaining.toCountdown()}."
        }
    }

    fun toPages(): List<Page> = images?.takeIf { it.isNotEmpty() }?.mapIndexed { index, image ->
        Page(index, imageUrl = image.fileUrl)
    } ?: throw IllegalStateException("Capítulo não possui páginas disponíveis.")

    fun toSChapter(projectId: String) = SChapter.create().apply {
        url = "$id|$projectId"
        name = buildString {
            if (volumeNumber != null) append("Vol. $volumeNumber ")
            append("Cap. ")
            append(chapterNumber?.toString()?.removeSuffix(".0") ?: "0")
            if (!this@Chapter.title.isNullOrBlank()) append(" - ${this@Chapter.title}")
        }.trim()
        date_upload = (publishedAt ?: createdAt)?.let { Instant.tryParse(it) } ?: 0L
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
class Image(
    @SerialName("file_url") val fileUrl: String,
)
