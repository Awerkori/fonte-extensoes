package eu.kanade.tachiyomi.extension.pt.fenixproject

import android.util.Base64
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.annotation.Source
import keiyoushi.lib.cryptoaes.CryptoAES
import keiyoushi.network.rateLimit
import keiyoushi.utils.asJsoup
import keiyoushi.utils.decodeHex
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

@Source
abstract class FenixProject :
    Madara(),
    ConfigurableSource {
    private val preferences by getPreferencesLazy()

    private val showAdultContent get() = preferences.getBoolean(ADULT_CONTENT_PREF, false)

    override val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR"))
    override val client = super.client.newBuilder()
        .rateLimit(3)
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            if (request.url.host != baseUrl.toHttpUrl().host) {
                chain.proceed(request)
            } else {
                val cookies = request.header("Cookie").orEmpty()
                    .split(';')
                    .map(String::trim)
                    .filter { it.isNotEmpty() && !it.startsWith("$ADULT_CONTENT_COOKIE=") }
                val adultCookie = "$ADULT_CONTENT_COOKIE=${if (showAdultContent) "1" else "0"}"
                chain.proceed(
                    request.newBuilder()
                        .header("Cookie", (cookies + adultCookie).joinToString("; "))
                        .build(),
                )
            }
        }
        .build()

    override val useNewChapterEndpoint = true

    override val useLoadMoreRequest = LoadMoreStrategy.Never

    override val chapterUrlSuffix = ""

    private val chapterPreviews = ConcurrentHashMap<String, ChapterPreview>()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = ADULT_CONTENT_PREF
            title = "Mostrar conteúdo adulto"
            summary = "Inclui obras +18 nos populares, nas últimas atualizações e na busca."
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    override fun popularMangaRequest(page: Int): Request = GET(baseUrl, headers)

    override fun popularMangaParse(response: Response): MangasPage = MangasPage(
        response.asJsoup().select("section").firstOrNull {
            it.selectFirst("h2")?.text() == "Os Mais Lidos do Ninho"
        }?.select("ol > li")?.mapNotNull(::fenixMangaFromElement).orEmpty(),
        false,
    )

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/?pagina=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = fenixMangaPage(response.asJsoup())

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/pesquisar".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("pagina", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = fenixMangaPage(response.asJsoup())

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$baseUrl${manga.url}", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        cacheChapterPreview(document)
        return SManga.create().apply {
            title = document.selectFirst("#work-banner h1, h1")?.text()
                ?: throw IllegalStateException("Fenix manga title is missing")
            thumbnail_url = document.select("img[alt]")
                .firstOrNull { it.attr("alt") == title }
                ?.attr("abs:src")
            author = document.metadata("Autor")
            artist = document.metadata("Artista")
            status = document.metadata("Status").toStatus()
            genre = document.select("a[href*='genero=']").eachText().joinToString()
            description = document.selectFirst("h2:matchesOwn(^Sinopse$) + p")?.text()
        }
    }

    override fun chapterListRequest(manga: SManga): Request {
        val preview = chapterPreviews[manga.url]
        return if (preview != null && System.currentTimeMillis() - preview.createdAt < CHAPTER_PREVIEW_TTL) {
            GET("$baseUrl${preview.readerUrl}", headers)
        } else {
            mangaDetailsRequest(manga)
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val readerLinks = document.select("[data-select-list] a[href*='/capitulo-']")
        if (readerLinks.isNotEmpty()) {
            val mangaUrl = readerLinks.firstOrNull()?.attr("href")?.substringBeforeLast("/capitulo-")
                ?: return emptyList()
            val dates = chapterPreviews[mangaUrl]?.dates.orEmpty()
            return readerLinks.map { link ->
                SChapter.create().apply {
                    url = link.attr("href")
                    name = link.text()
                    date_upload = dates[url] ?: 0L
                }
            }
        }
        val visibleChapters = document.select("[id^=chapter-card-]").mapNotNull { card ->
            val link = card.selectFirst("a[href*='/capitulo-']") ?: return@mapNotNull null
            SChapter.create().apply {
                url = link.attr("href")
                name = card.selectFirst("span[title]")?.text() ?: link.text()
                date_upload = parseChapterDate(card.selectFirst("span.text-xs")?.text())
            }
        }

        val dates = visibleChapters.associateBy(SChapter::url)
        val firstChapter = visibleChapters.firstOrNull() ?: return emptyList()
        val chaptersDocument = client.newCall(GET("$baseUrl${firstChapter.url}", headers)).execute().use {
            it.asJsoup()
        }

        return chaptersDocument.select("[data-select-list] a[href*='/capitulo-']")
            .map { link ->
                dates[link.attr("href")] ?: SChapter.create().apply {
                    url = link.attr("href")
                    name = link.text()
                }
            }
    }

    private fun fenixMangaPage(document: Document): MangasPage = MangasPage(
        document.select("[id^=work-]").mapNotNull(::fenixMangaFromElement),
        document.selectFirst("a[rel=next]") != null,
    )

    private fun fenixMangaFromElement(element: Element): SManga? {
        val link = element.selectFirst("a[href^=/manga/]:not([href*=/capitulo-])") ?: return null
        val title = element.selectFirst("h3")?.text()
            ?: element.selectFirst("img[alt]")?.attr("alt")
            ?: return null
        return SManga.create().apply {
            url = link.attr("href")
            this.title = title
            thumbnail_url = element.selectFirst("img")?.attr("abs:src")
        }
    }

    private fun Document.metadata(label: String): String? = select("dt")
        .firstOrNull { it.text() == label }
        ?.nextElementSibling()
        ?.text()
        ?.takeIf(String::isNotBlank)

    private fun cacheChapterPreview(document: Document) {
        val chapters = document.select("[id^=chapter-card-]").mapNotNull { card ->
            val link = card.selectFirst("a[href*='/capitulo-']") ?: return@mapNotNull null
            link.attr("href") to parseChapterDate(card.selectFirst("span.text-xs")?.text())
        }
        val readerUrl = chapters.firstOrNull()?.first ?: return
        chapterPreviews[readerUrl.substringBeforeLast("/capitulo-")] = ChapterPreview(
            readerUrl = readerUrl,
            dates = chapters.toMap(),
            createdAt = System.currentTimeMillis(),
        )
    }

    private class ChapterPreview(
        val readerUrl: String,
        val dates: Map<String, Long>,
        val createdAt: Long,
    )

    private fun String?.toStatus(): Int = when (this?.lowercase()) {
        "concluído", "concluido", "completo" -> SManga.COMPLETED
        "em andamento" -> SManga.ONGOING
        "hiato", "pausado" -> SManga.ON_HIATUS
        "cancelado" -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    private companion object {
        const val CHAPTER_PREVIEW_TTL = 2 * 60 * 1000L
        const val ADULT_CONTENT_PREF = "pref_show_adult_content"
        const val ADULT_CONTENT_COOKIE = "fenix_adult"
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val request = super.pageListRequest(chapter)

        // Bypass LiteSpeed Cache to ensure the server returns the unoptimized HTML
        // containing the raw image elements instead of stripped lazy-load placeholders.
        val url = request.url.newBuilder()
            .addQueryParameter("nocache", System.currentTimeMillis().toString())
            .build()

        return request.newBuilder()
            .url(url)
            .build()
    }

    override fun pageListParse(document: Document): List<Page> {
        launchIO { countViews(document) }

        document.select("#reader-pages img").mapIndexed { index, image ->
            Page(index, document.location(), image.attr("abs:src"))
        }.takeIf { it.isNotEmpty() }?.let { return it }

        var chapterData: String? = null
        var password: String? = null

        val chapterDataRegex = Regex("""chapter_data\s*=\s*(?:'([^']*)'|"((?:\\"|[^"])*)")""")
        val nonceRegex = Regex("""wpmangaprotectornonce\s*=\s*(?:'([^']*)'|"((?:\\"|[^"])*)")""")

        fun extractData(text: String) {
            if (chapterData == null) {
                chapterDataRegex.find(text)?.let { match ->
                    chapterData = match.groupValues[1].takeIf { it.isNotEmpty() }
                        ?: match.groupValues[2].replace("\\\"", "\"")
                }
            }
            if (password == null) {
                nonceRegex.find(text)?.let { match ->
                    password = match.groupValues[1].takeIf { it.isNotEmpty() }
                        ?: match.groupValues[2].replace("\\\"", "\"")
                }
            }
        }

        // First check standard script element from Madara
        val chapterProtector = document.selectFirst(chapterProtectorSelector)
        if (chapterProtector != null) {
            val chapterProtectorHtml = chapterProtector.attr("src")
                .takeIf { it.startsWith("data:text/javascript;base64,") }
                ?.substringAfter("data:text/javascript;base64,")
                ?.let { Base64.decode(it, Base64.DEFAULT).toString(Charsets.UTF_8) }
                ?: chapterProtector.html()

            extractData(chapterProtectorHtml)
        }

        // Check raw HTML in case it's inline
        extractData(document.html())

        // If still missing, check LiteSpeed cache JS
        if (chapterData == null || password == null) {
            document.select("script[src*=/litespeed/js/]").forEach { script ->
                if (chapterData != null && password != null) return@forEach
                val litespeedScript = script.attr("abs:src")
                try {
                    val jsResponse = client.newCall(GET(litespeedScript, headers)).execute().body.string()
                    extractData(jsResponse)
                } catch (_: Exception) {
                    // Ignore and continue fallback
                }
            }
        }

        if (chapterData != null && password != null) {
            val chapterDataJson = json.parseToJsonElement(chapterData.replace("\\/", "/")).jsonObject
            val unsaltedCiphertext = Base64.decode(
                chapterDataJson["ct"]?.jsonPrimitive?.content ?: return emptyList(),
                Base64.DEFAULT,
            )
            val salt = chapterDataJson["s"]?.jsonPrimitive?.content?.decodeHex() ?: return emptyList()
            val ciphertext = salted + salt + unsaltedCiphertext

            val rawImgArray = CryptoAES.decrypt(Base64.encodeToString(ciphertext, Base64.DEFAULT), password)
            val imgArrayString = json.parseToJsonElement(rawImgArray).jsonPrimitive.content
            val imgArray = json.parseToJsonElement(imgArrayString).jsonArray

            return imgArray.mapIndexed { idx, it ->
                Page(idx, document.location(), it.jsonPrimitive.content)
            }
        }

        return document.select(pageListParseSelector).mapIndexed { index, element ->
            val imageUrl = element.selectFirst("img")?.let { imageFromElement(it) }
            Page(index, document.location(), imageUrl)
        }
    }
}
