package eu.kanade.tachiyomi.extension.pt.jondomingues

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
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.io.IOException

@Source
abstract class JonDomingues : KeiSource() {

    // ============================== Popular ==============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$baseUrl/wp-json/wp/v2/posts".toHttpUrl().newBuilder().apply {
            addQueryParameter("orderby", "title")
            addQueryParameter("order", "asc")
            addQueryParameter("page", page.toString())
            addQueryParameter("per_page", PAGE_SIZE.toString())
            addQueryParameter("_embed", "true")
        }.build()

        val response = client.get(url)
        return parsePostsResponse(response)
    }

    // ============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$baseUrl/wp-json/wp/v2/posts".toHttpUrl().newBuilder().apply {
            addQueryParameter("orderby", "date")
            addQueryParameter("order", "desc")
            addQueryParameter("page", page.toString())
            addQueryParameter("per_page", PAGE_SIZE.toString())
            addQueryParameter("_embed", "true")
        }.build()

        val response = client.get(url)
        return parsePostsResponse(response)
    }

    // ============================== Search ===============================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val sortFilter = filters.firstInstanceOrNull<SortFilter>()
        val sortParams = sortFilter?.toParams() ?: Pair("date", "desc")
        val categoryFilter = filters.firstInstanceOrNull<CategoryFilter>()
        val categoryId = categoryFilter?.selectedId.orEmpty()

        val url = "$baseUrl/wp-json/wp/v2/posts".toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) {
                addQueryParameter("search", query)
            }
            if (categoryId.isNotBlank()) {
                addQueryParameter("categories", categoryId)
            }
            addQueryParameter("orderby", sortParams.first)
            addQueryParameter("order", sortParams.second)
            addQueryParameter("page", page.toString())
            addQueryParameter("per_page", PAGE_SIZE.toString())
            addQueryParameter("_embed", "true")
        }.build()

        val response = client.get(url)
        return parsePostsResponse(response)
    }

    private fun parsePostsResponse(response: Response): MangasPage {
        val totalPages = response.header("X-WP-TotalPages")?.toIntOrNull() ?: 1
        val currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1

        val posts = response.parseAs<List<WpPostDto>>()
        val mangas = posts.map { post ->
            SManga.create().apply {
                val cleanTitle = Parser.unescapeEntities(post.title.rendered, false)
                    .replace(Regex("""\s*–\s*Ler Online e Download.*"""), "")
                    .replace(Regex("""\s*-\s*Jon Domingues.*"""), "")
                    .trim()

                title = cleanTitle
                setUrlWithoutDomain(post.link)
                thumbnail_url = post.embedded?.featuredMedia?.firstOrNull()?.sourceUrl
                status = SManga.COMPLETED
            }
        }

        val hasNext = currentPage < totalPages && mangas.isNotEmpty()
        return MangasPage(mangas, hasNext)
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
        val rawTitle = document.selectFirst("h1.entry-title, .entry-title")?.text()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")
            ?: ""

        title = Parser.unescapeEntities(rawTitle, false)
            .replace(Regex("""\s*–\s*Ler Online e Download.*"""), "")
            .replace(Regex("""\s*-\s*Jon Domingues.*"""), "")
            .trim()

        thumbnail_url = document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: document.selectFirst(".entry-content img")?.absUrl("src")

        val descParagraphs = document.select(".entry-content p").map { it.text().trim() }
            .filter { it.isNotEmpty() && !it.contains("Baixar HQ", ignoreCase = true) && !it.contains("Ler Online", ignoreCase = true) }

        description = descParagraphs.joinToString("\n\n").takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property='og:description']")?.attr("content")

        val categories = document.select("a[rel='category tag'], .cat-links a").map { it.text().trim() }
        val tags = document.select("a[rel='tag'], .tags-links a").map { it.text().trim() }

        val allGenres = (categories + tags).distinct()
        genre = allGenres.joinToString(", ")

        // Extract author/artist from known creators in tags if present
        val creatorTags = tags.filter { tag ->
            KNOWN_AUTHORS.any { author -> tag.contains(author, ignoreCase = true) }
        }
        author = creatorTags.joinToString(", ").takeIf { it.isNotBlank() }

        status = SManga.COMPLETED
    }

    private fun parseChapterList(document: Document, mangaUrl: String): List<SChapter> {
        val links = document.select("a[href]")
        var lerOnlineLink: String? = null

        // 1. Priority 1: <a> containing "ler online" in text (and not tag/category/author)
        for (link in links) {
            val href = link.attr("href").trim()
            val text = link.text().trim()
            if (text.contains("ler online", ignoreCase = true) &&
                !href.contains("/tag/") &&
                !href.contains("/category/") &&
                !href.contains("/author/")
            ) {
                val cleanHref = link.absUrl("href").substringAfter(baseUrl).removeSuffix("/")
                val cleanManga = mangaUrl.removeSuffix("/")
                if (cleanHref != cleanManga) {
                    lerOnlineLink = link.absUrl("href")
                    break
                }
            }
        }

        // 2. Priority 2: href containing /ler- or /ler_ or /ler/ (and not tag/category/download)
        if (lerOnlineLink == null) {
            for (link in links) {
                val href = link.attr("href").trim()
                if ((href.contains("/ler-") || href.contains("/ler_") || href.contains("/ler/")) &&
                    !href.contains("/tag/") &&
                    !href.contains("/category/") &&
                    !href.contains("/author/") &&
                    !href.contains("workupload") &&
                    !href.contains("download")
                ) {
                    lerOnlineLink = link.absUrl("href")
                    break
                }
            }
        }

        if (lerOnlineLink == null) {
            return emptyList()
        }

        val chapterUrl = if (lerOnlineLink.startsWith("http")) {
            val path = lerOnlineLink.substringAfter(baseUrl)
            if (path.startsWith("/")) path else "/$path"
        } else {
            if (lerOnlineLink.startsWith("/")) lerOnlineLink else "/$lerOnlineLink"
        }

        val chapterName = document.selectFirst("h1.entry-title, .entry-title")?.text()
            ?.let { Parser.unescapeEntities(it, false) }
            ?.replace(Regex("""\s*–\s*Ler Online e Download.*"""), "")
            ?.replace(Regex("""\s*-\s*Jon Domingues.*"""), "")
            ?.trim()
            ?: "Edição Única"

        val chapterNum = Regex("""#\s*(\d+(?:\.\d+)?)""").find(chapterName)?.groupValues?.get(1)?.toFloatOrNull() ?: 1f

        return listOf(
            SChapter.create().apply {
                url = chapterUrl
                name = chapterName
                chapter_number = chapterNum
            },
        )
    }

    // =============================== Pages ===============================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val pageUrl = "$baseUrl${if (chapter.url.startsWith("/")) chapter.url else "/${chapter.url}"}"
        val document = client.get(pageUrl).asJsoup()

        val imgElements = document.select("img")
        val imageList = imgElements.mapNotNull { element ->
            val src = element.attr("data-lazy-src").takeIf { it.isNotBlank() }
                ?: element.attr("data-src").takeIf { it.isNotBlank() }
                ?: element.attr("src").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            val absSrc = if (src.startsWith("//")) {
                "https:$src"
            } else if (src.startsWith("/")) {
                "$baseUrl$src"
            } else {
                src
            }

            if (!absSrc.contains("wp-content/uploads", ignoreCase = true)) {
                return@mapNotNull null
            }

            if (IGNORE_IMG_KEYWORDS.any { absSrc.contains(it, ignoreCase = true) }) {
                return@mapNotNull null
            }

            absSrc
        }.distinct()

        if (imageList.isEmpty()) {
            throw IOException("Nenhuma página de leitura encontrada para esta edição")
        }

        return imageList.mapIndexed { index, imgUrl ->
            Page(index, imageUrl = imgUrl)
        }
    }

    // ============================== Filters ==============================

    override fun getFilterList(data: kotlinx.serialization.json.JsonElement?): FilterList = FilterList(
        SortFilter(),
        CategoryFilter(CategoryFilter.CATEGORIES),
    )

    companion object {
        private const val PAGE_SIZE = 24

        private val IGNORE_IMG_KEYWORDS = listOf(
            "removebg",
            "cropped",
            "logo",
            "icon",
            "banner",
            "gravatar",
            "avatar",
            "design_sem_nome",
        )

        private val KNOWN_AUTHORS = listOf(
            "Garth Ennis",
            "Darick Robertson",
            "Scott Snyder",
            "Nick Dragotta",
            "Jason Aaron",
            "Jesús Saiz",
            "Frank Miller",
            "Alan Moore",
            "Grant Morrison",
            "Stan Lee",
            "Jack Kirby",
            "Jim Lee",
            "Geoff Johns",
        )
    }
}
