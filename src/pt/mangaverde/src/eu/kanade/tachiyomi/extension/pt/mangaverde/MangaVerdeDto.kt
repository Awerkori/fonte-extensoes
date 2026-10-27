package eu.kanade.tachiyomi.extension.pt.mangaverde

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Serializable
class MangaVerdeListDto(
    val mangas: List<MangaVerdeMangaDto> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val limit: Int = 24,
    val totalPages: Int = 1,
)

@Serializable
class MangaVerdeMangaDto(
    val id: String = "",
    val title: String = "",
    val alternativeTitles: String? = null,
    val slug: String = "",
    val description: String? = null,
    val coverUrl: String? = null,
    val bannerUrl: String? = null,
    val type: String? = null,
    val status: String? = null,
    val isNsfw: Boolean = false,
    val isUncensored: Boolean = false,
    val isFeatured: Boolean = false,
    val language: String? = null,
    val rating: Double? = null,
    val views: Long? = null,
    val author: String? = null,
    val artist: String? = null,
    val releaseYear: Int? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val genres: List<MangaVerdeGenreRelationDto> = emptyList(),
    val genresList: List<String> = emptyList(),
    val chapters: List<MangaVerdeChapterDto> = emptyList(),
    val latestChapter: Double? = null,
)

@Serializable
class MangaVerdeGenreRelationDto(
    val mangaId: String? = null,
    val genreId: String? = null,
    val genre: MangaVerdeGenreItemDto? = null,
    val name: String? = null,
    val slug: String? = null,
)

@Serializable
class MangaVerdeGenreItemDto(
    val id: String? = null,
    val name: String? = null,
    val slug: String? = null,
    val isNsfw: Boolean? = false,
)

@Serializable
class MangaVerdeGenreDto(
    val id: String? = null,
    val name: String? = null,
    val slug: String? = null,
    val count: Int? = null,
)

@Serializable
class MangaVerdeChapterDto(
    val id: String? = null,
    val mangaId: String? = null,
    val chapterNumber: Double? = null,
    val chapterTitle: String? = null,
    val language: String? = null,
    val releaseDate: String? = null,
    val createdAt: String? = null,
    val views: Long? = null,
)

@Serializable
class MangaVerdeChapterDetailDto(
    val id: String? = null,
    val mangaId: String? = null,
    val chapterNumber: Double? = null,
    val chapterTitle: String? = null,
    val language: String? = null,
    val pages: List<String> = emptyList(),
    val releaseDate: String? = null,
    val createdAt: String? = null,
)

fun MangaVerdeMangaDto.toSManga(): SManga = SManga.create().apply {
    this.url = "/manga/$slug"
    this.title = this@toSManga.title.trim()
    this.thumbnail_url = cleanCoverUrl(coverUrl)
}

fun MangaVerdeMangaDto.toMangaDetails(): SManga = SManga.create().apply {
    this.url = "/manga/$slug"
    this.title = this@toMangaDetails.title.trim()
    this.thumbnail_url = cleanCoverUrl(coverUrl)
    this.author = author?.trim()?.ifEmpty { null }
    this.artist = artist?.trim()?.ifEmpty { null }
    this.description = buildString {
        if (!this@toMangaDetails.description.isNullOrBlank()) {
            append(this@toMangaDetails.description.trim())
        }
        val extraInfo = mutableListOf<String>()
        if (releaseYear != null && releaseYear > 0) {
            extraInfo.add("Ano: $releaseYear")
        }
        if (!type.isNullOrBlank()) {
            extraInfo.add("Formato: ${formatTypeName(type)}")
        }
        if (isNsfw) {
            extraInfo.add("Conteúdo: +18")
        }
        if (isUncensored) {
            extraInfo.add("Censura: Sem censura")
        }
        if (rating != null && rating > 0.0) {
            extraInfo.add("Avaliação: ★ $rating")
        }
        if (!alternativeTitles.isNullOrBlank()) {
            extraInfo.add("Títulos alternativos: $alternativeTitles")
        }
        if (extraInfo.isNotEmpty()) {
            if (isNotEmpty()) append("\n\n")
            append(extraInfo.joinToString(" • "))
        }
    }.ifEmpty { null }

    val genreNames = if (genresList.isNotEmpty()) {
        genresList.map { it.trim() }
    } else {
        genres.mapNotNull { it.genre?.name?.trim() ?: it.name?.trim() }
    }
    this.genre = genreNames.filter { it.isNotEmpty() }.distinct().joinToString(", ").ifEmpty { null }

    this.status = when (this@toMangaDetails.status?.uppercase()) {
        "ONGOING" -> SManga.ONGOING
        "COMPLETED" -> SManga.COMPLETED
        "HIATUS" -> SManga.ON_HIATUS
        "CANCELLED" -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }
}

