package eu.kanade.tachiyomi.extension.pt.blitztoons

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
import java.time.Instant

const val BLITZTOONS_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
private val CHAPTER_NUMBER_REGEX = Regex("""(?:Capítulo|Cap\.?|Ch\.?)\s*(\d+(?:[.,]\d+)?)""", RegexOption.IGNORE_CASE)

@Source
abstract class BlitzToons : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        rateLimit(permits = 2) { it.host == "blitztoons.com" }
    }

    override fun Headers.Builder.configureHeaders(): Headers.Builder = apply {
        set("User-Agent", BLITZTOONS_USER_AGENT)
    }

    override fun imageRequest(page: Page): Request = buildBlitzImageRequest(page.imageUrl!!, baseUrl, headers)

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$baseUrl/obras".toHttpUrl().newBuilder()
            .addQueryParameter("ordem", "populares")
            .addQueryParameter("p", page.toString())
            .build()

        val doc = client.get(url).asJsoup()
        return parseMangasPage(doc)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$baseUrl/obras".toHttpUrl().newBuilder()
            .addQueryParameter("ordem", "recentes")
            .addQueryParameter("p", page.toString())
            .build()

        val doc = client.get(url).asJsoup()
        return parseMangasPage(doc)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val urlBuilder = "$baseUrl/obras".toHttpUrl().newBuilder()

        if (query.isNotBlank()) {
            urlBuilder.addQueryParameter("q", query.trim())
        }

        filters.forEach { filter ->
            when (filter) {
                is OrderFilter -> {
                    if (filter.selected.isNotEmpty()) {
                        urlBuilder.addQueryParameter("ordem", filter.selected)
                    }
                }
                is TypeFilter -> {
                    if (filter.selected.isNotEmpty()) {
                        urlBuilder.addQueryParameter("tipo", filter.selected)
                    }
                }
                is StatusFilter -> {
                    if (filter.selected.isNotEmpty()) {
                        urlBuilder.addQueryParameter("status", filter.selected)
                    }
                }
                is GenreFilter -> {
                    if (filter.selected.isNotEmpty()) {
                        urlBuilder.addQueryParameter("genero", filter.selected)
                    }
                }
                else -> {}
            }
        }

        urlBuilder.addQueryParameter("p", page.toString())

        val doc = client.get(urlBuilder.build()).asJsoup()
        return parseMangasPage(doc)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val mangaPath = resolveMangaUrl(url) ?: return null
        return SManga.create().apply {
            this.url = mangaPath
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val doc = client.get("$baseUrl${manga.url}").asJsoup()

        val updatedManga = if (fetchDetails) {
            parseMangaDetails(doc, manga)
        } else {
            manga
        }

        val chapterList = if (fetchChapters) {
            parseChapterList(doc)
        } else {
            chapters
        }

        return SMangaUpdate(updatedManga, chapterList)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val doc = client.get("$baseUrl${chapter.url}").asJsoup()
        return parsePageList(doc)
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        OrderFilter(),
        TypeFilter(),
        StatusFilter(),
        GenreFilter(),
    )
}

internal fun buildBlitzImageRequest(imageUrl: String, baseUrl: String, baseHeaders: Headers? = null): Request {
    val builder = baseHeaders?.newBuilder() ?: Headers.Builder()
    if (builder["User-Agent"] == null) {
        builder.set("User-Agent", BLITZTOONS_USER_AGENT)
    }
    val finalHeaders = builder
        .set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
        .set("Sec-Fetch-Dest", "image")
        .set("Sec-Fetch-Mode", "no-cors")
        .set("Sec-Fetch-Site", "same-site")
        .set("Referer", "$baseUrl/")
        .removeAll("Origin")
        .build()

    return Request.Builder()
        .url(imageUrl)
        .headers(finalHeaders)
        .get()
        .build()
}

internal fun resolveMangaUrl(url: HttpUrl): String? {
    val path = url.encodedPath
    return when {
        path.startsWith("/obra/") -> path
        path.startsWith("/ler/") -> {
            val segments = url.pathSegments
            if (segments.size >= 2) "/obra/${segments[1]}" else null
        }
        else -> null
    }
}

