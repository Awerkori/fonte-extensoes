package eu.kanade.tachiyomi.multisrc.aurora

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.addCookie
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.parseAs
import keiyoushi.utils.string
import kotlinx.serialization.json.JsonObject
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.nodes.Document
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Aurora : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(3, 1.seconds)
        .addCookie("mnx_adulto" to "1")

    override fun Headers.Builder.configureHeaders() = set("Sec-Fetch-Dest", "document")
        .set("Sec-Fetch-Mode", "navigate")
        .set("Sec-Fetch-Site", "none")
        .set("Sec-Fetch-User", "?1")
        .set("Alt-Used", baseUrl.substringAfterLast("/"))

    override suspend fun getPopularManga(page: Int) = getMangasPage(client.get("$baseUrl/catalogo"))

    override suspend fun getLatestUpdates(page: Int) = getMangasPage(client.get("$baseUrl/novidades"))

    private fun getMangasPage(response: Response): MangasPage {
        val dto = response.extractNextJs<SeriesDto>()
        return MangasPage(dto?.toSMangaList() ?: emptyList(), false)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val mangas = getPopularManga(page).mangas.filter { it.title.contains(query, ignoreCase = true) }
        return MangasPage(mangas, false)
    }

    override fun getMangaUrl(manga: SManga): String = entryURL(manga.memo)

    override fun getChapterUrl(chapter: SChapter): String {
        val number = chapter.memo["number"]?.string ?: chapter.chapter_number.toString().removeSuffix(".0")
        return "${entryURL(chapter.memo)}/$number"
    }

    private fun entryURL(memo: JsonObject): String = "$baseUrl/${memo["type"]!!.string}/${memo["slug"]!!.string}"

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()

        val manga = getSMangaDetails(document, manga)
        val chapters = document.extractNextJs<ChapterListDto>()
            ?.toSChapterList(manga)
            ?: emptyList()

        return SMangaUpdate(manga, chapters)
    }

    private val mangaDetailsDescriptionRegex = """description":"([^"]+)""".toRegex()
    private val mangaDetailsGenreRegex = """genre":([^]]+])""".toRegex()
    private val mangaDetailsAuthorRegex = """author[^.]+name":"([^"]+)""".toRegex()
    private val cleanRegex = """\\{2,}""".toRegex()

    private fun getSMangaDetails(
        document: Document,
        manga: SManga,
    ): SManga = document.selectFirst("script[type]:containsData(ComicSeries)")?.data()
        ?.parseAs<MangaDto>()
        ?.toSManga()
        ?: manga.apply {
            document.selectFirst("script:containsData(ComicSeries)")?.data()
                ?.replace(cleanRegex, "")
                ?.let {
                    description = mangaDetailsDescriptionRegex.find(it)?.groupValues?.last()
                    genre = mangaDetailsGenreRegex.find(it)?.groupValues?.last()?.parseAs<List<String>>()?.joinToString()
                    author = mangaDetailsAuthorRegex.find(it)?.groupValues?.last()
                }
        }

    private val gateTiming = GateTiming()

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = getChapterUrl(chapter)
        val counts = linkedMapOf<String, Int>()
        var status = 0
        fun read(response: Response): ReaderPayload = response.use {
            status = it.code
            extractReader(it.body.string(), it.header("Content-Type").orEmpty(), chapterUrl).also { payload ->
                counts[payload.format] = payload.urls.size
            }
        }

        var payload = read(client.get(chapterUrl, readerHeaders(chapter), ensureSuccess = false))
        if (payload.gated) {
            val response = unlockReader(client, chapterUrl, readerHeaders(chapter, rsc = false), gateTiming)
            counts["gate"] = 1
            payload = read(response)
        }
        if (payload.urls.isEmpty() && !payload.gated && status in 200..299) {
            payload = read(client.get(chapterUrl, readerHeaders(chapter, rsc = false), ensureSuccess = false))
        }
        if (payload.urls.isEmpty()) readerFailure(name, chapterUrl, status, counts)

        val urls = try {
            decodePages(payload.urls, chapterUrl, ::getKey)
        } catch (_: IllegalArgumentException) {
            readerFailure(name, chapterUrl, status, counts + ("decrypt" to 0))
        }
        if (urls.isEmpty()) readerFailure(name, chapterUrl, status, counts + ("urls" to 0))
        return urls.mapIndexed { index, url -> Page(index, imageUrl = url) }
    }

    private fun readerHeaders(chapter: SChapter, rsc: Boolean = true): Headers {
        val builder = headersBuilder()
            .set("Referer", entryURL(chapter.memo))
        if (rsc) {
            builder
                .set("rsc", "1")
                .set("Sec-Fetch-Mode", "cors")
                .set("Sec-Fetch-Dest", "empty")
                .set("Sec-Fetch-Site", "same-origin")
                .set("next-url", entryURL(chapter.memo).toHttpUrl().encodedPath)
                .set("Accept", "*/*")
        }
        return builder.build()
    }

    private suspend fun getKey(params: Pair<Int, Long>): String {
        val (v, e) = params
        return client.get("$baseUrl/api/atfield/key?v=$v&e=$e").parseAs<KeyDto>().k
    }
}
