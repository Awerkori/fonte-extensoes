package eu.kanade.tachiyomi.extension.pt.hentaihome

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Source
abstract class HentaiHome : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        rateLimit(3) { !it.encodedPath.startsWith("/wp-content/uploads/") }
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$baseUrl/wp-json/wp/v2/posts".toHttpUrl().newBuilder()
            .addQueryParameter("orderby", "title")
            .addQueryParameter("order", "asc")
            .addQueryParameter("per_page", "20")
            .addQueryParameter("page", page.toString())
            .build()

        val response = client.get(url)
        val posts = response.parseAs<List<JsonObject>>()
        val totalPages = response.header("X-WP-TotalPages")?.toIntOrNull() ?: 1

        val mangas = posts.mapNotNull { post ->
            val link = post["link"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val rawTitle = post["title"]?.jsonObject?.get("rendered")?.jsonPrimitive?.contentOrNull.orEmpty()
            val ogImage = post["yoast_head_json"]?.jsonObject?.get("og_image")?.jsonArray
                ?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull

            SManga.create().apply {
                this.url = link.toHttpUrl().encodedPath
                this.title = Parser.unescapeEntities(rawTitle, false)
                thumbnail_url = ogImage
            }
        }

        return MangasPage(mangas, hasNextPage = page < totalPages)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = fetchListing("$baseUrl/", page)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isNotBlank()) {
            val url = if (page > 1) {
                "$baseUrl/page/$page/".toHttpUrl().newBuilder()
                    .addQueryParameter("s", query.trim())
                    .build()
            } else {
                baseUrl.toHttpUrl().newBuilder()
                    .addQueryParameter("s", query.trim())
                    .build()
            }
            return parseListing(client.get(url).asJsoup())
        }

        val category = filters.firstInstanceOrNull<CategoryFilter>()?.selected().orEmpty()
        return if (category.isNotEmpty()) {
            fetchListing("$baseUrl/categoria/$category", page)
        } else {
            getLatestUpdates(page)
        }
    }

    private suspend fun fetchListing(listingUrl: String, page: Int): MangasPage {
        val targetUrl = if (page > 1) {
            val trimmed = listingUrl.trimEnd('/')
            "$trimmed/page/$page/"
        } else {
            listingUrl
        }
        return parseListing(client.get(targetUrl).asJsoup())
    }

    private fun parseListing(document: Document): MangasPage {
        val mangas = document.select("ul.videos li .video-conteudo").mapNotNull { item ->
            val anchor = item.selectFirst(".thumb-conteudo a") ?: return@mapNotNull null
            val img = item.selectFirst(".thumb-conteudo img")
            val titleText = item.selectFirst(".titulo h2")?.text()
                ?: anchor.attr("title").ifEmpty { return@mapNotNull null }

            SManga.create().apply {
                url = anchor.absUrl("href").toHttpUrl().encodedPath
                title = titleText
                thumbnail_url = img?.absUrl("src")?.ifEmpty { img.absUrl("data-src") }
            }
        }

        val hasNextPage = document.selectFirst("ul.paginacao li.next a") != null
        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val slug = url.pathSegments.firstOrNull { it.isNotEmpty() } ?: return null

        val manga = SManga.create().apply { this.url = "/$slug/" }
        return fetchMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false)
            .manga
            .apply { initialized = true }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()

        val publishedTime = document.selectFirst("meta[property=article:published_time]")?.attr("content")
        val dateUpload = parseDate(publishedTime)

        val updatedManga = SManga.create().apply {
            url = manga.url
            title = document.selectFirst(".post-conteudo h1")?.text() ?: manga.title
            thumbnail_url = document.selectFirst(".post-conteudo .foto img, .post-conteudo .gallery-item img")
                ?.absUrl("src")
                ?: manga.thumbnail_url
            description = document.selectFirst(".post-conteudo .post-texto")?.text()

            val categories = document.select(".post-conteudo .post-tags a[href*=/categoria/]").map { it.text() }
            val tags = document.select(".post-conteudo .post-tags a[href*=/tag/]").map { it.text() }
            genre = (categories + tags).distinct().joinToString()

            status = SManga.COMPLETED
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
            initialized = true
        }

        val chapterList = listOf(
            SChapter.create().apply {
                url = manga.url
                name = "Capítulo"
                chapter_number = 1F
                date_upload = dateUpload
            },
        )

        return SMangaUpdate(updatedManga, chapterList)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()

        val imageUrls = buildList {
            document.select(".post-conteudo .foto a.fancybox, .post-conteudo .foto a[rel=gallery]").forEach { a ->
                val href = a.absUrl("href")
                if (href.isNotEmpty()) add(href)
            }
            if (isEmpty()) {
                document.select(".post-conteudo .gallery .gallery-item img").forEach { img ->
                    val src = img.absUrl("src").ifEmpty { img.absUrl("data-src") }
                    if (src.isNotEmpty()) add(src)
                }
            }
        }

        return imageUrls.mapIndexed { index, imageUrl ->
            Page(index, imageUrl = imageUrl)
        }
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        Filter.Header("Filtros são ignorados na busca por texto"),
        CategoryFilter(),
    )

    private fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrEmpty()) return 0L
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            format.parse(dateStr)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }
}
