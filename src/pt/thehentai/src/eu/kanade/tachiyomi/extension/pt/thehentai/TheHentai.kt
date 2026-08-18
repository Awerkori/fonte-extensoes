package eu.kanade.tachiyomi.extension.pt.thehentai

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser

@Source
abstract class TheHentai : KeiSource() {

    // ============================== Popular ==============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = if (page == 1) "$baseUrl/" else "$baseUrl/page/$page/"
        val response = client.get(url)
        return parseMangaList(response.asJsoup())
    }

    // ============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)

    // ============================== Search ===============================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/".toHttpUrl().newBuilder().apply {
            addQueryParameter("s", query)
            if (page > 1) {
                addEncodedPathSegments("page/$page/")
            }
        }.build()

        val response = client.get(url)
        return parseMangaList(response.asJsoup())
    }

    // ============================== Details ==============================

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val path = url.encodedPath
        if (path.isBlank() || path == "/") return null

        val document = client.get("$baseUrl$path").asJsoup()
        return parseMangaDetails(document).apply {
            setUrlWithoutDomain(path)
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        val updatedManga = if (fetchDetails) parseMangaDetails(document) else manga
        val updatedChapters = if (fetchChapters) parseChapterList(document, manga.url) else chapters
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
        val ogTitle = document.selectFirst("meta[property=og:title]")?.attr("content")
        val rawTitle = document.selectFirst(".post_title span, h1")?.text() ?: ogTitle ?: ""
        title = Parser.unescapeEntities(rawTitle.replace(Regex("""\s*-\s*Português\s*-\s*Hentai.*"""), ""), false).trim()

        thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")?.let {
            if (it.startsWith("/")) "$baseUrl$it" else it
        } ?: document.selectFirst(".post_imgs img, #img_cover")?.absUrl("src")

        description = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?: document.selectFirst(".description")?.text()

        val categories = document.select(".postInfo a[href*='/category/'], .postInfo a[href*='/tag/']")
            .map { it.text().removePrefix("#").trim() }
            .filter { it.isNotBlank() }
            .distinct()

        genre = categories.joinToString(", ")
        status = SManga.COMPLETED
    }

    // ============================== Chapters ==============================

    private fun parseChapterList(document: Document, mangaUrl: String): List<SChapter> = listOf(
        SChapter.create().apply {
            name = "Capítulo Completo"
            setUrlWithoutDomain(mangaUrl)
            chapter_number = 1f
        },
    )

    // ============================== Pages ==============================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = "$baseUrl${if (chapter.url.startsWith("/")) chapter.url else "/${chapter.url}"}"
        val document = client.get(chapterUrl).asJsoup()
        val pages = mutableListOf<Page>()

        val imgElements = document.select(".post_imgs img, #img_gallery_big")
        for ((index, element) in imgElements.withIndex()) {
            val src = element.attr("data-src").ifBlank { element.attr("data-lazy-src") }.ifBlank { element.attr("src") }
            if (src.isBlank()) continue
            val fullUrl = if (src.startsWith("/")) "$baseUrl$src" else src
            pages.add(Page(index, imageUrl = fullUrl))
        }

        return pages
    }

    // ============================== Filters ==============================

    override fun getFilterList(data: JsonElement?): FilterList = FilterList()

    // ============================== Helpers ==============================

    private fun parseMangaList(document: Document): MangasPage {
        val mangas = mutableListOf<SManga>()
        val seenUrls = mutableSetOf<String>()

        val elements = document.select(".gridPosts h3 a[href]")
        for (element in elements) {
            val href = element.attr("href").trim()
            if (href.startsWith("http://") || href.startsWith("https://track") || href.contains("candy.ai")) continue
            if (seenUrls.contains(href)) continue
            seenUrls.add(href)

            val titleText = element.text().trim()
            if (titleText.isBlank()) continue

            val imgCage = element.parent()?.previousElementSibling()?.takeIf { it.hasClass("img_cage") }
                ?: element.closest("div")?.previousElementSibling()
            val coverUrl = imgCage?.selectFirst("img")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("data-lazy-src") }.ifBlank { img.attr("src") }
            }?.let { if (it.startsWith("/")) "$baseUrl$it" else it }

            mangas.add(
                SManga.create().apply {
                    title = Parser.unescapeEntities(titleText, false)
                    setUrlWithoutDomain(href)
                    thumbnail_url = coverUrl
                },
            )
        }

        val hasNextPage = document.selectFirst(".pagination a[href]:contains(>)") != null ||
            document.select(".pagination a[href]").any { it.text().contains(">") || it.text().contains("Próxima") }

        return MangasPage(mangas, hasNextPage)
    }
}
