package eu.kanade.tachiyomi.extension.pt.megahentai

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import java.util.Locale

@Source
abstract class MegaHentai : HttpSource() {

    override val supportsLatest = true

    override val client: OkHttpClient = network.client

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)

    // ============================== Popular / Browse ==============================
    // Uses the site's official /popular/ section
    override fun popularMangaRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/popular/" else "$baseUrl/popular/page/$page/"
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage = parseMangaPage(response)

    // ============================== Latest / Recentes ==============================
    // Uses the site's official /capitulos-recentes/ section
    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/capitulos-recentes/" else "$baseUrl/capitulos-recentes/page/$page/"
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = parseMangaPage(response)

    // ============================== Search ==============================
    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        val parsedUrl = query.toHttpUrlOrNull()
        if (parsedUrl != null && parsedUrl.encodedPath.contains("/ler-online/")) {
            return client.newCall(GET(query, headers))
                .asObservableSuccess()
                .map { response ->
                    val document = response.asJsoup()
                    val manga = mangaDetailsParse(document).apply {
                        setUrlWithoutDomain(query)
                    }
                    MangasPage(listOf(manga), false)
                }
        }
        return super.fetchSearchManga(page, query, filters)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val categoryFilter = filters.filterIsInstance<CategoryFilter>().firstOrNull()
        val category = categoryFilter?.selected

        val url = if (query.isBlank() && !category.isNullOrBlank()) {
            val baseCatUrl = "$baseUrl/$category/"
            if (page > 1) "${baseCatUrl.trimEnd('/')}/page/$page/" else baseCatUrl
        } else {
            val baseSearchUrl = if (page == 1) "$baseUrl/" else "$baseUrl/page/$page/"
            baseSearchUrl.toHttpUrl().newBuilder()
                .addQueryParameter("s", query)
                .build()
                .toString()
        }

        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseMangaPage(response)

    // ============================== Manga Details ==============================
    override fun mangaDetailsParse(response: Response): SManga = mangaDetailsParse(response.asJsoup())

    private fun mangaDetailsParse(document: Document): SManga {
        val content = document.selectFirst(".sheader_content") ?: document
        val titleElement = content.selectFirst(".data_main > h1")
        val rawTitle = titleElement?.text() ?: run {
            document.select("h1").map { it.text().trim() }
                .firstOrNull { it.startsWith("Todos os Capítulos de ", ignoreCase = true) }
                ?: document.selectFirst("h1, .post-title h1, .entry-title")?.text().orEmpty()
        }

        val thumbnail = content.selectFirst(".poster .thumb img")?.let { getImageSrc(it) }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }

        val description = content.selectFirst(".sinopse .texto")?.text()?.trim()?.takeIf { it.isNotBlank() }
        val author = getInfoValue(content, "Autor")
        val artist = getInfoValue(content, "Artista")
        val genres = content.select(".gen_flex a[rel=tag]").map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(", ")

        val statusText = getInfoValue(content, "Status")?.lowercase(Locale.ROOT)
        val status = when (statusText) {
            "em lançamento", "em lancamento", "lançamento", "lancamento" -> SManga.ONGOING
            "completo", "finalizado", "concluído", "concluido" -> SManga.COMPLETED
            "em hiato", "hiato" -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }

        return SManga.create().apply {
            this.title = cleanTitle(rawTitle)
            this.thumbnail_url = thumbnail
            this.description = description
            this.author = author
            this.artist = artist
            this.genre = genres
            this.status = status
        }
    }

    // ============================== Chapters ==============================
    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val mangaTitle = mangaDetailsParse(document).title

        return document.select("a[href*='/ler/']").mapNotNull { element ->
            val text = element.text()
            val href = element.absUrl("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val chapterName = text.takeIf { it.isNotBlank() } ?: mangaTitle

            val number = CHAPTER_NUMBER_REGEX.find(text)?.groupValues?.get(1)?.toFloatOrNull()
                ?: CHAPTER_NUMBER_REGEX.find(href)?.groupValues?.get(1)?.toFloatOrNull()
                ?: -1f

            SChapter.create().apply {
                this.name = chapterName
                this.chapter_number = number
                setUrlWithoutDomain(href)
            }
        }.distinctBy { it.url }
    }

    // ============================== Page List / Reader ==============================
    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        return document.select("#content.cap img[src]")
            .mapNotNull { getImageSrc(it) }
            .filter { it.contains("gall") && it.contains("megahentai.biz/static/") }
            .distinct()
            .mapIndexed { index, imageUrl ->
                Page(index, "", imageUrl)
            }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================== Filters ==============================
    override fun getFilterList(): FilterList = FilterList(
        CategoryFilter(
            arrayOf(
                Pair("Todas", ""),
                Pair("Doujinshi", "doujinshi"),
                Pair("HQs", "hqs"),
                Pair("Sem censura", "sem-censura"),
                Pair("Manhwas18", "manhwas18"),
            ),
        ),
    )

    private class CategoryFilter(val options: Array<Pair<String, String>>) : Filter.Select<String>("Categoria", options.map { it.first }.toTypedArray()) {
        val selected: String
            get() = options[state].second
    }

    // ============================== Helpers ==============================
    private fun parseMangaPage(response: Response): MangasPage {
        val document = response.asJsoup()
        val elements = document.select("article:has(a[href*='/ler-online/']), .post:has(a[href*='/ler-online/']), .item:has(a[href*='/ler-online/']), .manga:has(a[href*='/ler-online/'])")

        val seen = mutableSetOf<String>()
        val mangaList = mutableListOf<SManga>()

        for (element in elements) {
            val link = element.selectFirst("a[href*='/ler-online/']") ?: continue
            val rawTitle = element.selectFirst("h1, h2, h3, h4, .title, .post-title")?.text()?.takeIf { it.isNotBlank() }
                ?: link.attr("title").takeIf { it.isNotBlank() }
                ?: link.text()

            val href = link.absUrl("href").takeIf { it.isNotBlank() } ?: continue
            val thumbnail = element.selectFirst("img")?.let { getImageSrc(it) }

            if (seen.add(href)) {
                val manga = SManga.create().apply {
                    this.title = cleanTitle(rawTitle)
                    this.thumbnail_url = thumbnail
                    setUrlWithoutDomain(href)
                }
                mangaList.add(manga)
            }
        }

        val hasNextPage = document.selectFirst("a.next, a.nextpostslink, a.page-numbers.next, .pagination a[rel=next]") != null
        return MangasPage(mangaList, hasNextPage)
    }

    private fun cleanTitle(title: String): String = title.replace(TITLE_PREFIX_REGEX, "")
        .replace(TITLE_SUFFIX_REGEX, "")
        .trim()

    private fun getImageSrc(element: Element): String? {
        val src = sequenceOf("data-lazy-src", "data-src", "data-original", "data-lazy", "src")
            .map { element.attr(it).trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
            ?: return null

        return when {
            src.startsWith("//") -> "https:$src"
            !src.startsWith("http://") && !src.startsWith("https://") -> {
                if (src.startsWith("/")) "$baseUrl$src" else "$baseUrl/${src.trimStart('/')}"
            }
            else -> src
        }
    }

    private fun getInfoValue(element: Element, label: String): String? {
        val items = element.select("ul.data_tvshow > li.data_info")
        for (item in items) {
            val labelText = item.selectFirst("div")?.text()?.trim()?.removeSuffix(":")
            if (labelText?.equals(label, ignoreCase = true) == true) {
                return item.selectFirst("span")?.text()?.trim()?.takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    companion object {
        private val TITLE_PREFIX_REGEX = Regex("^Todos\\s+os\\s+Capítulos\\s+de\\s+", RegexOption.IGNORE_CASE)
        private val TITLE_SUFFIX_REGEX = Regex("\\s+Todos\\s+os\\s+Capítulos$", RegexOption.IGNORE_CASE)
        private val CHAPTER_NUMBER_REGEX = Regex("""(?i)(?:cap[ií]tulo|cap\.?|ep\.?)\s*(\d+(?:\.\d+)?)""")
    }
}
