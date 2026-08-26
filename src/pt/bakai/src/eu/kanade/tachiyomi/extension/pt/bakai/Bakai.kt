package eu.kanade.tachiyomi.extension.pt.bakai

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservable
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import rx.Observable
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Source
abstract class Bakai : HttpSource() {

    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .addInterceptor(BakaiCloudflareInterceptor(headers["User-Agent"]))
        .rateLimit(1) { it.host != "img.bakai.org" }
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    // ============================== Popular ===============================

    override fun popularMangaRequest(page: Int): Request = GET(baseUrl, headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()

        // Primary: carousel widget "mostViewedArticlesItem"
        val items = document.select("li.mostViewedArticlesItem")
        val mangas = items.mapNotNull { element ->
            val a = element.selectFirst("h3.ipsTruncate a") ?: return@mapNotNull null
            extractManga(a, element)
        }.toMutableList()

        // Fallback: find the "Mais Lidos" section and collect hentai links
        if (mangas.isEmpty()) {
            val allElements = document.allElements.toList()
            var maisLidosEl: Element? = null
            for (el in allElements) {
                if (el.ownText().contains("Mais Lidos", ignoreCase = true)) {
                    maisLidosEl = el
                    break
                }
            }
            if (maisLidosEl != null) {
                // Walk up to 7 siblings looking for a block with hentai links
                var candidate: Element? = maisLidosEl
                var containerEl: Element? = null
                var steps = 0
                while (candidate != null && steps < 7) {
                    if (candidate.select("a[href*=/hentai/]").size >= 2) {
                        containerEl = candidate
                        break
                    }
                    candidate = candidate.parent()
                    steps++
                }
                val container = containerEl ?: maisLidosEl
                mangas.addAll(extractMangasFromHentaiLinks(container))
            }
        }

        return MangasPage(mangas, false)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/home/" else "$baseUrl/home/page/$page/"
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()

        val items = document.select(
            "section.ipsCmsEntries.ipsCmsEntries--image.ipsCmsEntries--3 > article.ipsCmsEntries__item",
        )
        val mangas = items.mapNotNull { element ->
            val a = element.selectFirst("h2.ipsTitle a") ?: return@mapNotNull null
            extractManga(a, element)?.apply {
                title = a.text().trim().ifBlank { title }
            }
        }.toMutableList()

        val hasNextPage = document.selectFirst(
            "li.ipsPagination_next:not(.ipsPagination_inactive) > a, a[rel=next]",
        ) != null

        return MangasPage(mangas, hasNextPage)
    }

    // =============================== Search ===============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/srch/".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("page", page.toString())
            .addQueryParameter("quick", "1")
            .addQueryParameter("search_and_or", "and")
            .addQueryParameter("sortby", "relevancy")
            .build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()

        val mangas = document
            .select("ol[data-role=resultsContents] > li.ipsStreamItem, .ipsStreamItem, article.ipsCmsEntries__item")
            .filter {
                it.selectFirst(
                    "span.ipsStreamItem_contentType i.fa-file-text, " +
                        "span.ipsStreamItem__contentType i.fa-file-text, " +
                        "div.ipsCmsEntries__thumb, " +
                        "header.ipsCmsEntries__header",
                ) != null
            }
            .mapNotNull { element ->
                val a = element.selectFirst(
                    "h2.ipsStreamItem_title a, h2.ipsStreamItem__title a, h2.ipsTitle a, header a",
                ) ?: return@mapNotNull null

                val imgNode = element.selectFirst(
                    "span.ipsThumb img, img.ipsStream_image, img.ipsStreamItem__image, div.ipsCmsEntries__thumb img",
                )
                val thumb = if (imgNode != null) {
                    resolveImageUrl(imgNode)
                } else {
                    element.selectFirst("span.ipsThumb")?.attr("abs:data-background-src")
                }

                SManga.create().apply {
                    title = a.text().trim()
                    setUrlWithoutDomain(a.attr("href"))
                    thumbnail_url = thumb
                }
            }

        val hasNextPage = document.selectFirst(
            "li.ipsPagination__next:not(.ipsPagination__inactive) > a, " +
                "li.ipsPagination_next:not(.ipsPagination_inactive) > a",
        ) != null

        return MangasPage(mangas, hasNextPage)
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = client.newCall(searchMangaRequest(page, query, filters)).asObservable().map { response ->
        if (response.code == 429) {
            response.close()
            throw Exception("Wait 1 second before retrying or login to speed up")
        }
        searchMangaParse(response)
    }