internal fun parseMangasPage(doc: Document): MangasPage {
    val mangas = doc.select("article.card").mapNotNull { card ->
        val link = card.selectFirst("a.card-cover")
            ?: card.selectFirst("h3.card-title a")
            ?: return@mapNotNull null

        val href = link.attr("href").trim()
        if (href.isBlank()) return@mapNotNull null

        val title = card.selectFirst("h3.card-title a")?.text()?.trim()
            ?: card.selectFirst("img")?.attr("alt")?.removePrefix("Capa de ")?.trim()
            ?: return@mapNotNull null

        val coverImg = card.selectFirst("img")
        val thumbnail = coverImg?.attr("abs:src")
            ?.takeIf { it.isNotBlank() }
            ?: coverImg?.attr("abs:data-src")

        SManga.create().apply {
            this.url = href
            this.title = title
            this.thumbnail_url = thumbnail
        }
    }

    val hasNextPage = doc.selectFirst("nav.pager a:contains(Próxima)") != null ||
        doc.selectFirst("link[rel=next]") != null

    return MangasPage(mangas, hasNextPage)
}

internal fun parseMangaDetails(doc: Document, manga: SManga): SManga = manga.apply {
    title = doc.selectFirst("h1")?.text()?.trim() ?: title
    thumbnail_url = doc.selectFirst("img.sbanner-cover")?.attr("abs:src")
        ?.takeIf { it.isNotBlank() }
        ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
        ?: thumbnail_url

    val altTitle = doc.selectFirst("p.salt")?.text()?.trim().orEmpty()
    val syn = doc.selectFirst("div.syn")?.text()?.trim()
        ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim().orEmpty()

    description = buildString {
        if (altTitle.isNotBlank()) {
            append("Título alternativo: ").append(altTitle).append("\n\n")
        }
        append(syn)
    }.trim()

    val genres = doc.select("div.sgenres span").map { it.text().trim() }.filter { it.isNotBlank() }.toMutableList()
    val type = doc.selectFirst("span.pill")?.text()?.trim()
    if (!type.isNullOrBlank() && !genres.contains(type)) {
        genres.add(0, type)
    }
    genre = genres.joinToString(", ")

    val statusText = doc.select("dl.smeta div").firstOrNull {
        it.selectFirst("dt")?.text()?.contains("Status", true) == true
    }?.selectFirst("dd")?.text()?.trim()

    status = when (statusText) {
        "Em lançamento" -> SManga.ONGOING
        "Completo" -> SManga.COMPLETED
        "Hiato" -> SManga.ON_HIATUS
        "Cancelado" -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }
    initialized = true
}

internal fun parseChapterList(doc: Document): List<SChapter> {
    return doc.select("ul.chaplist li.chap").mapNotNull { li ->
        val link = li.selectFirst("a.chap-alvo") ?: return@mapNotNull null
        val href = link.attr("href").trim()
        if (href.isBlank()) return@mapNotNull null

        val name = li.selectFirst("span.chap-nome")?.text()?.trim()
            ?: link.text().trim()

        val numStr = li.attr("data-num").trim().ifEmpty {
            CHAPTER_NUMBER_REGEX.find(name)?.groupValues?.get(1).orEmpty()
        }
        val chapterNumber = numStr.replace(',', '.').toFloatOrNull() ?: -1f

        val dateStr = li.selectFirst("time")?.attr("datetime")?.trim()
        val uploadDate = dateStr?.let {
            runCatching { Instant.parse(it).toEpochMilli() }.getOrDefault(0L)
        } ?: 0L

        SChapter.create().apply {
            this.url = href
            this.name = name
            this.chapter_number = chapterNumber
            this.date_upload = uploadDate
        }
    }
}

internal fun parsePageList(doc: Document): List<Page> {
    val pageElements = doc.select("img.page, div.pages img, section.ch-block img")

    return pageElements.mapNotNull { img ->
        val url = img.attr("abs:src").ifEmpty { img.attr("abs:data-src") }
        url.takeIf { it.isNotBlank() }
    }.distinct().mapIndexed { index, imageUrl ->
        Page(index, imageUrl = imageUrl)
    }
}
