package eu.kanade.tachiyomi.extension.pt.nhentaibr

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
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
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

@Source
abstract class NhentaiBR : HttpSource() {

    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .rateLimit(1, 1.seconds) { !it.encodedPath.startsWith("/wp-content/uploads/") }
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)

    override fun popularMangaRequest(page: Int): Request = GET(pageUrl("$baseUrl/popular/", page), headers)

    override fun popularMangaParse(response: Response): MangasPage = parseMangas(response.asJsoup())

    override fun latestUpdatesRequest(page: Int): Request = GET(pageUrl("$baseUrl/ultimos/", page), headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseMangas(response.asJsoup())

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = GET("$baseUrl/page/$page/".toHttpUrl().newBuilder().addQueryParameter("s", query).build(), headers)

    override fun searchMangaParse(response: Response): MangasPage = parseMangas(response.asJsoup())

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        val url = query.toHttpUrlOrNull()
        if (url != null) {
            if (url.host != baseUrl.toHttpUrl().host) return Observable.error(Exception("Unsupported url"))
            val slug = url.pathSegments.firstOrNull().orEmpty()
            return client.newCall(GET("$baseUrl/$slug", headers)).asObservableSuccess()
                .map { MangasPage(listOf(mangaDetailsParse(it)), false) }
        }
        return super.fetchSearchManga(page, query, filters)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val box = document.selectFirst("div.container > div.post-box") ?: error("Required value was null.")
        val hasChapters = document.select("div.galeriaTabItem").isNotEmpty()
        return SManga.create().apply {
            setUrlWithoutDomain(response.request.url.encodedPath)
            title = box.selectFirst("h1.post-titulo")!!.text()
            thumbnail_url = box.selectFirst("div.post-capa > img.wp-post-image")?.imageUrl()?.replace(IMAGE_SIZE, "")
            author = metadata(box, "Artista")
            genre = box.select("ul.post-itens > li").filter {
                metadataLabel(it) in setOf("Categorias", "Tags")
            }.flatMap { it.select("a").map(Element::text) }.distinct().joinToString()
            description = listOf("Cor", "Paródia", "Páginas").mapNotNull { label ->
                metadata(box, label)?.let { "$label: $it" }
            }.joinToString("\n").takeIf(String::isNotEmpty)
            status = SManga.COMPLETED
            update_strategy = if (!hasChapters) {
                UpdateStrategy.ONLY_FETCH_ONCE
            } else {
                UpdateStrategy.ALWAYS_UPDATE
            }
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select("div.galeriaTabItem").mapIndexed { index, element ->
            val gallery = element.selectFirst("div.galeriaConteudo[id~=^galeria-\\d+$]")
            val chapter = gallery?.selectFirst(".galeriaTabCapitulo")?.text().orEmpty()
            val title = gallery?.selectFirst(".galeriaTabTitulo")?.text().orEmpty()
            SChapter.create().apply {
                url = gallery?.id()?.let { "${response.request.url.encodedPath}#$it" } ?: response.request.url.encodedPath
                name = when {
                    chapter.isEmpty() && title.isEmpty() -> "Capítulo"
                    chapter.isEmpty() -> title
                    title.isEmpty() -> chapter
                    else -> "$chapter: $title"
                }
                chapter_number = NUMBER.find(chapter)?.groupValues?.get(1)?.toFloatOrNull() ?: index + 1f
                date_upload = document.selectFirst("meta[property=\"article:published_time\"]")
                    ?.attr("content")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
            }
        }.ifEmpty {
            listOf(
                SChapter.create().apply {
                    url = response.request.url.encodedPath
                    name = "Capítulo único"
                    chapter_number = 1f
                },
            )
        }
    }

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val fragment = response.request.url.fragment
        val selector = if (fragment.isNullOrEmpty()) {
            "div.post-box.listaImagens ul.post-fotos > li > a > img"
        } else {
            "div.galeriaConteudo#$fragment img"
        }
        return document.select(selector).mapIndexed { index, image ->
            Page(index, imageUrl = image.imageUrl())
        }.distinctBy { it.imageUrl }
    }

    private fun parseMangas(document: org.jsoup.nodes.Document): MangasPage {
        val mangas = document.select("div.lista > ul > li > div.thumb-conteudo > a:has(span.thumb-imagem)[href^=\"https://nhentai.net.br/\"]")
            .mapNotNull { element ->
                val href = element.absUrl("href").takeIf { it.startsWith("https://nhentai.net.br/") } ?: return@mapNotNull null
                val title = element.selectFirst("span.thumb-titulo")?.text()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
                SManga.create().apply {
                    setUrlWithoutDomain(href.toHttpUrl().encodedPath)
                    this.title = title
                    thumbnail_url = element.selectFirst("span.thumb-imagem img.wp-post-image")?.imageUrl()?.replace(IMAGE_SIZE, "")
                }
            }
        return MangasPage(mangas, document.selectFirst("ul.paginacao li.next > a[href]") != null)
    }

    private fun metadata(box: Element, label: String): String? = box.select("ul.post-itens > li").firstOrNull { metadataLabel(it) == label }
        ?.select("a")?.joinToString { it.text() }?.takeIf(String::isNotEmpty)

    private fun metadataLabel(element: Element): String = element.selectFirst("strong")?.text()?.trimEnd(':').orEmpty()

    private fun Element.imageUrl(): String = absUrl("data-src").ifEmpty { absUrl("src") }

    private fun pageUrl(url: String, page: Int) = if (page > 1) "$url/page/$page/" else url

    private fun String.toHttpUrlOrNull() = runCatching { toHttpUrl() }.getOrNull()

    override fun imageUrlParse(response: Response): String = response.request.url.toString()

    private companion object {
        val IMAGE_SIZE = Regex("-\\d+x\\d+(?=\\.(?:jpe?g|png|webp|avif)$)")
        val NUMBER = Regex("(\\d+(?:\\.\\d+)?)")
    }
}
