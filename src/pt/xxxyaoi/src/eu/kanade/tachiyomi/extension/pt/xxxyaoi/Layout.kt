package eu.kanade.tachiyomi.extension.pt.xxxyaoi

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal object Layout {
    class Card(val title: String, val path: String, val image: Element?, val id: String?)
    class Chapter(val name: String, val path: String, val date: String?)

    private val chapterName = Regex("(?i)^(?:cap[ií]tulo|cap\\.?|chapter|ch\\.?|epis[oó]dio|extra|pr[oó]logo|especial|side\\s*story)\\s*[:#.-]?\\s*\\d*")
    private val dateText = Regex("\\d{2}/\\d{2}/\\d{4}")
    private val dateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val postId = Regex("(?:^|\\s)post-(\\d+)(?:$|\\s)")

    fun cards(document: Document): List<Card> = document.select("h2 a[href], h3 a[href], h4 a[href], .post-title a[href], a[rel=bookmark][href]").mapNotNull { link ->
        val url = link.absUrl("href").toHttpUrlOrNull() ?: return@mapNotNull null
        val parts = url.pathSegments.filter(String::isNotEmpty)
        if (url.host != document.location().toHttpUrlOrNull()?.host || parts.size != 2 || parts.firstOrNull() != "bl") return@mapNotNull null
        val title = link.text().ifBlank { link.attr("title") }.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val container = link.parents().take(5).firstOrNull {
            it.hasAttr("data-post-id") || it.selectFirst("img") != null
        } ?: return@mapNotNull null
        if (container.tagName() in listOf("body", "html", "main")) return@mapNotNull null
        val id = (listOf(container) + container.select("[data-post-id], [id^=post-]")).firstNotNullOfOrNull {
            it.attr("data-post-id").takeIf { id -> id.isNotEmpty() && id.all(Char::isDigit) }
                ?: postId.find(it.id())?.groupValues?.get(1)
        }
        Card(title, url.encodedPath, container.selectFirst("img"), id)
    }.distinctBy(Card::path)

    fun chapters(document: Document, mangaPath: String): List<Chapter> {
        val base = document.location().toHttpUrlOrNull() ?: return emptyList()
        val root = mangaPath.trimEnd('/') + "/"
        return document.select("a[href]").mapNotNull { link ->
            val href = link.attr("href").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val url = base.resolve(root)?.resolve(href) ?: return@mapNotNull null
            if (url.host != base.host || !url.encodedPath.startsWith(root)) return@mapNotNull null
            val tail = url.encodedPath.removePrefix(root).trim('/')
            if (tail.isBlank() || tail.contains('/') || tail in listOf("feed", "ajax", "page")) return@mapNotNull null
            val row = link.parents().take(4).firstOrNull {
                it.tagName() == "li" || it.className().contains("chapter", true)
            } ?: link.parent() ?: link
            val name = (link.selectFirst(".xyaoi-chapter-name") ?: row.selectFirst(".xyaoi-chapter-name"))?.text()
                ?: link.select("span, strong, div").firstOrNull { chapterName.containsMatchIn(it.ownText()) }?.ownText()
                ?: link.text().ifBlank { link.attr("title") }
            if (name.isBlank() || (!chapterName.containsMatchIn(name) && !row.hasClass("wp-manga-chapter"))) return@mapNotNull null
            val date = row.selectFirst("time[datetime]")?.attr("datetime")
                ?: row.selectFirst(".xyaoi-chapter-date-line, .chapter-release-date, time")?.text()
                ?: row.selectFirst("[data-date]")?.attr("data-date")
                ?: row.select("span, time").firstOrNull { dateText.matches(it.ownText()) }?.ownText()
            val path = url.newBuilder().removeAllQueryParameters("style").fragment(null).build().let {
                it.encodedPath + (it.encodedQuery?.let { query -> "?$query" } ?: "")
            }
            Chapter(name, path, date)
        }.distinctBy(Chapter::path)
    }

    fun chapterUrl(baseUrl: String, chapter: String, mangaPath: String?, chapterPath: String?): String {
        val path = chapterPath ?: chapter.takeIf { it.contains('/') }
            ?: mangaPath?.let { it.trimEnd('/') + "/" + chapter + "/" }
            ?: error("XXX Yaoi: atualize a lista de capítulos (chapter-path-missing).")
        val original = path.toHttpUrlOrNull()
        val relative = original?.let { it.encodedPath + (it.encodedQuery?.let { query -> "?$query" } ?: "") } ?: path
        return baseUrl.toHttpUrlOrNull()?.resolve(relative)?.newBuilder()?.removeAllQueryParameters("style")?.fragment(null)?.build()?.toString()
            ?: error("XXX Yaoi: endereço de capítulo inválido.")
    }

    fun absoluteDate(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDate.parse(value.take(10)).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
            ?: dateText.find(value)?.value?.let {
                runCatching { LocalDate.parse(it, dateFormat).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
            }
    }

    fun property(document: Document, label: String): Element? {
        val exact = Regex("(?i)^${Regex.escape(label)}(?:\\(ES\\)|\\(S\\))?\\s*:?$")
        val heading = document.select("span, dt, th, strong, label").firstOrNull { exact.matches(it.ownText()) } ?: return null
        return heading.nextElementSibling() ?: heading.parent()?.nextElementSibling()
    }
}
