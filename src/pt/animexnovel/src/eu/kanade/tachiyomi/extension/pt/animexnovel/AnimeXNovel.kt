package eu.kanade.tachiyomi.extension.pt.animexnovel

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.parseAs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.collections.map
import kotlin.collections.plusAssign
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Source
abstract class AnimeXNovel : KeiSource() {

    override val supportsLatest: Boolean = true

    override fun OkHttpClient.Builder.configureClient() = this
        .readTimeout(1.minutes)
        .callTimeout(1.minutes)
        .rateLimit(3, 1.seconds)

    // ========================== Popular ===================================

    private val popularFilter = FilterList(
        listOf(
            BoxList("", setOf("Mangá", "Manhwa", "Manhua").map { BoxValue("", it) }).apply {
                state.forEach { it.state = true }
            },
        ),
    )

    override suspend fun getPopularManga(page: Int): MangasPage = getSearchMangaList(page, "", popularFilter)

    // ========================== Latest ====================================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val mangas = client.get(baseUrl).asJsoup()
            .select("div:contains(Últimos Mangás) + .axn-piz-container .axn-piz-card")
            .map(::mangaFromElement)
        return MangasPage(mangas, hasNextPage = false)
    }

    // ========================== Search ====================================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val form = FormBody.Builder()
            .add("action", "axn_filter_obras")
            .add("posts_per_page", "21")
            .add("search", query)
            .add("paged", page.toString())
            .apply {
                filters.filterIsInstance<BoxList>()
                    .also { filterList ->
                        filterList.find { it.name.contains("ordem", ignoreCase = true) }
                            ?.state?.find(CheckBox::state)?.let {
                                if (it.id.isBlank()) return@let
                                add("letra", it.id)
                            }
                    }
                    .filterNot { it.name.contains("ordem", ignoreCase = true) }
                    .flatMap(BoxList::state)
                    .filter { it.state && it.id.isNotBlank() }
                    .forEach { filter -> add("terms[]", filter.id) }
            }
            .build()
        return client.post("$baseUrl/wp-admin/admin-ajax.php", form).asJsoup().let(::searchMangaParse)
    }

    private var lastManga: SManga? = null

    private fun searchMangaParse(document: Document): MangasPage {
        val mangas = document.select("a.axn-card").map(::mangaFromElement).toMutableList()
        val hasNextPage = mangas.isNotEmpty() && mangas.size > 1

        when {
            hasNextPage -> {
                lastManga = mangas.removeAt(mangas.lastIndex)
            }
            else -> {
                lastManga?.let { mangas += it }
            }
        }

        return MangasPage(mangas, hasNextPage = hasNextPage)
    }

    // ========================== Details ===================================

    override suspend fun getMangaByUrl(url: okhttp3.HttpUrl): SManga = parseMangaDetails(client.get(url).asJsoup(), url.toString())

    private fun parseMangaDetails(document: Document, url: String) = SManga.create().apply {
        title = document.selectFirst("h1")!!.text()
        thumbnail_url = document.selectFirst("meta[itemprop=image]")?.absUrl("content")
        author = document.selectFirst("li:contains(Autor:)")?.text()?.substringAfter(":")?.trim()
        artist = document.selectFirst("li:contains(Arte:)")?.text()?.substringAfter(":")?.trim()
        genre = document.selectFirst("meta[itemprop=genre]")?.attr("content")
        description = extractDescription(document)
            ?: document.selectFirst("meta[itemprop=description]")?.attr("content")
        document.selectFirst("meta[itemprop=creativeWorkStatus]")?.attr("content")?.let {
            status = when (it.lowercase()) {
                "ongoing" -> SManga.ONGOING
                "completed" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }

        setUrlWithoutDomain(url)
    }

    // ========================== Chapters ==================================

    override suspend fun fetchMangaUpdate(manga: SManga, chapters: List<SChapter>, fetchDetails: Boolean, fetchChapters: Boolean) = client.get(getMangaUrl(manga)).asJsoup().let { document ->
        eu.kanade.tachiyomi.source.model.SMangaUpdate(
            if (fetchDetails) parseMangaDetails(document, getMangaUrl(manga)) else manga,
            if (fetchChapters) fetchChapterList(document) else chapters,
        )
    }

    private suspend fun fetchChapterList(document: Document): List<SChapter> {
        val category = document.selectFirst("[id^=axn-list-][data-categoria]")
            ?.attr("data-categoria") ?: return emptyList()
        val url = "$baseUrl/wp-json/wp/v2/posts".toHttpUrl().newBuilder()
            .addQueryParameter("categories", category)
            .addQueryParameter("orderby", "date")
            .addQueryParameter("order", "desc")
            .addQueryParameter("per_page", "100")
        val chapters = mutableListOf<SChapter>()
        var page = 1
        while (true) {
            url.setQueryParameter("page", page.toString())
            val response = client.get(url.build(), ensureSuccess = false)
            if (!response.isSuccessful) break
            chapters += chapterListParse(response)
            page++
        }
        return chapters
    }

    private fun chapterListParse(response: okhttp3.Response): List<SChapter> = response.parseAs<List<ChapterDto>>()
        .map(ChapterDto::toSChapter)
        .onEach { chapter -> chapter.setUrlWithoutDomain(chapter.url) }
        .filter { it.url.contains("capitulo") }

    // ========================== Pages =====================================

    private val pageContainerSelector = ".spice-block-img-gallery, .wp-block-gallery, .spnc-entry-content"

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()
        val images = document.selectFirst(pageContainerSelector)?.select("img")
            ?: document.select("article .entry-content img[src*=/wp-content/uploads/]")
        return images.mapIndexed { index, element ->
            Page(index, imageUrl = element.absUrl("src"))
        }
    }

    // =========================== Filters ==================================

    private class BoxList(title: String, values: List<BoxValue>) : Filter.Group<CheckBox>(title, values.map { CheckBox(it.name, it.id) })

    private class CheckBox(name: String, val id: String = name) : Filter.CheckBox(name)

    private class BoxValue(val name: String, val id: String = name)

    private var options: List<Pair<String, List<BoxValue>>> = emptyList()

    private val scope = CoroutineScope(Dispatchers.IO)

    private var fetchFiltersAttempts: Int = 0

    private fun fetchFilters() {
        if (fetchFiltersAttempts < 3 && options.isEmpty()) {
            try {
                options = client.newCall(filterRequest()).execute()
                    .use { parseOptions(it.asJsoup()) }
            } catch (_: Exception) {
            } finally {
                fetchFiltersAttempts++
            }
        }
    }

    private fun filterRequest(): Request = Request.Builder().url("$baseUrl/pesquisar").headers(headers).get().build()

    private fun parseOptions(document: Document): List<Pair<String, List<BoxValue>>> {
        val filtersSelectors = setOf(
            "grp-alfabeto",
            "grp-demografia",
            "grp-classificacao",
            "grp-categorias",
            "grp-tags",
        )
        return filtersSelectors.mapNotNull { selector ->
            val title = document.selectFirst(".filter-section-title:has( + #$selector)")?.text()
                ?: return@mapNotNull null
            title to document.select("#$selector .axn-chip").map { element ->
                BoxValue(element.text(), element.attr("data-value"))
            }
        }
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        scope.launch { fetchFilters() }

        val filters: MutableList<Filter<out Any>> = mutableListOf()

        if (options.isNotEmpty()) {
            options.forEachIndexed { index, (title, values) ->
                if (index != 0) {
                    filters += Filter.Separator()
                }
                filters += BoxList(title, values)
            }
        } else {
            filters += listOf(
                Filter.Separator(),
                Filter.Header("Aperte 'Redefinir' para tentar mostrar os filtros"),
            )
        }
        return FilterList(filters)
    }

    // =========================== Utils ====================================

    private fun extractDescription(document: Document): String? {
        val synopsisHeading = document.select("h1, h2, h3, h4, h5, h6")
            .firstOrNull { it.text().trim().equals("Ler Sinopse", true) }
            ?: return null
        val paragraphs = synopsisHeading.parents()
            .asSequence()
            .mapNotNull { ancestor ->
                ancestor.select(".eb-accordion-content p.wp-block-paragraph")
                    .takeIf { it.isNotEmpty() }
                    ?: ancestor.select(".wp-block-accordion-panel p.wp-block-paragraph")
                        .takeIf { it.isNotEmpty() }
            }
            .firstOrNull()
            ?.map { it.text().trim() }
            ?.filter(String::isNotBlank)
            .orEmpty()
        return paragraphs.joinToString("\n\n").takeIf(String::isNotBlank)
    }

    private fun mangaFromElement(element: Element): SManga = SManga.create().apply {
        title = element.selectFirst("h2, h3, .search-content")!!.text()
        thumbnail_url = element.selectFirst("img")?.absUrl("src")
        setUrlWithoutDomain(element.selectFirst("a")!!.absUrl("href"))
    }
}