    // =========================== Manga Details ============================

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()

        return SManga.create().apply {
            title = document.selectFirst("h1.ipsType_pageTitle span.ipsContained")?.text() ?: ""
            thumbnail_url = document.selectFirst("div.cCmsRecord_image img")?.attr("abs:src")

            author = document.selectFirst("p:has(strong:contains(Artist:))")
                ?.text()?.substringAfter("Artist:")?.trim()

            val type = document.selectFirst("p:has(strong:contains(Type:))")
                ?.text()?.substringAfter("Type:")?.trim()
            val color = document.selectFirst("p:has(strong:contains(Color:))")
                ?.text()?.substringAfter("Color:")?.trim()
            val tagsStr = document.selectFirst("p:has(strong:contains(Tags:))")
                ?.text()?.substringAfter("Tags:")?.trim()
            val parody = document.selectFirst("p:has(strong:contains(Parody:))")
                ?.text()?.substringAfter("Parody:")?.trim()

            genre = listOfNotNull(type, color, parody, tagsStr)
                .flatMap { it.split(",") }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(", ")

            description = document.selectFirst("section.ipsType_richText")?.text()
                ?.takeIf { it != "-" }

            status = SManga.COMPLETED
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
        }
    }

    // ============================== Chapters ==============================

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()

        val chapter = SChapter.create().apply {
            name = document.selectFirst("h1.ipsType_pageTitle span.ipsContained")?.text() ?: "Chapter"
            setUrlWithoutDomain(response.request.url.toString())

            val dateStr = document.selectFirst("time")?.attr("datetime")
            if (!dateStr.isNullOrBlank()) {
                try {
                    date_upload = dateFormat.parse(dateStr)?.time ?: 0L
                } catch (_: Exception) {}
            }
        }

        return listOf(chapter)
    }

    // =============================== Pages ================================

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val seen = HashSet<String>()
        val urls = mutableListOf<String>()

        document.select(
            "div.mangaReaderImages img, img.mangaReaderImage, " +
                "div.ipsGrid.ipsGrid_collapsePhone img, div.ipsPhotoFrame img, " +
                "section.ipsRichText img",
        ).forEach { img ->
            val url = resolveImageUrl(img)
            if (url.isNotBlank() && seen.add(url)) {
                urls.add(url)
            }
        }

        return urls.mapIndexed { i, url -> Page(i, imageUrl = url) }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================== Helpers ===============================

    /** Mirrors the `a(Element)` helper in the reference APK. */
    private fun resolveImageUrl(img: Element): String {
        var url = img.attr("abs:data-src")
        if (url.isEmpty()) url = img.attr("abs:data-lazy-src")
        if (url.isEmpty()) url = img.attr("abs:data-cfsrc")
        if (url.isEmpty()) {
            val srcset = img.attr("abs:srcset").trim()
            url = srcset.substringBefore(",").trim().substringBefore(" ")
        }
        if (url.isEmpty()) url = img.attr("abs:src")
        return url
    }

    /** Mirrors the `c(Element, Element)` helper: extracts SManga from an anchor + container. */
    private fun extractManga(anchor: Element, container: Element): SManga? {
        val href = anchor.attr("href").takeIf { it.isNotBlank() } ?: return null

        val img = anchor.selectFirst("img")
            ?: anchor.parent()?.selectFirst("img")
            ?: container.selectFirst("img")

        val title = sequenceOf(
            anchor.attr("title"),
            anchor.attr("aria-label"),
            anchor.selectFirst("[title]")?.attr("title"),
            img?.attr("alt"),
            anchor.text(),
            container.selectFirst("h1, h2, h3, h4")?.text(),
        ).filterNotNull().firstOrNull { it.isNotBlank() } ?: return null

        val thumb = img?.let { resolveImageUrl(it) }?.takeIf { it.isNotBlank() } ?: return null

        return SManga.create().apply {
            this.title = title
            setUrlWithoutDomain(href)
            thumbnail_url = thumb
        }
    }

    /** Mirrors the `b(Element)` helper: collects mangas from hentai links. */
    private fun extractMangasFromHentaiLinks(container: Element): List<SManga> {
        val seen = HashSet<String>()
        return container.select("a[href*=/hentai/]").mapNotNull { a ->
            val href = a.attr("href").substringBefore("#").substringBefore("?").trimEnd('/')
            if (href.isBlank() || !seen.add(href)) return@mapNotNull null
            val parent = a.parent()?.parent() ?: a.parent() ?: a
            extractManga(a, parent)
        }
    }
}
