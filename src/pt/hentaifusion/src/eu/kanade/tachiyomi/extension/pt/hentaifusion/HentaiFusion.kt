package eu.kanade.tachiyomi.extension.pt.hentaifusion

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.Instant

@Source
abstract class HentaiFusion : KeiSource() {

    override val supportsFilterFetching = true

    override suspend fun getPopularManga(page: Int): MangasPage = fetchListing(baseUrl, page)

    override suspend fun getLatestUpdates(page: Int): MangasPage = fetchListing(baseUrl, page)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val filterValues = filters.filterValues()
        val url = when {
            filterValues.isNotEmpty() -> "$baseUrl/filtro".toHttpUrl().newBuilder().apply {
                filterValues.forEach { (name, value) -> addQueryParameter(name, value) }
                if (query.isNotEmpty()) addQueryParameter("s", query)
                if (page > 1) addQueryParameter("paged", page.toString())
            }.build().toString()
            query.isNotEmpty() -> if (page == 1) "$baseUrl/?s=${query.urlEncoded()}" else "$baseUrl/page/$page/?s=${query.urlEncoded()}"
            else -> return getLatestUpdates(page)
        }
        return parseListing(client.get(url).asJsoup())
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.encodedPath == "/") return null
        val manga = SManga.create().apply { this.url = url.encodedPath }
        return fetchMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        val updatedManga = if (fetchDetails) parseDetails(document, manga) else manga
        val updatedChapters = if (fetchChapters) listOf(parseChapter(document, manga.url)) else chapters
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = client.get(getChapterUrl(chapter)).asJsoup()
        .select("ul.post-fotos > li > img")
        .mapNotNull { it.imageUrl() }
        .distinct()
        .mapIndexed { index, url -> Page(index, imageUrl = url) }

    override suspend fun fetchFilterData(): JsonElement = client.get(baseUrl).asJsoup()
        .select("form[action$=/filtro] select[name]")
        .map { select ->
            FilterGroup(
                select.attr("name"),
                select.select("option[value]").mapNotNull { option ->
                    option.attr("value").takeIf { it.isNotEmpty() }?.let { FilterOption(option.text(), it) }
                },
            )
        }
        .filter { it.options.isNotEmpty() }
        .toJsonElement()

    override fun getFilterList(data: JsonElement?): FilterList {
        val groups = data?.parseAs<List<FilterGroup>>().orEmpty()
        return FilterList(groups.map { SiteFilter(it.name, it.options) })
    }

    private suspend fun fetchListing(listingUrl: String, page: Int): MangasPage = parseListing(
        client.get(if (page == 1) listingUrl else "${listingUrl.trimEnd('/')}/page/$page/").asJsoup(),
    )

    private fun parseListing(document: Document): MangasPage {
        val mangas = document.select(".lista > ul > li > .thumb-conteudo")
            .mapNotNull { card ->
                val link = card.selectFirst("a[href]") ?: return@mapNotNull null
                val url = link.absUrl("href").takeIf { it.startsWith(baseUrl) } ?: return@mapNotNull null
                SManga.create().apply {
                    this.url = url.toHttpUrl().encodedPath
                    title = link.selectFirst(".thumb-titulo")?.text() ?: link.attr("title")
                    thumbnail_url = card.selectFirst("img")?.imageUrl()
                }
            }
        return MangasPage(mangas, document.selectFirst("ul.paginacao li.next a") != null)
    }

    private fun parseDetails(document: Document, manga: SManga) = SManga.create().apply {
        url = manga.url
        title = document.selectFirst(".post-conteudo .post-titulo")?.text() ?: manga.title
        thumbnail_url = document.selectFirst(".post-capa img")?.imageUrl() ?: manga.thumbnail_url
        description = document.selectFirst(".post-conteudo .post-texto")?.text()
        val metadata = document.select(".post-itens > li")
        val values = metadata.associate { item -> item.selectFirst("strong")?.text()?.removeSuffix(":").orEmpty() to item.select("a").map(Element::text) }
        genre = (
            (values["Categorias"] ?: emptyList()) +
                (values["Tags"] ?: emptyList()) +
                (values["Tipo"] ?: emptyList()) +
                (values["Cor"] ?: emptyList()) +
                (values["Paródia"] ?: emptyList())
            ).distinct().joinToString()
        artist = values["Artista"]?.joinToString()
        author = values["Tradutor"]?.joinToString()
        status = SManga.COMPLETED
        initialized = true
    }

    private fun parseChapter(document: Document, mangaUrl: String) = SChapter.create().apply {
        url = mangaUrl
        name = "Capítulo"
        chapter_number = 1F
        date_upload = document.selectFirst("meta[property=article:published_time]")?.attr("content")?.let(Instant::parse)?.toEpochMilli() ?: 0L
    }

    private fun Element.imageUrl(): String? = sequenceOf("data-src", "data-lazy-src", "src")
        .map { absUrl(it) }
        .firstOrNull { it.isNotEmpty() }

    private fun String.urlEncoded() = java.net.URLEncoder.encode(this, Charsets.UTF_8.name())
}

@Serializable
private class FilterOption(val name: String, val value: String)

@Serializable
private class FilterGroup(val name: String, val options: List<FilterOption>)

private class SiteFilter(name: String, private val options: List<FilterOption>) :
    Filter.Select<String>(
        name.replaceFirstChar(Char::uppercase),
        arrayOf("Todos") + options.map(FilterOption::name).toTypedArray(),
    ) {
    fun selectedValue() = options.getOrNull(state - 1)?.value.orEmpty()
    val parameter = name
}

private fun FilterList.filterValues() = filterIsInstance<SiteFilter>()
    .mapNotNull { filter -> filter.selectedValue().takeIf { it.isNotEmpty() }?.let { filter.parameter to it } }
