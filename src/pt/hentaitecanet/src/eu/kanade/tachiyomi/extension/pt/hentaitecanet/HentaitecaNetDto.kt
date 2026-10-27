package eu.kanade.tachiyomi.extension.pt.hentaitecanet

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

fun parseDate(dateStr: String?): Long {
    if (dateStr.isNullOrBlank()) return 0L
    val clean = dateStr.trim().lowercase(Locale.ROOT)

    if (clean.contains("ago") || clean.contains("atrás") || clean.contains("atras")) {
        val match = Regex("""(\d+)\s*(segundo|minuto|min|hora|dia|semana|mês|mes|ano)""").find(clean)
        if (match != null) {
            val amount = match.groupValues[1].toIntOrNull() ?: return 0L
            val unit = match.groupValues[2]
            val cal = Calendar.getInstance()
            when {
                unit.startsWith("seg") -> cal.add(Calendar.SECOND, -amount)
                unit.startsWith("min") -> cal.add(Calendar.MINUTE, -amount)
                unit.startsWith("hor") -> cal.add(Calendar.HOUR_OF_DAY, -amount)
                unit.startsWith("dia") -> cal.add(Calendar.DAY_OF_YEAR, -amount)
                unit.startsWith("sem") -> cal.add(Calendar.WEEK_OF_YEAR, -amount)
                unit.startsWith("mes") || unit.startsWith("mês") -> cal.add(Calendar.MONTH, -amount)
                unit.startsWith("ano") -> cal.add(Calendar.YEAR, -amount)
            }
            return cal.timeInMillis
        }
    }

    val matchDate = Regex("""(\d{1,2})/(\d{1,2})/(\d{4})""").find(clean)
    if (matchDate != null) {
        return runCatching {
            val format = SimpleDateFormat("dd/MM/yyyy", Locale.ROOT)
            format.parse(matchDate.value)?.time ?: 0L
        }.getOrDefault(0L)
    }

    val matchIsoDate = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})""").find(clean)
    if (matchIsoDate != null) {
        return runCatching {
            val format = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
            format.parse(matchIsoDate.value)?.time ?: 0L
        }.getOrDefault(0L)
    }

    return 0L
}

fun extractChapterNumber(name: String, url: String): Float {
    val fromUrl = Regex("""capitulo-([0-9]+(?:[.-][0-9]+)?)""", RegexOption.IGNORE_CASE).find(url)
    if (fromUrl != null) {
        val numStr = fromUrl.groupValues[1].replace('-', '.')
        val parsed = numStr.toFloatOrNull()
        if (parsed != null) return parsed
    }

    val fromName = Regex("""(?:Capítulo|Cap\.|Chapter|Ch\.)?\s*([0-9]+(?:[.,][0-9]+)?)""", RegexOption.IGNORE_CASE).find(name)
    if (fromName != null) {
        val numStr = fromName.groupValues[1].replace(',', '.')
        val parsed = numStr.toFloatOrNull()
        if (parsed != null) return parsed
    }

    return -1f
}

fun cleanThumbnail(url: String?): String? {
    if (url.isNullOrBlank()) return null
    return url.trim().replace(Regex("""-\d+x\d+(?=\.[a-zA-Z0-9]+$)"""), "")
}
