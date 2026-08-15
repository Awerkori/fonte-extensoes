package eu.kanade.tachiyomi.extension.pt.inkscan

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

private val apiDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}

private val microsecondsRegex = Regex("""(\.\d{3})\d+""")

@Serializable
internal class WorkDto(
    private val id: String,
    private val titulo: String,
    @SerialName("capa_url") private val coverUrl: String? = null,
    private val descricao: String? = null,
    private val status: String? = null,
    private val tipo: String? = null,
    private val formato: String? = null,
    private val generos: List<String>? = null,
    private val tags: List<String>? = null,
    private val autor: String? = null,
    private val artista: String? = null,
    @SerialName("titulos_alternativos") private val alternativeTitles: List<String>? = null,
    @SerialName("total_capitulos") private val totalChapters: Int? = null,
) {
    fun id(): String = id

    fun toSManga(initialized: Boolean = false): SManga = SManga.create().apply {
        title = titulo
        url = "/manga/$id"
        thumbnail_url = coverUrl
        description = buildDescription()
        author = this@WorkDto.autor
        artist = this@WorkDto.artista
        genre = (generos.orEmpty() + tags.orEmpty()).distinct().joinToString()
        status = this@WorkDto.status.toMangaStatus()
        this.initialized = initialized
    }

    private fun buildDescription(): String? = buildString {
        descricao?.takeIf { it.isNotEmpty() }?.let { append(it) }

        alternativeTitles.orEmpty()
            .filter(String::isNotEmpty)
            .takeIf { it.isNotEmpty() }
            ?.let {
                if (isNotEmpty()) append("\n\n")
                append("Titulos alternativos: ${it.joinToString()}")
            }

        listOfNotNull(
            tipo?.takeIf { it.isNotEmpty() }?.let { "Tipo: $it" },
            formato?.takeIf { it.isNotEmpty() }?.let { "Formato: $it" },
            totalChapters?.let { "Capitulos: $it" },
        )
            .takeIf { it.isNotEmpty() }
            ?.let {
                if (isNotEmpty()) append("\n\n")
                append(it.joinToString("\n"))
            }
    }.takeIf { it.isNotEmpty() }
}

@Serializable
internal class SearchResultDto(
    private val id: String,
) {
    fun id(): String = id
}

@Serializable
internal class LatestChapterDto(
    @SerialName("obras") val work: WorkDto? = null,
)

@Serializable
internal class ChapterDto(
    private val id: String,
    private val numero: Double,
    private val titulo: String? = null,
    @SerialName("created_at") private val createdAt: String? = null,
) {
    fun toSChapter(workId: String): SChapter = SChapter.create().apply {
        val chapterNumber = numero.formatChapterNumber()
        name = titulo?.takeIf { it.isNotEmpty() } ?: "Capitulo $chapterNumber"
        url = "/manga/$workId/capitulo/$chapterNumber?id=$id"
        chapter_number = numero.toFloat()
        date_upload = apiDateFormat.tryParse(createdAt?.normalizeApiDate())
    }
}

@Serializable
internal class FolderDto(
    private val id: String,
    private val slug: String? = null,
    @SerialName("pasta_s3") private val s3Folder: String? = null,
    @SerialName("is_acervo_b") private val isArchiveB: Boolean = false,
) {
    fun folderName(): String = s3Folder?.takeIf { it.isNotEmpty() }
        ?: slug?.takeIf { it.isNotEmpty() }
        ?: throw IllegalStateException("Obra $id sem pasta_s3 definida")

    fun cdnBaseUrl(): String = if (isArchiveB) ARCHIVE_CDN_URL else CDN_URL
}

@Serializable
internal class ChapterPagesDto(
    private val numero: Double,
    @SerialName("total_paginas") private val totalPages: Int? = null,
    @SerialName("updated_at") private val updatedAt: String? = null,
    private val paginas: List<Int>? = null,
    private val arquivos: List<PageFileDto>? = null,
) {
    fun toPageList(folder: FolderDto): List<Page> {
        val pages = arquivos
            ?.takeIf { it.isNotEmpty() }
            ?.sortedBy { it.pageNumber() }
            ?.map { it.pageNumber() to it.fileName() }
            ?: pageNumbers().map { it to null }

        val version = apiDateFormat.tryParse(updatedAt?.normalizeApiDate())
        return pages.mapIndexed { index, (pageNumber, fileName) ->
            Page(
                index,
                imageUrl = buildImageUrl(folder, pageNumber, fileName, version),
            )
        }
    }

    private fun pageNumbers(): List<Int> {
        val existingPages = paginas.orEmpty()
        if (existingPages.isNotEmpty()) return existingPages.sorted()

        val count = totalPages ?: 0
        return if (count > 0) (1..count).toList() else emptyList()
    }

    private fun buildImageUrl(
        folder: FolderDto,
        pageNumber: Int,
        fileName: String?,
        version: Long,
    ): String {
        val builder = folder.cdnBaseUrl().toHttpUrl().newBuilder()
            .addPathSegments(folder.folderName())
            .addPathSegment("Cap_${numero.formatChapterNumber()}")
            .addPathSegments(fileName?.takeIf { it.isNotEmpty() } ?: "Pag_$pageNumber.webp")

        if (version > 0L) {
            builder.setQueryParameter("v", version.toString())
        }

        return builder.build().toString()
    }
}

@Serializable
internal class PageFileDto(
    private val ordem: Int,
    private val filename: String? = null,
) {
    fun pageNumber(): Int = ordem

    fun fileName(): String? = filename
}

@Serializable
internal class SearchRequestDto(
    @SerialName("search_term") private val searchTerm: String,
    @SerialName("max_results") private val maxResults: Int,
    @SerialName("acervo_b_only") private val archiveBOnly: Boolean,
)

@Serializable
internal class AuthRequestDto(
    @SerialName("refresh_token") private val refreshToken: String? = null,
)

@Serializable
internal class AuthResponseDto(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
)

@Serializable
internal class AuthStorageDto(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_at") val expiresAt: Long? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
)

@Serializable
internal class ChapterRequestDto(
    private val id: String,
)

private fun String?.toMangaStatus(): Int = when (this?.lowercase()) {
    "ongoing" -> SManga.ONGOING
    "completed" -> SManga.COMPLETED
    "hiatus" -> SManga.ON_HIATUS
    "cancelled" -> SManga.CANCELLED
    else -> SManga.UNKNOWN
}

private fun String.normalizeApiDate(): String = replace(microsecondsRegex, "$1")

private fun Double.formatChapterNumber(): String = toString().removeSuffix(".0")

private const val CDN_URL = "https://cdn.inkscann.live"
private const val ARCHIVE_CDN_URL = "https://inck2.inkscann.live"
