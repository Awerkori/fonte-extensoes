package eu.kanade.tachiyomi.extension.pt.gomangas

import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

@Serializable
class GoMangasChapterDto(
    val id: Long = 0L,
    val slug: String,
    val number: Double? = null,
    val numberLabel: String? = null,
    val title: String? = null,
    val sourcePublishedAt: String? = null,
    val createdAt: String? = null,
)

@Serializable
class GoMangasPageDto(
    val imageUrl: String,
)

@Serializable
class BookSchemaDto(
    val name: String? = null,
    val description: String? = null,
    val author: AuthorSchemaDto? = null,
    val genre: List<String> = emptyList(),
)

@Serializable
class AuthorSchemaDto(
    val name: String? = null,
)

fun GoMangasChapterDto.toSChapter(): SChapter = SChapter.create().apply {
    this.name = formatChapterName(numberLabel, number, title)
    this.url = "/ler/$slug"
    this.chapter_number = numberLabel?.toFloatOrNull() ?: number?.toFloat() ?: -1f
    this.date_upload = parseIsoDate(sourcePublishedAt ?: createdAt)
}

internal fun formatChapterName(numberLabel: String?, number: Double?, rawTitle: String?): String {
    var numStr = numberLabel?.trim()?.ifEmpty { null }
    if (numStr == null && number != null && number >= 0.0) {
        numStr = formatNumberString(number)
    }

    val cleanTitle = rawTitle?.trim()?.ifEmpty { null }

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

        return if (!extra.isNullOrEmpty() && !extra.equals(numStr, ignoreCase = true)) {
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

internal fun formatNumberString(number: Float): String = if (number % 1.0f == 0.0f) {
    number.toLong().toString()
} else {
    number.toString()
}

internal fun parsePortugueseDate(dateStr: String?): Long {
    if (dateStr.isNullOrBlank()) return 0L
    val match = Regex("""(\d{1,2})\s+de\s+([a-zA-Zç]+)\.?\s+de\s+(\d{4})""", RegexOption.IGNORE_CASE).find(dateStr)
        ?: return 0L
    val day = match.groupValues[1].toIntOrNull() ?: return 0L
    val monthStr = match.groupValues[2].lowercase(Locale.ROOT).take(3)
    val year = match.groupValues[3].toIntOrNull() ?: return 0L

    val month = when (monthStr) {
        "jan" -> 0
        "fev" -> 1
        "mar" -> 2
        "abr" -> 3
        "mai" -> 4
        "jun" -> 5
        "jul" -> 6
        "ago" -> 7
        "set" -> 8
        "out" -> 9
        "nov" -> 10
        "dez" -> 11
        else -> 0
    }

    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(year, month, day, 12, 0, 0)
    }
    return cal.timeInMillis
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
