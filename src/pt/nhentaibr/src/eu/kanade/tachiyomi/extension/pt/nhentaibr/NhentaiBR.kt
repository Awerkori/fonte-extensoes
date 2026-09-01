package eu.kanade.tachiyomi.extension.pt.nhentaibr

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Element
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

@Source
abstract class NhentaiBR : KeiSource() {

    override val supportsLatest = true

    override fun OkHttpClient.Builder.configureClient() = this
        .rateLimit(1, 1.seconds) { !it.encodedPath.startsWith("/wp-content/uploads/") }

    override fun okhttp3.Headers.Builder.configureHeaders() = this
        .set("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .set("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")

    override suspend fun getPopularManga(page: Int): MangasPage = client.get(pageUrl("$baseUrl/popular/", page)).asJsoup().let(::parseMangas)
    override suspend fun getLatestUpdates(page: Int): MangasPage = client.get(pageUrl("$baseUrl/ultimos/", page)).asJsoup().let(::parseMangas)
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = client
        .get("$baseUrl/page/$page/".toHttpUrl().newBuilder().addQueryParameter("s", query).build())
        .asJsoup().let(::parseMangas)

    override suspend fun getMangaByUrl(url: okhttp3.HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        return parseMangaDetails(client.get(url).asJsoup(), url.encodedPath)
    }

    override suspend fun fetchMangaUpdate(manga: SManga, chapters: List<SChapter>, fetchDetails: Boolean, fetchChapters: Boolean) = client.get(getMangaUrl(manga)).asJsoup().let { document ->
        eu.kanade.tachiyomi.source.model.SMangaUpdate(
            if (fetchDetails) parseMangaDetails(document, manga.url) else manga,
            if (fetchChapters) parseChapterList(document, manga.url) else chapters,
        )
    }

    private fun parseMangaDetails(document: org.jsoup.nodes.Document, path: String): SManga {
        val box = document.selectFirst("div.container > div.post-box") ?: error("Required value was null.")
        val hasChapters = document.select("div.galeriaTabItem").isNotEmpty()
        return SManga.create().apply {
            setUrlWithoutDomain(path)
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

    private fun parseChapterList(document: org.jsoup.nodes.Document, path: String): List<SChapter> = document.select("div.galeriaTabItem").mapIndexed { index, element ->
        val gallery = element.selectFirst("div.galeriaConteudo[id~=^galeria-\\d+$]")
        val chapter = gallery?.selectFirst(".galeriaTabCapitulo")?.text().orEmpty()
        val title = gallery?.selectFirst(".galeriaTabTitulo")?.text().orEmpty()
        SChapter.create().apply {
            url = gallery?.id()?.let { "$path#$it" } ?: path
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
                url = path
                name = "Capítulo único"
                chapter_number = 1f
            },
        )
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val url = getChapterUrl(chapter).toHttpUrl()
        val document = client.get(url).asJsoup()
        val fragment = url.fragment
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

    // Bare paginated paths self-redirect on the site (301 Location: same
    // URL), exhausting OkHttp's follow-up limit. The page query preserves the
    // real pagination while making the server return the page directly.
    private fun pageUrl(url: String, page: Int) = if (page > 1) "$url/page/$page/?page=$page" else url

    private fun String.toHttpUrlOrNull() = runCatching { toHttpUrl() }.getOrNull()

    override fun getFilterList(data: JsonElement?): FilterList = FilterList()

    private companion object {
        val IMAGE_SIZE = Regex("-\\d+x\\d+(?=\\.(?:jpe?g|png|webp|avif)$)")
        val NUMBER = Regex("(\\d+(?:\\.\\d+)?)")
    }
}
