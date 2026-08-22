package eu.kanade.tachiyomi.extension.pt.instahentai

import android.util.Log
import eu.kanade.tachiyomi.network.GET
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

@Source
abstract class InstaHentai : HttpSource() {

    override val supportsLatest = true

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/melhores/page/$page/", headers)

    override fun popularMangaParse(response: Response): MangasPage = response.asJsoup().toMangasPage()

    override fun latestUpdatesRequest(page: Int): Request = if (page == 1) {
        GET(baseUrl, headers)
    } else {
        GET("$baseUrl/page/$page/", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = response.asJsoup().toMangasPage()

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = GET("$baseUrl/page/$page/?s=${java.net.URLEncoder.encode(query, Charsets.UTF_8.name())}", headers)

    override fun searchMangaParse(response: Response): MangasPage = response.asJsoup().toMangasPage()

    override fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val manga = SManga.create()
        manga.title = document.selectFirst("h1")?.text()?.trim().orEmpty()
        manga.thumbnail_url = document.selectFirst("img[itemprop=image]")?.absUrl("src")
        manga.author = document.selectFirst("a[href*=/autor/]")?.text()?.trim()
        manga.artist = document.selectFirst("a[href*=/artista/]")?.text()?.trim()
        manga.genre = document.select("a[href*=/genero/], a[href*=/categoria/]")
            .joinToString { it.text().trim() }.takeIf { it.isNotBlank() }
        manga.description = document.selectFirst(".description, .sinopse, [class*=sinopse]")?.text()?.trim()
            ?: document.select("p").map { it.text().trim() }.maxByOrNull { it.length }
        manga.status = SManga.UNKNOWN
        manga.initialized = true
        return manga
    }

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> = response.asJsoup()
        .select("a[href*=/ler/ler-]")
        .map { link ->
            SChapter.create().apply {
                url = link.attr("href")
                name = link.text().trim().ifBlank { "Capítulo ${parseChapterNumber(url)}" }
                chapter_number = parseChapterNumber(url)
                date_upload = link.parent()?.select("span")?.lastOrNull()?.text()?.let(::parseDate) ?: 0L
            }
        }
        .distinctBy { it.url }
        .sortedWith(compareByDescending<SChapter> { it.chapter_number }.thenByDescending { it.date_upload })

    override fun pageListRequest(chapter: SChapter): Request {
        val echoUrl = getChapterUrl(chapter).toHttpUrl().newBuilder()
            .setQueryParameter("echo", "true")
            .build()
        return GET(echoUrl, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val echoUrl = response.request.url.toString()
        Log.d(DEBUG_TAG, "stage=ECHO_RESPONSE status=${response.code} contentType=${response.header("Content-Type")}")
        val document = response.asJsoup()
        val images = document.select(".cap img[src*=/static/], .cap img[data-src*=/static/]")
            .map { it.attr("data-src").ifBlank { it.attr("src") }.absoluteUrl(baseUrl) }
            .filter { it.contains("cdn.instahentai.com/static/") }
            .distinct()
        Log.d(DEBUG_TAG, "stage=ECHO_PARSE pages=${images.size}")
        if (images.isEmpty()) throw IllegalStateException("Nenhuma página encontrada na resposta echo=true")
        images.forEachIndexed { index, image ->
            val url = image.toHttpUrl()
            Log.d(DEBUG_TAG, "stage=PAGE index=$index host=${url.host} path=${url.encodedPath}")
        }
        return images.mapIndexed { index, image -> Page(index, url = echoUrl, imageUrl = image) }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request = GET(
        page.imageUrl!!,
        headers.newBuilder().set("Referer", page.url).build(),
    )

    override fun getFilterList(): FilterList = FilterList()

    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url

    override fun getChapterUrl(chapter: SChapter): String = chapter.url.absoluteUrl(baseUrl)

    private fun Document.toMangasPage(): MangasPage = MangasPage(parseMangas(this, baseUrl), false)

    private companion object {
        const val DEBUG_TAG = "INSTAHENTAI_DEBUG"
    }
}
