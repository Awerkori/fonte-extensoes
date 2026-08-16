package eu.kanade.tachiyomi.extension.pt.leitordemangas

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.lib.cookieinterceptor.CookieInterceptor
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

@Source
abstract class LeitorDeMangas : KeiSource() {

    override fun OkHttpClient.Builder.configureClient() = apply {
        rateLimit(2)
        val host = baseUrl.toHttpUrl().host
        addNetworkInterceptor(CookieInterceptor(host, "mnx_adulto" to "1"))
    }

    // ============================== Popular ==============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("catalogo")
            if (page > 1) {
                addQueryParameter("page", page.toString())
            }
        }.build()

        val document = client.get(url).asJsoup()
        return parseCatalogoPage(document)
    }

    // ============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("novidades")
            if (page > 1) {
                addQueryParameter("page", page.toString())
            }
        }.build()

        val document = client.get(url).asJsoup()
        val updates = document.extractNextJs<UpdatesDto>()?.updates?.map { it.toSManga() }
            ?: document.select("div.space-y-2\\.5 > div").mapNotNull { card ->
                val link = card.selectFirst("a[href]") ?: return@mapNotNull null
                val href = link.attr("href")
                val title = card.selectFirst("a.text-sm.font-semibold")?.text()
                    ?: link.selectFirst("img")?.attr("alt") ?: return@mapNotNull null
                val cover = link.selectFirst("img")?.absUrl("src")
                SManga.create().apply {
                    setUrlWithoutDomain(href)
                    this.title = title
                    thumbnail_url = cover
                }
            }

        val hasNextPage = document.selectFirst("button:contains(Carregar mais)") != null && updates.isNotEmpty()
        return MangasPage(updates, hasNextPage)
    }

    // ============================== Search ===============================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("catalogo")
            if (page > 1) {
                addQueryParameter("page", page.toString())
            }
            if (query.isNotEmpty()) {
                addQueryParameter("q", query)
            }
            filters.firstInstanceOrNull<SortFilter>()?.toUriPart()?.takeIf { it.isNotEmpty() }?.let {
                addQueryParameter("sort", it)
            }
            filters.firstInstanceOrNull<TypeFilter>()?.toUriPart()?.takeIf { it.isNotEmpty() }?.let {
                addQueryParameter("tipo", it)
            }
            filters.firstInstanceOrNull<GenreFilter>()?.toUriPart()?.takeIf { it.isNotEmpty() }?.let {
                addQueryParameter("genero", it)
            }
        }.build()

        val document = client.get(url).asJsoup()
        return parseCatalogoPage(document)
    }

    private fun parseCatalogoPage(document: Document): MangasPage {
        val mangas = document.extractNextJs<CatalogoDto>()?.series?.map { it.toSManga() }
            ?: document.select("div.grid a.group").mapNotNull { element ->
                val href = element.attr("href")
                val title = element.selectFirst("h3")?.text()
                    ?: element.selectFirst("img")?.attr("alt")
                    ?: return@mapNotNull null
                val cover = element.selectFirst("img")?.absUrl("src")
                SManga.create().apply {
                    setUrlWithoutDomain(href)
                    this.title = title
                    thumbnail_url = cover
                }
            }

        val hasNextPage = document.selectFirst("button[aria-label='Próxima página']:not([disabled])") != null
        return MangasPage(mangas, hasNextPage)
    }

    // ============================== Manga Details ========================

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val segments = url.pathSegments.filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null
        val type = segments[0]
        if (type !in VALID_TYPES) return null
        val slug = segments.getOrNull(1) ?: return null
        val mangaPath = "/$type/$slug"
        val document = client.get("$baseUrl$mangaPath").asJsoup()
        return parseMangaDetails(document).apply {
            setUrlWithoutDomain(mangaPath)
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
        val comicSeries = document.select("script[type='application/ld+json']")
            .mapNotNull { script ->
                runCatching { script.data().parseAs<ComicSeriesDto>() }.getOrNull()
            }
            .firstOrNull { it.type == "ComicSeries" }

        title = comicSeries?.name
            ?: document.selectFirst("h1")?.text()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")
            ?: ""

        author = comicSeries?.author?.name
            ?: document.selectFirst("p:has(span:contains(Por)) span, p.text-base:contains(Por) span")?.text()

        description = comicSeries?.description?.takeIf { it.isNotEmpty() && it != "Plataforma de leitura" }
            ?: document.selectFirst("div.mt-3 p, div.mt-4 p, p[class*='leading-relaxed']")?.text()
            ?: document.selectFirst("meta[name='description']")?.attr("content")?.takeIf { it.isNotEmpty() && it != "Plataforma de leitura" }

        genre = comicSeries?.genre?.joinToString()
            ?: document.select("a[href*='genero=']").joinToString { it.text() }.takeIf { it.isNotEmpty() }

        thumbnail_url = comicSeries?.image
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: document.selectFirst("div.aspect-\\[2/3\\] img, div[class*='aspect-[2/3]'] img")?.absUrl("src")

        val statusText = document.select("div.flex.flex-wrap.gap-2 span, span[class*='rounded-full']").text()
        status = when {
            statusText.contains("Em Lançamento", ignoreCase = true) -> SManga.ONGOING
            statusText.contains("Completo", ignoreCase = true) -> SManga.COMPLETED
            statusText.contains("Hiato", ignoreCase = true) -> SManga.ON_HIATUS
            statusText.contains("Cancelado", ignoreCase = true) -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    private fun parseChapterList(document: Document, mangaUrl: String): List<SChapter> {
        val nextJsChapters = document.extractNextJs<ChapterDataDto>()?.chapters
        if (!nextJsChapters.isNullOrEmpty()) {
            return nextJsChapters.map { it.toSChapter(mangaUrl) }
        }

        return document.select("ul.divide-y li a[href]").map { element ->
            SChapter.create().apply {
                setUrlWithoutDomain(element.attr("href"))
                name = element.selectFirst("span.truncate")?.text() ?: element.text()
                chapter_number = element.attr("href").substringAfterLast("/").toFloatOrNull() ?: -1f
                date_upload = parseDate(element.selectFirst("p.text-mnx-muted")?.text())
            }
        }
    }

    // ============================== Pages ================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterPath = if (chapter.url.startsWith("/")) chapter.url else "/${chapter.url}"

        // Direct pages load first
        val initialDoc = client.get("$baseUrl$chapterPath").asJsoup()
        val directPages = initialDoc.extractNextJs<PageListDto>()?.pages
        if (!directPages.isNullOrEmpty()) {
            return directPages.mapIndexed { index, page ->
                Page(index, imageUrl = page.url)
            }
        }

        // Gate resolution
        val requestBody = GateTokenRequest(returnTo = chapterPath).toJsonRequestBody()
        val tokenResponse = client.post(GATE_TOKEN_URL, headers, requestBody).parseAs<GateTokenResponseDto>()
        val token = tokenResponse.data.token
        val gateUrl = tokenResponse.data.gateUrl
        val minWait = tokenResponse.data.minWaitSeconds ?: 0

        if (!gateUrl.isNullOrBlank()) {
            runCatching { client.get(gateUrl) }
        }

        val waitMs = maxOf(minWait * 1000L, 4000L)
        delay(waitMs)

        var callbackDoc = client.get("$baseUrl/gate/callback?token=$token").asJsoup()
        var pages = callbackDoc.extractNextJs<PageListDto>()?.pages

        if (pages.isNullOrEmpty()) {
            delay(3000L)
            callbackDoc = client.get("$baseUrl/gate/callback?token=$token").asJsoup()
            pages = callbackDoc.extractNextJs<PageListDto>()?.pages
        }

        if (pages.isNullOrEmpty()) {
            val chapterDoc = client.get("$baseUrl$chapterPath").asJsoup()
            pages = chapterDoc.extractNextJs<PageListDto>()?.pages
        }

        return pages?.mapIndexed { index, page ->
            Page(index, imageUrl = page.url)
        } ?: emptyList()
    }

    // ============================== Filters ==============================

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        SortFilter(),
        TypeFilter(),
        GenreFilter(),
    )

    companion object {
        private const val GATE_TOKEN_URL = "https://app.leitordemangas.com/v1/www/gate-token"
        private val VALID_TYPES = setOf("manga", "manhwa", "manhua")
    }
}