fun MangaVerdeChapterDto.toSChapter(): SChapter = SChapter.create().apply {
    this.url = "/api/chapters/${id ?: ""}"
    this.name = cleanChapterName(chapterNumber, chapterTitle)
    this.chapter_number = chapterNumber?.toFloat() ?: -1f
    this.date_upload = parseIsoDate(releaseDate ?: createdAt)
}

internal fun cleanChapterName(number: Double?, rawTitle: String?): String {
    val numStr = if (number != null && number >= 0.0) {
        formatNumberString(number)
    } else {
        null
    }

    var cleanTitle = rawTitle?.replace(Regex("""\s+"""), " ")?.trim()?.ifEmpty { null }
    if (cleanTitle != null) {
        cleanTitle = cleanTitle.replace(Regex("""\b(?:NOVO|NOVA|HOT)\b""", RegexOption.IGNORE_CASE), "").trim()
    }

    if (cleanTitle != null) {
        val lower = cleanTitle.lowercase(Locale.ROOT)
        if (lower == "prólogo" || lower == "prologo" ||
            lower == "especial" || lower == "one-shot" ||
            lower == "oneshot" || lower == "extra"
        ) {
            return cleanTitle
        }
    }

    if (numStr != null && numStr.isNotEmpty()) {
        val redundantPrefixRegex = Regex(
            """^(?:Cap[ií]tulo|Cap\.?)\s*""" + Regex.escape(numStr) + """\s*[:\-\s]*""",
            RegexOption.IGNORE_CASE,
        )
        val extra = cleanTitle?.replace(redundantPrefixRegex, "")
            ?.trim()
            ?.trim('-', ':', ' ')

        val redundantSecond = Regex(
            """^(?:Cap[ií]tulo|Cap\.?)\s*""" + Regex.escape(numStr) + """\s*$""",
            RegexOption.IGNORE_CASE,
        )

        return if (!extra.isNullOrEmpty() &&
            !extra.equals(numStr, ignoreCase = true) &&
            !extra.matches(redundantSecond)
        ) {
            "Cap. $numStr - $extra"
        } else {
            "Cap. $numStr"
        }
    }

    return cleanTitle ?: "Capítulo"
}

internal fun formatNumberString(number: Double): String = if (number % 1.0 == 0.0) {
    number.toLong().toString()
} else {
    number.toString()
}

internal fun formatTypeName(type: String?): String = when (type?.uppercase()) {
    "MANGA" -> "Mangá"
    "MANHWA" -> "Manhwa"
    "MANHUA" -> "Manhua"
    "COMIC" -> "Comic / HQ"
    else -> type ?: ""
}

internal fun cleanCoverUrl(rawUrl: String?): String? {
    if (rawUrl.isNullOrBlank()) return null
    var cleaned = rawUrl.trim()
        .replace("&amp;", "&")
        .replace("\\", "")
        .replace(" ", "%20")
    if (cleaned.startsWith("//")) {
        cleaned = "https:$cleaned"
    }
    return cleaned
}

internal fun parseIsoDate(dateStr: String?): Long {
    if (dateStr.isNullOrBlank()) return 0L
    return try {
        val datePart = dateStr.substringBefore(".")
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        sdf.parse(datePart)?.time ?: 0L
    } catch (_: Exception) {
        0L
    }
}
