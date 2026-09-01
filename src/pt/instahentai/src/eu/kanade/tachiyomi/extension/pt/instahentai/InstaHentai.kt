package eu.kanade.tachiyomi.extension.pt.instahentai

import android.util.Log
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.nodes.Document

@Source
abstract class InstaHentai : KeiSource() {

    override val supportsLatest = true

    override suspend fun getPopularManga(page: Int): MangasPage = client.get("$baseUrl/melhores/page/$page/").asJsoup().toMangasPage()

    override suspend fun getLatestUpdates(page: Int): MangasPage = client.get(if (page == 1) baseUrl else "$baseUrl/page/$page/").asJsoup().toMangasPage()

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = client
        .get("$baseUrl/page/$page/?s=${java.net.URLEncoder.encode(query, Charsets.UTF_8.name())}")
        .asJsoup().toMangasPage()

    override suspend fun getMangaByUrl(url: okhttp3.HttpUrl): SManga = parseMangaDetails(client.get(url).asJsoup()).apply {
        setUrlWithoutDomain(url.toString())
    }

    override suspend fun fetchMangaUpdate(manga: SManga, chapters: List<SChapter>, fetchDetails: Boolean, fetchChapters: Boolean) = client.get(getMangaUrl(manga)).asJsoup().let { document ->
        eu.kanade.tachiyomi.source.model.SMangaUpdate(
            if (fetchDetails) parseMangaDetails(document).apply { url = manga.url } else manga,
            if (fetchChapters) parseChapterList(document) else chapters,
        )
    }

    private fun parseMangaDetails(document: Document): SManga {
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

    private fun parseChapterList(document: Document): List<SChapter> = document
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

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val echoUrl = getChapterUrl(chapter).toHttpUrl().newBuilder()
            .setQueryParameter("echo", "true")
            .build()
        val response = client.get(echoUrl)
        Log.d(DEBUG_TAG, "stage=ECHO_RESPONSE status=${response.code} contentType=${response.header("Content-Type")}")
        val document = response.asJsoup()
        val echoUrlString = echoUrl.toString()
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
        return images.mapIndexed { index, image -> Page(index, url = echoUrlString, imageUrl = image) }
    }

    override fun imageRequest(page: Page): Request = Request.Builder()
        .url(page.imageUrl!!).headers(headers.newBuilder().set("Referer", page.url).build()).get().build()

    override fun getFilterList(data: JsonElement?): FilterList = FilterList()

    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url

    override fun getChapterUrl(chapter: SChapter): String = chapter.url.absoluteUrl(baseUrl)

    private fun Document.toMangasPage(): MangasPage = MangasPage(parseMangas(this, baseUrl), false)

    private companion object {
        const val DEBUG_TAG = "INSTAHENTAI_DEBUG"
    }
}
