package eu.kanade.tachiyomi.extension.pt.terceiroz

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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Locale

@Source
abstract class TerceiroZ : HttpSource() {
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Accept", "text/html,application/xhtml+xml")

    override fun popularMangaRequest(page: Int): Request = GET(
        catalogUrl(page).newBuilder()
            .addQueryParameter("orderby", "meta_value_num")
            .addQueryParameter("meta_key", "views")
            .build(),
        headers,
    )

    override fun popularMangaParse(response: Response) = response.asJsoup().toMangasPage(sortByViews = true)

    override fun latestUpdatesRequest(page: Int): Request = GET(catalogUrl(page), headers)
    override fun latestUpdatesParse(response: Response) = response.asJsoup().toMangasPage()

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addQueryParameter("s", query)
            .addQueryParameter("paged", page.toString())
        filters.filterIsInstance<CategoryFilter>().firstOrNull()?.value()?.let { url.addQueryParameter("category_name", it) }
        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response) = response.asJsoup().toMangasPage()

    override fun mangaDetailsRequest(manga: SManga) = GET(manga.url.toHttpUrl(), headers)
    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()
        val title = doc.selectFirst(".post-conteudo > h1, .post-texto > h1")?.text()?.trim()
            ?: doc.selectFirst("h1")?.text().orEmpty()
        val cover = doc.selectFirst(".post-texto img[src*='/wp-content/uploads/']")?.absUrl("src")
        val description = doc.select(".post-texto p")
            .map { it.text().trim() }
            .firstOrNull { it.length > 30 && !it.contains("Ver mais", true) }
        return SManga.create().apply {
            this.title = title
            thumbnail_url = cover
            this.description = description
            genre = doc.select(".post-tags a").joinToString { it.text().trim() }.ifBlank { null }
            status = SManga.COMPLETED
            initialized = true
        }
    }

    override fun chapterListRequest(manga: SManga) = mangaDetailsRequest(manga)
    override fun chapterListParse(response: Response) = listOf(
        SChapter.create().apply {
            url = response.request.url.toString()
            name = "Leitura completa"
            chapter_number = 1f
        },
    )

    override fun pageListRequest(chapter: SChapter) = GET(chapter.url.toHttpUrl(), headers)
    override fun pageListParse(response: Response): List<Page> {
        val doc = response.asJsoup()
        val images = doc.select(".post-info img").mapNotNull { image ->
            val source = sequenceOf("data-src", "data-lazy-src", "data-original", "src")
                .map { image.attr(it) }.firstOrNull { it.isNotBlank() && !it.startsWith("data:image") }
                ?: image.attr("srcset").substringBefore(',').substringBefore(' ').takeIf { it.isNotBlank() }
            val url = source?.let { raw ->
                raw.replace("&amp;", "&").let {
                    when {
                        it.startsWith("//") -> "https:$it"
                        it.startsWith("/") -> baseUrl + it
                        else -> it
                    }
                }
            } ?: return@mapNotNull null
            if (!url.contains("/wp-content/uploads/")) return@mapNotNull null
            val alt = image.attr("alt")
            val file = url.substringAfterLast('/').lowercase(Locale.ROOT)
            val isPage = alt.contains("página", true) || alt.contains("pagina", true) ||
                file.matches(Regex("(?:^|[-_])\\d{1,3}(?:[-_.]|$).+"))
            val isPromo = file in PROMO_FILES || file.contains("banner") || file.contains("veja-completo") || file.contains("verfilme")
            if (!isPage || isPromo) null else url
        }
        if (images.isEmpty()) throw IllegalStateException("Nenhuma página da HQ foi encontrada.")
        return images.mapIndexed { index, image -> Page(index, response.request.url.toString(), image) }
    }

    override fun imageRequest(page: Page): Request = GET(
        page.imageUrl!!,
        headers.newBuilder().set("Referer", page.url).build(),
    )

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()
    override fun getFilterList(): FilterList = FilterList(
        CategoryFilter(
            listOf(
                "Todas" to "",
                "Cartoon Pornô" to "cartoon-porno",
                "Comics Porno" to "comics",
                "Quadrinhos Pornô" to "quadrinhos-porno",
                "Hentai" to "categorias-hentai",
                "HQ de Sexo" to "super-hq-de-sexo",
                "Mangás Hentai" to "mangas-hentai",
                "Simpsons Pornô" to "simpsons-porno",
                "Traduções Exclusivas" to "traducoes-exclusivas",
            ),
        ),
    )

    private fun catalogUrl(page: Int): okhttp3.HttpUrl = if (page == 1) baseUrl.toHttpUrl() else "$baseUrl/page/$page/".toHttpUrl()

    private fun Document.toMangasPage(sortByViews: Boolean = false): MangasPage {
        val cards = select("ul.videos li .video-conteudo").let { elements ->
            if (sortByViews) {
                elements.sortedByDescending { card ->
                    card.selectFirst(".views")?.text()?.filter { it.isDigit() }?.toLongOrNull() ?: 0L
                }
            } else {
                elements
            }
        }
        val items = cards.mapNotNull { it.toManga() }.filterNot { it.title.isPromo() }.distinctBy { it.url }
        val hasNext = selectFirst("ul.paginacao a.next") != null
        return MangasPage(items, hasNext)
    }

    private fun Element.toManga(): SManga? {
        val link = selectFirst("a.titulo[href], .thumb-conteudo a[href]") ?: return null
        val url = link.absUrl("href")
        if (!url.startsWith(baseUrl) || link.attr("rel").contains("nofollow")) return null
        val title = selectFirst("a.titulo h2")?.text()?.trim().orEmpty()
        if (title.isBlank()) return null
        return SManga.create().apply {
            this.url = url
            this.title = title
            thumbnail_url = selectFirst("img.thumb")?.absUrl("src")
        }
    }

    private class CategoryFilter(options: List<Pair<String, String>>) : Filter.Select<String>("Categoria", options.map { it.first }.toTypedArray()) {
        private val categoryValues = options.map { it.second }
        fun value(): String? = categoryValues.getOrNull(state)?.takeIf { it.isNotBlank() }
    }

    private fun String.isPromo() = lowercase(Locale.ROOT).let { it.contains("acesso vitalício") || it.contains("acesso vitalicio") || it.contains("assine") || it.contains("compre") }

    private companion object {
        val PROMO_FILES = setOf("quer.png", "verfilme.png", "veja-completo.png")
    }
}
