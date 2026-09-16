package eu.kanade.tachiyomi.extension.pt.corujatoon

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.HttpUrl
import org.jsoup.nodes.Document

internal data class CorujaParsedPage(
    val details: SManga,
    val chapters: List<SChapter>,
)

internal fun parseSeriesPage(document: Document, source: SManga): CorujaParsedPage {
    val title = document.selectFirst("h1")?.text()?.trim().takeIf { !it.isNullOrEmpty() } ?: source.title
    val details = SManga.create().apply {
        url = source.url
        this.title = title
        thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf(String::isNotBlank)
            ?: source.thumbnail_url
        description = source.description
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.takeIf(String::isNotBlank)
        author = source.author
        artist = source.artist
        genre = source.genre
        status = document.select("span").asSequence()
            .map { it.text().trim() }
            .firstNotNullOfOrNull(::parseStatus)
            ?: source.status
        initialized = true
    }

    val slug = source.url.substringBefore('|')
    val seriesId = source.url.substringAfter('|', "")
    val scriptData = document.select("script").joinToString("\n") { it.data() }
    val chapterDtos = parseChapterPayload(scriptData).ifEmpty {
        parseChapterLinks(document)
    }
    val chapters = chapterDtos.map { it.toSChapter(slug, seriesId) }
        .distinctBy { it.url }
        .sortedByDescending { it.chapter_number }

    return CorujaParsedPage(details, chapters)
}

internal fun parseChapterPayload(payload: String): List<ChapterDto> = CHAPTER_START.findAll(payload).mapNotNull { match ->
    val tail = payload.substring(match.range.last + 1).substringBefore('}')
    val title = CHAPTER_TITLE.find(tail)?.groupValues?.get(1)
    val publishedAt = CHAPTER_DATE.find(tail)?.groupValues?.get(1)
    ChapterDto(
        id = match.groupValues[1],
        number = match.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null,
        title = title,
        publishedAt = publishedAt,
    )
}.toList()
    .distinctBy { it.id }
    .sortedByDescending { it.number }

internal fun parseChapterLinks(document: Document): List<ChapterDto> = document
    .select("a[href*='/series/'][href*='/capitulo/']")
    .mapNotNull { link ->
        val href = link.attr("abs:href").ifBlank { link.attr("href") }
        val number = href.substringAfterLast("/capitulo/").substringBefore('?')
        val parsedNumber = number.toDoubleOrNull() ?: return@mapNotNull null
        ChapterDto(
            id = number,
            number = parsedNumber,
            title = link.selectFirst("h3")?.text()?.trim(),
        )
    }
    .distinctBy { it.id }
    .sortedByDescending { it.number }

internal fun parseChapterPageImages(document: Document, baseUrl: HttpUrl): List<String> {
    val candidates = document.select("main img").mapNotNull { image ->
        sequenceOf("data-src", "data-lazy-src", "data-original", "src", "srcset")
            .map { image.attr(it).substringBefore(',').substringBefore(' ').trim() }
            .filter(String::isNotBlank)
            .mapNotNull { source -> runCatching { baseUrl.resolve(source) }.getOrNull() }
            .firstOrNull { url -> !isNoiseImage(url) }
    }

    val groups = candidates.groupBy { it.host to it.encodedPath.substringBeforeLast('/', "") }
    val strongest = groups.maxByOrNull { it.value.size } ?: return emptyList()
    if (strongest.value.size < 2 && !strongest.key.second.contains("/capitulo", ignoreCase = true)) {
        return emptyList()
    }

    return candidates.filter { it.host to it.encodedPath.substringBeforeLast('/', "") == strongest.key }
        .distinct()
        .map(HttpUrl::toString)
}

private fun isNoiseImage(url: HttpUrl): Boolean = Regex("(?i)(favicon|logo|mascot|avatar|banner|thumbnail|placeholder|advert|/ads?/)")
    .containsMatchIn(url.encodedPath)

private fun parseStatus(value: String): Int? = when (value.lowercase()) {
    "em andamento", "ongoing" -> SManga.ONGOING
    "completo", "completed", "finished" -> SManga.COMPLETED
    "hiato", "on hiatus" -> SManga.ON_HIATUS
    "cancelado", "cancelled", "canceled" -> SManga.CANCELLED
    else -> null
}

private val CHAPTER_START = Regex(
    """\\?"id\\?"\s*:\s*\\?"([^"\\]+)\\?"\s*,\s*\\?"number\\?"\s*:\s*(-?\d+(?:\.\d+)?)""",
)
private val CHAPTER_TITLE = Regex("""\\?"title\\?"\s*:\s*\\?"([^"\\]*)\\?"""")
private val CHAPTER_DATE = Regex("""\\?"publishedAt\\?"\s*:\s*\\?"([^"\\]+)\\?"""")
