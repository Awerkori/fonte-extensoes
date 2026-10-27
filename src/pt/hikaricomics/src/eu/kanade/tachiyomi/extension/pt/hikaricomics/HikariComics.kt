package eu.kanade.tachiyomi.extension.pt.hikaricomics

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document

private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
private const val PAGE_SIZE = 20

@Source
abstract class HikariComics : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        rateLimit(permits = 2) { it.host == "hikaricomics.xyz" }
    }

    override fun Headers.Builder.configureHeaders(): Headers.Builder = apply {
        set("User-Agent", USER_AGENT)
        set("Referer", "$baseUrl/")
    }

    override fun imageRequest(page: Page): Request {
        val imageHeaders = headersBuilder()
            .set("Referer", "$baseUrl/")
            .build()

        return super.imageRequest(page).newBuilder()
            .headers(imageHeaders)
            .build()
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$baseUrl/comics".toHttpUrl().newBuilder()
            .addQueryParameter("sort", "views")
            .addQueryParameter("page", page.toString())
            .build()

        val doc = client.get(url).asJsoup()
        return parseMangasPage(doc)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$baseUrl/comics".toHttpUrl().newBuilder()
            .addQueryParameter("sort", "updated")
            .addQueryParameter("page", page.toString())
            .build()

        val doc = client.get(url).asJsoup()
        return parseMangasPage(doc)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val urlBuilder = "$baseUrl/comics".toHttpUrl().newBuilder()

        if (query.isNotBlank()) {
            urlBuilder.addQueryParameter("q", query.trim())
        }

        var hasSort = false
        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> {
                    if (filter.selected.isNotEmpty()) {
                        urlBuilder.addQueryParameter("sort", filter.selected)
                        hasSort = true
                    }
                }
                is StatusFilter -> {
                    if (filter.selected.isNotEmpty()) {
                        urlBuilder.addQueryParameter("status", filter.selected)
                    }
                }
                is GenreFilter -> {
                    if (filter.selected.isNotEmpty()) {
                        urlBuilder.addQueryParameter("tag", filter.selected)
                    }
                }
                else -> {}
            }
        }

        if (!hasSort) {
            val defaultSort = if (query.isNotBlank()) "views" else "updated"
            urlBuilder.addQueryParameter("sort", defaultSort)
        }

        urlBuilder.addQueryParameter("page", page.toString())

        val doc = client.get(urlBuilder.build()).asJsoup()
        return parseMangasPage(doc)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val segments = url.pathSegments
        val idx = segments.indexOf("comic")
        if (idx == -1 || idx + 1 >= segments.size) return null
        val slug = segments[idx + 1]

        val manga = SManga.create().apply {
            this.url = "/comic/$slug"
        }

        val update = fetchMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false)
        return update.manga
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val doc = client.get("$baseUrl${manga.url}").asJsoup()

        if (fetchDetails) {
            val titleText = doc.selectFirst("h1")?.text()?.trim()
            if (!titleText.isNullOrEmpty()) {
                manga.title = titleText
            }

            val ogImage = doc.selectFirst("meta[property=og:image]")?.attr("content")
            val coverImg = doc.selectFirst("img[src*=/files/covers/]")?.attr("abs:src")
            val thumbnail = ogImage?.takeIf { it.isNotBlank() } ?: coverImg?.takeIf { it.isNotBlank() }
            if (thumbnail != null) {
                manga.thumbnail_url = thumbnail
            }

            val synopsisText = doc.selectFirst("span:matches(^Sinopse) ~ div p, span:matches(^Sinopse) + div p, p.text-sm.leading-relaxed")?.text()?.trim()
            val metaDesc = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
                ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            val finalDesc = synopsisText?.takeIf { it.isNotBlank() } ?: metaDesc
            if (!finalDesc.isNullOrEmpty()) {
                manga.description = finalDesc
            }

            val author = doc.select("dt:matches(^Autor) + dd a, dt:matches(^Autor) + dd span").text().trim()
            val artist = doc.select("dt:matches(^Artista) + dd a, dt:matches(^Artista) + dd span").text().trim()
            if (author.isNotEmpty()) manga.author = author
            if (artist.isNotEmpty()) manga.artist = artist

            val categories = doc.select("dt:matches(^Categoria) + dd span").eachText()
            val genres = doc.select("dt:matches(^Gêneros) + dd a, dt:matches(^Gêneros) + dd span").eachText()
            val allGenres = (categories + genres).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (allGenres.isNotEmpty()) {
                manga.genre = allGenres.joinToString(", ")
            }

            val statusText = doc.select("dt:matches(^Status) + dd span, span:contains(Status)").text().trim()
            manga.status = when {
                statusText.contains("Completo", ignoreCase = true) -> SManga.COMPLETED
                statusText.contains("Pausado", ignoreCase = true) -> SManga.ON_HIATUS
                statusText.contains("Cancelado", ignoreCase = true) -> SManga.CANCELLED
                else -> SManga.ONGOING
            }
        }

        val chapterList = if (fetchChapters) {
            parseChapters(doc)
        } else {
            chapters
        }

        return SMangaUpdate(manga, chapterList)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val doc = client.get("$baseUrl${chapter.url}").asJsoup()
        val imgElements = doc.select("img[src*=/files/chapters/], img[alt^=Página]")
        val imageUrls = imgElements.map { img ->
            img.attr("abs:src").ifEmpty { img.attr("src") }
        }.filter { it.isNotBlank() }

        val finalUrls = if (imageUrls.isNotEmpty()) {
            imageUrls
        } else {
            // Regex fallback from Next.js payload scripts
            val html = doc.html()
            Regex("""https?://[^\s"'\\]+/files/chapters/[^\s"'\\]+""")
                .findAll(html)
                .map { it.value.replace(Regex("""\\+"""), "") }
                .distinct()
                .toList()
        }

        if (finalUrls.isEmpty()) {
            throw Exception("Nenhuma página encontrada para este capítulo.")
        }

        return finalUrls.mapIndexed { index, url ->
            Page(index, imageUrl = url)
        }
    }

    override fun getFilterList(data: JsonElement?): FilterList = HikariComicsFilters.get()

    private fun parseMangasPage(doc: Document): MangasPage {
        val elements = doc.select("a.group.block[href^=/comic/]")
        val seenUrls = mutableSetOf<String>()
        val mangas = mutableListOf<SManga>()

        for (el in elements) {
            val href = el.attr("href").trim()
            if (href.isEmpty() || !seenUrls.add(href)) continue

            val titleText = el.selectFirst("img")?.attr("alt")?.trim()
                ?: el.selectFirst("p.text-ink")?.text()?.trim()
                ?: ""

            val coverUrl = el.selectFirst("img")?.let { img ->
                img.attr("abs:src").ifEmpty { img.attr("src") }
            }

            mangas.add(
                SManga.create().apply {
                    url = href
                    title = titleText
                    thumbnail_url = coverUrl
                },
            )
        }

        val hasNextPage = mangas.size >= PAGE_SIZE
        return MangasPage(mangas, hasNextPage)
    }

    private fun parseChapters(doc: Document): List<SChapter> {
        val html = doc.html()
        val dateMap = mutableMapOf<String, Long>()

        // Extract publishedAt ISO dates from Next.js payload
        val dateRegex = Regex("""(?:\\\"|")slug(?:\\\"|"):\s*(?:\\\"|")(capitulo-[a-zA-Z0-9.\-]+)(?:\\\"|").*?(?:\\\"|")publishedAt(?:\\\"|"):\s*(?:\\\"|")([^\\"]+)(?:\\\"|")""")
        for (match in dateRegex.findAll(html)) {
            val slug = match.groupValues[1]
            val isoDate = match.groupValues[2]
            val parsed = parseIsoDate(isoDate)
            if (parsed > 0L) {
                dateMap[slug] = parsed
            }
        }

        val rows = doc.select("a.flex.items-center.justify-between[href*=/capitulo-]")
        val chapters = mutableListOf<SChapter>()
        val seenUrls = mutableSetOf<String>()

        for (row in rows) {
            val href = row.attr("href").trim()
            val text = row.text().trim()
            if (href.isEmpty() || text.equals("Primeiro", ignoreCase = true) || text.equals("Último", ignoreCase = true)) {
                continue
            }
            if (!seenUrls.add(href)) continue

            val chSlug = href.substringAfterLast("/")
            val nameText = row.selectFirst("span.truncate")?.text()?.trim()
                ?: row.selectFirst("span.font-medium")?.text()?.trim()
                ?: text

            val relativeDateText = row.selectFirst("span.text-xs")?.text()?.trim()

            val num = extractChapterNumber(chSlug.ifEmpty { nameText })
            val dateUpload = dateMap[chSlug] ?: parseRelativeDate(relativeDateText)

            chapters.add(
                SChapter.create().apply {
                    url = href
                    name = formatChapterName(num, nameText)
                    chapter_number = num
                    date_upload = dateUpload
                },
            )
        }

        return chapters.sortedByDescending { it.chapter_number }
    }
}
