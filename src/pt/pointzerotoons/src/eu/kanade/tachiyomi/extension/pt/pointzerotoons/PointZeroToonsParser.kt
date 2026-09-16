package eu.kanade.tachiyomi.extension.pt.pointzerotoons

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal data class PointZeroCard(
    val title: String,
    val url: String,
    val thumbnail: String,
)

internal data class PointZeroChapter(
    val name: String,
    val url: String,
)

internal fun parsePointZeroCards(document: Document): List<PointZeroCard> = document
    .select(
        "a.inkra-catalog-card__media, a[href*='/manga/']:has(img):has(h3), " +
            ".utao .uta .imgu, .listupd .bs .bsx, .listo .bs .bsx",
    )
    .mapNotNull { element ->
        val link = if (element.tagName() == "a") element else element.selectFirst("a") ?: return@mapNotNull null
        val url = link.attr("abs:href").ifBlank { link.attr("href") }
        if (!url.contains("/manga/")) return@mapNotNull null

        val title = element.selectFirst("h3")?.text()?.trim()
            ?.takeIf(String::isNotBlank)
            ?: link.attr("title").trim().takeIf(String::isNotBlank)
            ?: element.selectFirst("img")?.attr("alt")?.trim()?.takeIf(String::isNotBlank)
            ?: return@mapNotNull null
        val thumbnail = element.selectFirst("img")?.pointZeroImageUrl().orEmpty()

        PointZeroCard(title, url, thumbnail)
    }
    .distinctBy { it.url }

internal fun hasPointZeroNextPage(document: Document): Boolean = document.select(
    "a.next.page-numbers, div.pagination .next, div.hpage .r",
).isNotEmpty()

internal fun parsePointZeroChapters(document: Document): List<PointZeroChapter> = document
    .select(".inkra-chapter-list .inkra-chapter-item")
    .mapNotNull { element ->
        val link = element.selectFirst("a.inkra-chapter-item__link[href], a[href]") ?: return@mapNotNull null
        val url = link.attr("abs:href").ifBlank { link.attr("href") }.trim()
        if (url.isBlank()) return@mapNotNull null

        val name = element.selectFirst(".inkra-chapter-item__label, .inkra-chapter-item__title")
            ?.text()
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: link.text().trim().takeIf(String::isNotBlank)
            ?: return@mapNotNull null

        PointZeroChapter(name, url)
    }
    .distinctBy { it.url }

internal fun pointZeroCatalogPath(page: Int): String = if (page <= 1) "/manga/" else "/manga/page/$page/"

private fun Element.pointZeroImageUrl(): String = sequenceOf(
    "data-lazy-src",
    "data-src",
    "data-cfsrc",
    "src",
    "srcset",
).map { attr ->
    attr(attr).substringBefore(',').substringBefore(' ').trim()
        .let { value -> absUrl(attr).ifBlank { value } }
}.firstOrNull(String::isNotBlank).orEmpty()
