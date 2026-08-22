package eu.kanade.tachiyomi.extension.pt.fliptru

import android.util.Log
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import java.io.IOException

@Source
abstract class Fliptru : HttpSource() {

    override val supportsLatest = true

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(2)
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/home/comics/most_popular", headers)

    override fun popularMangaParse(response: Response): MangasPage = response.use { res ->
        MangasPage(parseComicCards(res.asJsoup()), hasNextPage = false)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET(buildPagedUrl(RECENT_PATH, page), headers)

    override fun latestUpdatesParse(response: Response): MangasPage = response.use { res ->
        parseComicListPage(res.asJsoup())
    }

    // =============================== Search ===============================

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        if (query.isNotBlank() && query.length < SEARCH_MIN_LENGTH) {
            return Observable.just(MangasPage(emptyList(), hasNextPage = false))
        }

        return super.fetchSearchManga(page, query, filters)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotBlank()) {
            val url = "$baseUrl/search/".toHttpUrl().newBuilder()
                .addQueryParameter("term", query)
                .addQueryParameter("page", page.toString())
                .build()

            return GET(url, ajaxHeaders("/"))
        }

        val listPath = filters.firstInstanceOrNull<ListFilter>()?.selectedValue ?: POPULAR_PATH
        val category = filters.firstInstanceOrNull<CategoryFilter>()?.selectedValue.orEmpty()
        val tag = filters.firstInstanceOrNull<TagFilter>()?.selectedValue.orEmpty()
        val path = when {
            tag.isNotEmpty() -> "/tag/$tag"
            listPath == RECENT_PATH && category.isNotEmpty() -> "/category/$category"
            else -> listPath
        }

