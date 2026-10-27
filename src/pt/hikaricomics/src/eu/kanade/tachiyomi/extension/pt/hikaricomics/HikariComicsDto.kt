package eu.kanade.tachiyomi.extension.pt.hikaricomics

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

private val ISO_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}

private val NUMBER_REGEX = Regex("""\d+""")
private val CHAPTER_NUMBER_REGEX = Regex("""(?:capitulo-|cap[íi]tulo\s*)(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)

fun parseIsoDate(dateStr: String?): Long {
    if (dateStr.isNullOrBlank()) return 0L
    return runCatching {
        val clean = dateStr.substringBefore(".")
        ISO_DATE_FORMAT.parse(clean)?.time ?: 0L
    }.getOrDefault(0L)
}

fun parseRelativeDate(dateStr: String?): Long {
    if (dateStr.isNullOrBlank()) return 0L
    val clean = dateStr.trim().lowercase(Locale.ROOT)
    val number = NUMBER_REGEX.find(clean)?.value?.toLongOrNull() ?: 1L
    val cal = Calendar.getInstance()

    when {
        clean.contains("segundo") -> cal.add(Calendar.SECOND, -number.toInt())
        clean.contains("minuto") -> cal.add(Calendar.MINUTE, -number.toInt())
        clean.contains("hora") -> cal.add(Calendar.HOUR_OF_DAY, -number.toInt())
        clean.contains("dia") -> cal.add(Calendar.DAY_OF_YEAR, -number.toInt())
        clean.contains("semana") -> cal.add(Calendar.WEEK_OF_YEAR, -number.toInt())
        clean.contains("mês") || clean.contains("mes") -> cal.add(Calendar.MONTH, -number.toInt())
        clean.contains("ano") -> cal.add(Calendar.YEAR, -number.toInt())
        else -> return 0L
    }

    return cal.timeInMillis
}

fun extractChapterNumber(input: String): Float {
    val match = CHAPTER_NUMBER_REGEX.find(input)
    if (match != null) {
        return match.groupValues[1].toFloatOrNull() ?: 0f
    }
    val fallback = NUMBER_REGEX.find(input)
    return fallback?.value?.toFloatOrNull() ?: 0f
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

    val stripped = when {
        cleanTitle.startsWith("Capítulo $formattedNum - ", ignoreCase = true) ->
            cleanTitle.substring("Capítulo $formattedNum - ".length).trim()
        cleanTitle.startsWith("Capítulo $formattedNum: ", ignoreCase = true) ->
            cleanTitle.substring("Capítulo $formattedNum: ".length).trim()
        cleanTitle.startsWith("Capítulo $formattedNum ", ignoreCase = true) ->
            cleanTitle.substring("Capítulo $formattedNum ".length).trim().removePrefix("-").removePrefix(":").trim()
        cleanTitle.startsWith("Cap. $formattedNum - ", ignoreCase = true) ->
            cleanTitle.substring("Cap. $formattedNum - ".length).trim()
        cleanTitle.startsWith("Cap. $formattedNum ", ignoreCase = true) ->
            cleanTitle.substring("Cap. $formattedNum ".length).trim().removePrefix("-").removePrefix(":").trim()
        else -> cleanTitle
    }

    return if (stripped.isNotBlank()) "$baseName - $stripped" else baseName
}
