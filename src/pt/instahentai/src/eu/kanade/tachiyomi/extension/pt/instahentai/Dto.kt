package eu.kanade.tachiyomi.extension.pt.instahentai

import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import java.text.SimpleDateFormat
import java.util.Locale

internal fun parseMangas(document: org.jsoup.nodes.Document, baseUrl: String): List<SManga> = document.select("article.card_item").mapNotNull { article ->
    val link = article.selectFirst("a[href*=/serie/]") ?: return@mapNotNull null
    val title = link.attr("aria-label").ifBlank { link.text() }.trim()
    val slug = link.attr("href").substringAfter("/serie/").substringBefore("/")
    if (title.isBlank() || slug.isBlank()) return@mapNotNull null
    SManga.create().apply {
        this.title = title
        url = "/serie/$slug/"
        thumbnail_url = article.selectFirst("img[src*=/uploads/], img[data-src*=/uploads/]")
            ?.let { it.attr("data-src").ifBlank { it.attr("src") } }
            ?.absoluteUrl(baseUrl)
    }
}.distinctBy { it.url }

internal fun String.absoluteUrl(baseUrl: String): String = when {
    startsWith("http") -> this
    startsWith("//") -> "https:$this"
    startsWith("/") -> baseUrl + this
    else -> "$baseUrl/$this"
}

internal fun parseChapterNumber(value: String): Float = Regex("""(?:capitulo|capítulo)[- ]([0-9]+(?:[.,][0-9]+)?)""", RegexOption.IGNORE_CASE)
    .find(value)?.groupValues?.get(1)?.replace(',', '.')?.toFloatOrNull() ?: -1f

internal fun parseDate(value: String): Long = SimpleDateFormat("dd/MM/yyyy", Locale.ROOT).tryParse(value)