        return GET(buildPagedUrl(path, page), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = response.use { res ->
        if (res.request.url.encodedPath == SEARCH_PATH) {
            val mangas = res.parseAs<List<SearchResultDto>>()
                .mapNotNull { it.toComicEntry(baseUrl) }
                .map { entry ->
                    SManga.create().apply {
                        title = entry.title
                        setUrlWithoutDomain(baseUrl + entry.url)
                    }
                }

            MangasPage(mangas, hasNextPage = false)
        } else {
            parseComicListPage(res.asJsoup())
        }
    }

    // =============================== Details ==============================

    override fun mangaDetailsParse(response: Response): SManga = response.use { res ->
        val document = res.asJsoup()

        SManga.create().apply {
            title = document.selectFirst("h1 a[href^=/comic/]")?.text()
                ?: throw IOException("Nao foi possivel encontrar o titulo da obra")
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
            author = document.selectFirst(".bi-brush + a")?.text()
            artist = author
            description = document.selectFirst(".comic-description-large > p")?.text()
                ?: document.selectFirst("meta[property=og:description]")?.attr("content")
            genre = buildGenre(document)
            status = SManga.UNKNOWN
            initialized = true
        }
    }

    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url

    // ============================== Chapters ==============================

    override fun chapterListRequest(manga: SManga): Request {
        val url = "$baseUrl${manga.url}/chapters".toHttpUrl().newBuilder()
            .addQueryParameter("order", CHAPTER_ORDER)
            .build()

        return GET(url, ajaxHeaders(manga.url))
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val refererPath = response.request.url.encodedPath.removeSuffix("/chapters")
        val chapters = mutableListOf<SChapter>()

        var nextUrl = response.use { res ->
            val page = parseChapterPage(res.asJsoup())
            chapters += page.chapters
            page.nextUrl
        }

        while (nextUrl != null) {
            nextUrl = client.newCall(GET(nextUrl, ajaxHeaders(refererPath))).execute().use { res ->
                val page = parseChapterPage(res.asJsoup())
                chapters += page.chapters
                page.nextUrl
            }
        }

        return chapters.distinctBy { it.url }.also {
            if (it.isEmpty()) {
                throw IOException("Nenhum capitulo encontrado para esta obra")
            }
        }
    }

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    // ================================ Pages ===============================

    override fun pageListRequest(chapter: SChapter): Request = GET(baseUrl + chapter.url, headers).also {
        readerDebug("request url=${it.url}")
    }

    override fun pageListParse(response: Response): List<Page> = response.use { res ->
        val chapterUrl = res.request.url.toString()
        val body = res.body.string()
        readerDebug(
            "response HTTP=${res.code} finalUrl=${res.request.url} " +
                "contentType=${res.header("Content-Type")} bodyLength=${body.length}",
        )

        val document = Jsoup.parse(body, chapterUrl)
        val comicPageImages = document.select(".comic-page-image")
        val comicPageImagesLegacy = document.select(".comic_page_image")
        val pageContainers = document.select(".comic-page-image, .comic_page_image")
        readerDebug(
            "selectors comicPageImage=${comicPageImages.size} " +
                "comic_page_image=${comicPageImagesLegacy.size} imgSrc=${document.select("img[src]").size}",
        )

        pageContainers.take(DEBUG_ELEMENT_LIMIT)
            .forEach { element ->
                val image = element.selectFirst("img")
                readerDebug(
                    "element tag=${element.tagName()} classes=${element.classNames().joinToString(",")} " +
                        "img=${if (image != null) "SIM" else "NÃO"} " +
                        "src=${if (image?.hasAttr("src") == true) "SIM" else "NÃO"} " +
                        "dataSrc=${if (image?.hasAttr("data-src") == true) "SIM" else "NÃO"} " +
                        "srcset=${if (image?.hasAttr("srcset") == true) "SIM" else "NÃO"} " +
                        "styleBackground=${if (element.attr("style").contains("background-image", true)) "SIM" else "NÃO"}",
                )
            }

        val urls = pageContainers
            .mapNotNull { container ->
                container.selectFirst("img[src]")?.absUrl("src")
                    ?.takeIf { it.isNotEmpty() }
                    ?: container.selectFirst("img[data-src]")?.absUrl("data-src")?.takeIf { it.isNotEmpty() }
            }
            .distinct()

        val pages = urls.mapIndexed { index, url -> Page(index, chapterUrl, imageUrl = url) }

        pages.also {
            if (it.isEmpty()) {
                readerDebug(
                    "parsed=0 HTTP=${res.code} comicPageImage=${comicPageImages.size} " +
                        "comic_page_image=${comicPageImagesLegacy.size} chapterUrl=$chapterUrl",
                )
                throw IOException("Nenhuma pagina encontrada para este capitulo")
            }
        }
    }

    override fun imageRequest(page: Page): Request {
        val imageHeaders = headersBuilder()
            .set("Accept", "image/*")
            .set("Referer", page.url)
            .build()

        return GET(page.imageUrl!!, imageHeaders)
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================== Filters ===============================

    override fun getFilterList(): FilterList = FilterList(
        ListFilter(),
        CategoryFilter(),
        TagFilter(),
    )

    // ============================= Utilities ==============================

    private fun parseComicListPage(document: Document): MangasPage {
        val mangas = parseComicCards(document)
        val hasNextPage = document.selectFirst("#loadMoreComics button[data-url], #loadMoreComics a[data-url]") != null

        return MangasPage(mangas, hasNextPage)
    }

    private fun parseComicCards(document: Document): List<SManga> = document.select("a.comic-card[data-url][data-title]").mapNotNull(::parseComicCard)

    private fun parseComicCard(element: Element): SManga? {
        val title = element.attr("data-title").ifEmpty { element.selectFirst("h1")?.text().orEmpty() }
        val url = element.attr("data-url").toComicPath(baseUrl) ?: return null
        if (title.isEmpty()) return null

        return SManga.create().apply {
            this.title = title
            thumbnail_url = element.attr("style").extractBackgroundUrl()
            setUrlWithoutDomain(baseUrl + url)
        }
    }

    private fun parseChapterPage(document: Document): ChapterPage {
        val chapters = document.select(".chapter-list-item a[href^=/comic/]").map { element ->
            SChapter.create().apply {
                name = element.text()
                chapter_number = name.substringBefore(" - ").toFloatOrNull() ?: -1f
                setUrlWithoutDomain(element.absUrl("href"))
            }
        }

        val nextUrl = document.selectFirst("#chapters-load-more a[data-url]")?.absUrl("data-url")
            ?.takeIf { it.isNotEmpty() }

        return ChapterPage(chapters, nextUrl)
    }

    private fun buildGenre(document: Document): String {
        val category = document.select("h2 a[href^=/category/]").map { it.text() }
        val tags = document.select(".comic-description-large a[href^=/tag/]").map { it.text().removePrefix("#") }

        return (category + tags).distinct().joinToString(", ")
    }

    private fun buildPagedUrl(path: String, page: Int): String = (baseUrl + path).toHttpUrl().newBuilder().apply {
        if (page > 1) {
            addQueryParameter("page", page.toString())
        }
    }.build().toString()

    private fun ajaxHeaders(refererPath: String): Headers {
        val referer = if (refererPath.startsWith("http")) refererPath else baseUrl + refererPath

        return headersBuilder()
            .set("Accept", "*/*")
            .set("Referer", referer)
            .add("X-Requested-With", "XMLHttpRequest")
            .build()
    }

    private fun String.extractBackgroundUrl(): String? {
        val url = BACKGROUND_IMAGE_REGEX.find(this)?.groupValues?.get(1) ?: return null

        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> baseUrl + url
            else -> null
        }
    }

    private fun readerDebug(message: String) = Log.d(DEBUG_TAG, "FLIPTRU_DEBUG READER $message")

    private class ChapterPage(
        val chapters: List<SChapter>,
        val nextUrl: String?,
    )

    @Serializable
    private class SearchResultDto(
        private val label: String,
        private val url: String,
    ) {
        fun toComicEntry(baseUrl: String): ComicEntry? {
            val path = url.toComicPath(baseUrl) ?: return null
            if (label.isEmpty()) return null

            return ComicEntry(label, path)
        }
    }

    private class ComicEntry(
        val title: String,
        val url: String,
    )

    private open class UriPartFilter(
        name: String,
        private val entries: Array<Pair<String, String>>,
        defaultValue: Int = 0,
    ) : Filter.Select<String>(name, entries.map { it.first }.toTypedArray(), defaultValue) {
        val selectedValue: String
            get() = entries[state].second
    }

    private class ListFilter : UriPartFilter("Lista", LISTS)

    private class CategoryFilter : UriPartFilter("Genero", CATEGORIES)

    private class TagFilter : UriPartFilter("Tag", TAGS)

    private companion object {
        const val DEBUG_TAG = "Fliptru"
        const val DEBUG_ELEMENT_LIMIT = 3
        const val POPULAR_PATH = "/home/comics/most_popular"
        const val RECENT_PATH = "/comics/all"
        const val SEARCH_PATH = "/search/"
        const val CHAPTER_ORDER = "desc"
        const val SEARCH_MIN_LENGTH = 3

        val BACKGROUND_IMAGE_REGEX = """background(?:-image)?:\s*url\(['"]?([^)'"]+)['"]?\)""".toRegex()

        val LISTS = arrayOf(
            "Mais populares" to POPULAR_PATH,
            "Mais recentes" to RECENT_PATH,
            "Mais vendidos" to "/home/comics/best_seller",
            "Promovidos" to "/promoted/",
            "Series" to "/type/series",
            "Series completas" to "/comics/series/completed",
            "One shots" to "/type/one_shot",
        )

        val CATEGORIES = arrayOf(
            "Todos os generos" to "",
            "Acao" to "acao",
            "Adulto" to "adulto",
            "Aventura" to "aventura",
            "Comedia" to "comedia",
            "Drama" to "drama",
            "Educacao" to "educacao",
            "Esportes" to "esportes",
            "Fantasia" to "fantasia",
            "Ficcao Cientifica" to "ficcao-cientifica",
            "LGBTQIA+" to "lgbtqia",
            "Misterio" to "misterio",
            "Nao-ficcao" to "nao-ficcao",
            "Policial" to "policial",
            "Romance" to "romance",
            "Slice of Life" to "slice-life",
            "Suspense" to "suspense",
            "Terror" to "terror",
            "Tirinhas" to "tirinhas",
        )

        val TAGS = arrayOf(
            "Todas as tags" to "",
            "Manga" to "manga",
            "Acao" to "acao",
            "Quadrinhos" to "quadrinhos",
            "Aventura" to "aventura",
            "Comedia" to "comedia",
            "Quadrinho" to "quadrinho",
        )
    }
}

private fun String.toComicPath(baseUrl: String): String? {
    val absoluteUrl = when {
        startsWith("http") -> this
        startsWith("/") -> baseUrl + this
        else -> return null
    }

    return absoluteUrl.toHttpUrl().encodedPath
        .removeSuffix("/info")
        .takeIf { it.startsWith("/comic/") }
}
