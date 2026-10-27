package eu.kanade.tachiyomi.extension.pt.hentaitecanet

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

@Source
abstract class HentaitecaNet : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        rateLimit(permits = 2) { it.host.contains("hentaiteca") }
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
        val path = if (page <= 1) "/obra/" else "/obra/page/$page/"
        val url = "$baseUrl$path".toHttpUrl().newBuilder()
            .addQueryParameter("m_orderby", "views")
            .build()

        val doc = client.get(url).asJsoup()
        return parseMangasPage(doc)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val path = if (page <= 1) "/obra/" else "/obra/page/$page/"
        val url = "$baseUrl$path".toHttpUrl().newBuilder()
            .addQueryParameter("m_orderby", "latest")
            .build()

        val doc = client.get(url).asJsoup()
        return parseMangasPage(doc)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val sort = filters.valueOrEmpty<SortFilter>().ifEmpty { "views" }
        val status = filters.valueOrEmpty<StatusFilter>()
        val condition = filters.valueOrEmpty<GenreConditionFilter>()
        val selectedGenres = filters.selectedGenres()

        val isSearch = query.isNotBlank() || status.isNotEmpty() || selectedGenres.isNotEmpty()

        val url = if (isSearch) {
            val path = if (page <= 1) "/" else "/page/$page/"
            "$baseUrl$path".toHttpUrl().newBuilder().apply {
                addQueryParameter("s", query.trim())
                addQueryParameter("post_type", "wp-manga")

                selectedGenres.forEach { genreSlug ->
                    addQueryParameter("genre[]", genreSlug)
                }

                if (condition.isNotEmpty()) {
                    addQueryParameter("op", condition)
                }

                if (status.isNotEmpty()) {
                    addQueryParameter("status[]", status)
                }

                addQueryParameter("m_orderby", sort)
            }.build()
        } else {
            val path = if (page <= 1) "/obra/" else "/obra/page/$page/"
            "$baseUrl$path".toHttpUrl().newBuilder().apply {
                addQueryParameter("m_orderby", sort)
            }.build()
        }

        val doc = client.get(url).asJsoup()
        return parseMangasPage(doc)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val segments = url.pathSegments
        val idx = segments.indexOf("obra")
        if (idx == -1 || idx + 1 >= segments.size) return null
        val slug = segments[idx + 1]
        if (slug.isBlank()) return null

        val manga = SManga.create().apply {
            this.url = "/obra/$slug/"
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
        val fullUrl = if (manga.url.startsWith("http")) manga.url else "$baseUrl${manga.url}"
        val doc = client.get(fullUrl).asJsoup()

        if (fetchDetails) {
            val titleText = doc.selectFirst(".post-title h1, .post-title h2, div.post-title")?.text()?.trim()
            if (!titleText.isNullOrEmpty()) {
                manga.title = titleText
            }

            val ogImage = doc.selectFirst("meta[property=og:image]")?.attr("content")
            val coverImg = doc.selectFirst("div.summary_image img")?.let { img ->
                img.attr("abs:data-src").trim()
                    .ifEmpty { img.attr("abs:data-lazy-src").trim() }
                    .ifEmpty { img.attr("abs:src").trim() }
            }
            val thumbnail = ogImage?.takeIf { it.isNotBlank() } ?: coverImg?.takeIf { it.isNotBlank() }
            if (thumbnail != null) {
                manga.thumbnail_url = cleanThumbnail(thumbnail)
            }

            val descEl = doc.selectFirst("div.description-summary div.summary__content")
                ?: doc.selectFirst("div.manga-excerpt")
            val synopsisText = descEl?.text()?.trim()
            val metaDesc = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
                ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            val finalDesc = synopsisText?.takeIf { it.isNotBlank() } ?: metaDesc
            if (!finalDesc.isNullOrEmpty()) {
                manga.description = finalDesc
            }

            val author = doc.select("div.author-content a").eachText().joinToString(", ").trim()
            val artist = doc.select("div.artist-content a").eachText().joinToString(", ").trim()
            if (author.isNotEmpty()) manga.author = author
            if (artist.isNotEmpty()) manga.artist = artist

            val genres = doc.select("div.genres-content a").eachText()
            val tags = doc.select("div.tags-content a").eachText()
            val allGenres = (genres + tags).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (allGenres.isNotEmpty()) {
                manga.genre = allGenres.joinToString(", ")
            }

            val statusText = doc.select(".post-content_item:contains(Status) .summary-content").text().trim()
            manga.status = when {
                statusText.contains("Lançamento", ignoreCase = true) -> SManga.ONGOING
                statusText.contains("Completo", ignoreCase = true) || statusText.contains("Completa", ignoreCase = true) -> SManga.COMPLETED
                statusText.contains("Hiato", ignoreCase = true) || statusText.contains("Pausado", ignoreCase = true) -> SManga.ON_HIATUS
                statusText.contains("Cancelado", ignoreCase = true) || statusText.contains("Cancelada", ignoreCase = true) -> SManga.CANCELLED
                else -> SManga.UNKNOWN
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
        val fullUrl = if (chapter.url.startsWith("http")) chapter.url else "$baseUrl${chapter.url}"
        val doc = client.get(fullUrl).asJsoup()

        val imgElements = doc.select("div.reading-content img, div.page-break img, img.wp-manga-chapter-img")
        val imageUrls = imgElements.mapNotNull { img ->
            val src = img.attr("abs:data-src").trim()
                .ifEmpty { img.attr("abs:data-lazy-src").trim() }
                .ifEmpty { img.attr("abs:src").trim() }
            src.takeIf { it.isNotBlank() && (it.startsWith("http://") || it.startsWith("https://")) }
        }.distinct()

        if (imageUrls.isEmpty()) {
            throw Exception("Nenhuma página encontrada para este capítulo.")
        }

        return imageUrls.mapIndexed { index, url ->
            Page(index, imageUrl = url)
        }
    }

    override fun getFilterList(data: JsonElement?): FilterList = HentaitecaNetFilters.get()

    private fun parseMangasPage(doc: Document): MangasPage {
        val elements = doc.select("div.page-item-detail, div.c-tabs-item__content")
        val seenUrls = mutableSetOf<String>()
        val mangas = mutableListOf<SManga>()

        for (el in elements) {
            val titleLink = el.selectFirst(".post-title a, h2.h5 a, h3.h4 a")
            val thumbLink = el.selectFirst(".item-thumb a, .tab-thumb a")
            val link = titleLink ?: thumbLink ?: continue

            val href = link.attr("href").trim()
            if (href.isEmpty() || !seenUrls.add(href)) continue

            val rawTitle = titleLink?.text()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: thumbLink?.attr("title")?.trim()?.takeIf { it.isNotEmpty() }
                ?: el.selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotEmpty() }
                ?: continue

            val coverImg = el.selectFirst("img")?.let { img ->
                img.attr("abs:data-src").trim()
                    .ifEmpty { img.attr("abs:data-lazy-src").trim() }
                    .ifEmpty { img.attr("abs:src").trim() }
            }

            mangas.add(
                SManga.create().apply {
                    url = href.removePrefix(baseUrl).let { if (it.startsWith("/")) it else "/$it" }
                    title = rawTitle
                    thumbnail_url = cleanThumbnail(coverImg)
                },
            )
        }

        val hasNextPage = doc.selectFirst(".wp-pagenavi a.nextpostslink, .nav-previous, a[rel=next]") != null
        return MangasPage(mangas, hasNextPage)
    }

    private fun parseChapters(doc: Document): List<SChapter> {
        val rows = doc.select("li.wp-manga-chapter")
        val chapters = mutableListOf<SChapter>()
        val seenUrls = mutableSetOf<String>()

        for (row in rows) {
            val link = row.selectFirst("a") ?: continue
            val href = link.attr("href").trim()
            if (href.isEmpty() || !seenUrls.add(href)) continue

            val nameText = link.text().trim()
            val dateEl = row.selectFirst("span.chapter-release-date")
            val relativeTitle = dateEl?.selectFirst("a[title]")?.attr("title")
            val dateText = relativeTitle?.takeIf { it.isNotBlank() }
                ?: dateEl?.selectFirst("i")?.text()?.trim()
                ?: dateEl?.text()?.trim()

            val num = extractChapterNumber(nameText, href)
            val dateUpload = parseDate(dateText)

            chapters.add(
                SChapter.create().apply {
                    url = href.removePrefix(baseUrl).let { if (it.startsWith("/")) it else "/$it" }
                    name = nameText
                    chapter_number = num
                    date_upload = dateUpload
                },
            )
        }

        return chapters
    }
}
