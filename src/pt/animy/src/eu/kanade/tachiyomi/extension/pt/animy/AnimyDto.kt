package eu.kanade.tachiyomi.extension.pt.animy

import kotlinx.serialization.Serializable
import java.text.Normalizer
import java.util.Locale

@Serializable
class AnimyMangaListDto(
    val items: List<AnimyMangaItemDto> = emptyList(),
    val total: Int = 0,
)

@Serializable
class AnimyMangaItemDto(
    val id: Int,
    val t: String,
    val cover: String? = null,
    val desc: String? = null,
    val status: String? = null,
    val au: String? = null,
    val y: Int? = null,
    val g: List<String> = emptyList(),
    val type: String? = null,
    val ch: Int? = null,
    val demographic: String? = null,
    val contentRating: String? = null,
    val language: String? = null,
)

@Serializable
class AnimyMangaDetailDto(
    val id: Int,
    val t: String,
    val cover: String? = null,
    val desc: String? = null,
    val status: String? = null,
    val au: String? = null,
    val y: Int? = null,
    val g: List<String> = emptyList(),
    val type: String? = null,
    val ch: Int? = null,
    val demographic: String? = null,
    val contentRating: String? = null,
    val language: String? = null,
    val chapters: List<AnimyChapterDto> = emptyList(),
)

@Serializable
class AnimyChapterDto(
    val id: Int,
    val number: Float,
    val date: String? = null,
    val language: String? = null,
    val title: String? = null,
    val views: Int? = null,
)

@Serializable
class AnimyRecentChapterDto(
    val mangaId: Int,
    val mangaTitle: String,
    val mangaCover: String? = null,
    val number: Float? = null,
    val date: String? = null,
    val language: String? = null,
)

@Serializable
class AnimyChapterPagesDto(
    val id: Int,
    val number: Float,
    val pages: List<AnimyPageDto>? = emptyList(),
)

@Serializable
class AnimyPageDto(
    val number: Int,
    val imageUrl: String,
)

fun AnimyMangaItemDto.isNovel(): Boolean {
    val tLower = type?.lowercase(Locale.ROOT).orEmpty()
    val titleLower = t.lowercase(Locale.ROOT).trim()
    return tLower == "novel" ||
        titleLower.endsWith(" novel") ||
        titleLower.endsWith("(novel)") ||
        titleLower.endsWith("[novel]") ||
        g.any { it.equals("Novel", ignoreCase = true) }
}

fun AnimyMangaDetailDto.isNovel(): Boolean {
    val tLower = type?.lowercase(Locale.ROOT).orEmpty()
    val titleLower = t.lowercase(Locale.ROOT).trim()
    return tLower == "novel" ||
        titleLower.endsWith(" novel") ||
        titleLower.endsWith("(novel)") ||
        titleLower.endsWith("[novel]") ||
        g.any { it.equals("Novel", ignoreCase = true) }
}

fun AnimyRecentChapterDto.isNovel(): Boolean {
    val titleLower = mangaTitle.lowercase(Locale.ROOT).trim()
    return titleLower.endsWith(" novel") ||
        titleLower.endsWith("(novel)") ||
        titleLower.endsWith("[novel]") ||
        titleLower.contains(" novel ") ||
        titleLower.contains("-novel") ||
        Regex("\\bnovel\\b", RegexOption.IGNORE_CASE).containsMatchIn(mangaTitle)
}

fun slugify(title: String): String {
    val normalized = Normalizer.normalize(title, Normalizer.Form.NFD)
    return normalized
        .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
        .lowercase(Locale.ROOT)
        .replace("[^a-z0-9]+".toRegex(), "-")
        .trim('-')
}

fun Float.formatChapterNumber(): String = if (this % 1.0f == 0f) toInt().toString() else toString()

fun formatChapterName(number: Float, title: String?): String {
    val formattedNum = number.formatChapterNumber()
    val baseName = if (number == 0f) "Prólogo" else "Capítulo $formattedNum"
    val cleanTitle = title?.trim().orEmpty()

    if (cleanTitle.isEmpty()) return baseName

    val isRedundant = cleanTitle.equals(baseName, ignoreCase = true) ||
        cleanTitle.equals("Capítulo", ignoreCase = true) ||
        cleanTitle.equals("Cap. $formattedNum", ignoreCase = true) ||
        cleanTitle.equals("#$formattedNum", ignoreCase = true) ||
        cleanTitle.equals(formattedNum, ignoreCase = true)

    if (isRedundant) return baseName

    val strippedTitle = when {
        cleanTitle.startsWith("Capítulo $formattedNum - ", ignoreCase = true) ->
            cleanTitle.substring("Capítulo $formattedNum - ".length).trim()
        cleanTitle.startsWith("Capítulo $formattedNum: ", ignoreCase = true) ->
            cleanTitle.substring("Capítulo $formattedNum: ".length).trim()
        cleanTitle.startsWith("Capítulo $formattedNum ", ignoreCase = true) ->
            cleanTitle.substring("Capítulo $formattedNum ".length).trim().removePrefix("-").removePrefix(":").trim()
        cleanTitle.startsWith("Cap. $formattedNum - ", ignoreCase = true) ->
            cleanTitle.substring("Cap. $formattedNum - ".length).trim()
        cleanTitle.startsWith("Cap. $formattedNum: ", ignoreCase = true) ->
            cleanTitle.substring("Cap. $formattedNum: ".length).trim()
        cleanTitle.startsWith("Cap. $formattedNum ", ignoreCase = true) ->
            cleanTitle.substring("Cap. $formattedNum ".length).trim().removePrefix("-").removePrefix(":").trim()
        else -> cleanTitle
    }

    return if (strippedTitle.isNotBlank()) {
        "$baseName - $strippedTitle"
    } else {
        baseName
    }
}
