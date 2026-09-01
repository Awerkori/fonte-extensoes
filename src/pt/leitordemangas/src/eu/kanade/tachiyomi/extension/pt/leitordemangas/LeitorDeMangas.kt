package eu.kanade.tachiyomi.extension.pt.leitordemangas

import android.util.Base64
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
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import org.jsoup.nodes.Document
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Source
abstract class LeitorDeMangas : KeiSource() {

    override fun OkHttpClient.Builder.configureClient() = apply {
        protocols(listOf(Protocol.HTTP_1_1))
        addInterceptor(WebViewInterceptor(baseUrl, headers["User-Agent"]))
        rateLimit(2)
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
        val parts = chapter.url.trim('/').split('/')
        val slug = parts.getOrNull(parts.lastIndex - 1) ?: return emptyList()
        val chapterId = parts.lastOrNull() ?: return emptyList()
        val response = client.get("$PAGE_API_BASE_URL/$slug/chapters/$chapterId/pages")
            .parseAs<PageApiResponseDto>()
        val imageUrls = response.data.pages.map { it.imageUrl }
        val direct = imageUrls.filter { it.startsWith("http://") || it.startsWith("https://") }
        val decrypted = decryptImages(imageUrls.filter { it.startsWith("AQAA") })
        return (direct + decrypted).filterNot { it.contains("/cover/") }.distinct()
            .mapIndexed { index, url -> Page(index, imageUrl = url) }
    }

    private suspend fun decryptImages(values: List<String>): List<String> {
        val first = values.firstOrNull()?.decodeOpaque() ?: return emptyList()
        if (first.size < 13) return emptyList()
        val version = first[0].toInt()
        val counter = ((first[1].toInt() and 0xFF) shl 24) or ((first[2].toInt() and 0xFF) shl 16) or
            ((first[3].toInt() and 0xFF) shl 8) or (first[4].toInt() and 0xFF)
        val key = client.get("$baseUrl/api/atfield/key?v=$version&e=$counter").parseAs<AtfieldKeyDto>()
        val secret = Base64.decode(key.k, Base64.DEFAULT)
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }
        return values.mapNotNull { value ->
            val bytes = value.decodeOpaque() ?: return@mapNotNull null
            if (bytes.size < 14) return@mapNotNull null
            val nonce = bytes.copyOfRange(5, 13)
            val encrypted = bytes.copyOfRange(13, bytes.size)
            val output = ByteArray(encrypted.size)
            var offset = 0
            var block = 0
            while (offset < encrypted.size) {
                val digest = mac.doFinal(nonce + byteArrayOf((block++).toByte()))
                val count = minOf(digest.size, encrypted.size - offset)
                digest.copyInto(output, offset, 0, count)
                offset += count
            }
            encrypted.indices.map { (encrypted[it].toInt() xor output[it].toInt()).toByte() }.toByteArray()
                .toString(Charsets.UTF_8).takeIf { it.startsWith("http://") || it.startsWith("https://") }
        }
    }

    private fun String.decodeOpaque(): ByteArray? = runCatching {
        var value = replace('-', '+').replace('_', '/')
        value += "=".repeat((4 - value.length % 4) % 4)
        Base64.decode(value, Base64.DEFAULT)
    }.getOrNull()

    // ============================== Filters ==============================

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        SortFilter(),
        TypeFilter(),
        GenreFilter(),
    )

    companion object {
        private const val PAGE_API_BASE_URL = "https://app.leitordemangas.com/v1/www/works"
        private val VALID_TYPES = setOf("manga", "manhwa", "manhua")
    }
}
