package eu.kanade.tachiyomi.extension.pt.littletyrant

import android.util.Log
import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.ByteString.Companion.decodeBase64
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Source
abstract class LittleTyrant : Madara() {
    override val chapterDateFormat = DateTimeFormatter.ofPattern("MMMM dd, yyyy", Locale.forLanguageTag("pt-BR"))

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        addNetworkInterceptor(ImageDecoderInterceptor())
        rateLimit(
            permits = 5,
            period = 1.seconds,
            shouldLimit = { url ->
                !url.encodedPath.contains("image-loader.php") &&
                    !url.encodedPath.endsWith(".webp") &&
                    !url.encodedPath.endsWith(".jpg") &&
                    !url.encodedPath.endsWith(".png")
            },
        )
    }

    override fun Headers.Builder.configureHeaders(): Headers.Builder = apply {
        set("Sec-Fetch-Mode", "cors")
        set("Sec-Fetch-Dest", "empty")
        set("Sec-Fetch-Site", "same-origin")
    }

    // =============================== Popular / Latest / Archive ===============================

    override fun archiveSelector() = ".card-hunters, .manga-hover-card, [id*=-entry-], div.page-item-detail, .manga__item, .c-tabs-item__content"

    override val archiveUrlSelector =
        ".x7q-title a, .card-title a, .post-title a, a[href*='/manga/']"

    override val archiveTitleSelector =
        ".x7q-title a, .card-title a, .post-title a, h4 a, h3"

    override fun Element.postId(): String? = attr("data-post-id").takeIf(String::isNotBlank)
        ?: id().substringAfter("-entry-", "").takeIf(String::isNotBlank)
        ?: selectFirst("[data-post-id]")?.attr("data-post-id")
        ?: selectFirst("a[data-post]")?.attr("data-post")

    // =============================== Details =================================

    override val mangaDetailsSelectorGenre = ".genres-content a, .genres-tax-list a"
    override val mangaDetailsSelectorDescription =
        ".x7q-summary .summary-text-scroll, .summary-content-box, div.description-summary div.summary__content"
    override val mangaDetailsSelectorAuthor =
        ".x7q-row:has(.attr-label:contains(AUTOR)) .attr-value, .attr-item:has(.attr-label:contains(AUTOR)) .attr-value, div.author-content > a"
    override val mangaDetailsSelectorArtist =
        ".x7q-row:has(.attr-label:contains(ARTISTA)) .attr-value, .attr-item:has(.attr-label:contains(ARTISTA)) .attr-value, div.artist-content > a"
    override val mangaDetailsSelectorStatus =
        ".x7q-row:has(.attr-label:contains(STATUS)) .attr-value, .attr-item:has(.attr-label:contains(STATUS)) .attr-value, div.summary-content, div.summary-heading:contains(Status) + div"

    // =============================== Chapters =================================

    override val chapterNameSelector = ".x7q-chapter-name, .chapter-name-label"
    override val chapterDateSelector = ".x7q-chapter-date, .chapter-pub-date, span.chapter-release-date"

    override fun parseChapterDate(date: String?): Long {
        val trimmed = date?.trim() ?: return 0
        return super.parseChapterDate(trimmed)
    }

    override fun getChapterUrl(chapter: SChapter): String {
        val url = chapter.url
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url
        }
        if (url.startsWith("/")) {
            return "$baseUrl$url"
        }
        val mangaPath = runCatching { chapter.memo["mangaPath"]?.jsonPrimitive?.content }.getOrNull()
        return if (!mangaPath.isNullOrBlank()) {
            "$baseUrl/${mangaPath.trim('/')}/${url.trim('/')}/"
        } else {
            "$baseUrl/$url"
        }
    }

    override suspend fun fetchChapters(
        mangaPath: String,
        id: String,
        mangaPage: Document?,
    ): List<SChapter> {
        val chapters = mutableListOf<SChapter>()
        val url = "$baseUrl/wp-admin/admin-ajax.php"
        var offset = 0
        while (true) {
            val form = FormBody.Builder()
                .add("action", "load_more_chapters")
                .add("manga_id", id)
                .add("offset", offset.toString())
                .build()
            val dto = client.post(url, form).parseAs<ChapterDto>()
            if (dto.isEmpty()) break
            val chapterElements = dto.toJsoup(baseUrl).select(chapterListSelector())
            if (chapterElements.isEmpty()) break
            chapters += chapterElements.mapNotNull { chapterFromElement(it, mangaPath) }
            if (chapterElements.size < 12) break
            offset += 12
        }

        return chapters.sortedByDescending(SChapter::chapter_number)
    }

    // =============================== Pages =================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = getChapterUrl(chapter)
        val chapterResponse = client.get(chapterUrl, ensureSuccess = false)
        val chapterCode = chapterResponse.code
        val chapterContentType = chapterResponse.header("Content-Type").orEmpty()
        val chapterCookies = client.cookieJar.loadForRequest(chapterUrl.toHttpUrl())
        val hasCookies = chapterCookies.isNotEmpty()

        Log.d(
            "LittleTyrantLog",
            "CHAPTER_HTML status=$chapterCode host=${chapterUrl.toHttpUrl().host} contentType=$chapterContentType hasCookies=$hasCookies",
        )

        if (chapterCode == 403) {
            val errorBody = chapterResponse.body.string()
            val isCloudflare = errorBody.contains("cloudflare", ignoreCase = true) ||
                errorBody.contains("challenge-platform", ignoreCase = true) ||
                errorBody.contains("turnstile", ignoreCase = true)
            Log.e(
                "LittleTyrantLog",
                "CHAPTER_HTML 403 detected! isCloudflare=$isCloudflare bodySnippet=${errorBody.take(200).replace("\n", " ")}",
            )
            throw HttpException(403)
        }

        if (!chapterResponse.isSuccessful) {
            throw HttpException(chapterCode)
        }

        val html = chapterResponse.body.string()
        val document = Jsoup.parse(html, chapterUrl)

        val script = document.select("script").firstOrNull {
            PROXY_URLS_REGEX.containsMatchIn(it.data()) || CURRENT_PAGES_REGEX.containsMatchIn(it.data())
        }?.data()

        val nonce = script?.let(::extractNonce)
        val themePath = script?.let(::extractThemePath)
        val proxyUrls = script?.let(::extractProxyUrls)
        val legacyPages = script?.let(::extractLegacyPages)

        Log.d(
            "LittleTyrantLog",
            "EXTRACTION hasNonce=${!nonce.isNullOrBlank()} hasTheme=${!themePath.isNullOrBlank()} hasProxyUrls=${!proxyUrls.isNullOrBlank()} legacyPages=${!legacyPages.isNullOrBlank()}",
        )

        if (script == null) {
            val pages = parsePages(document)
            if (pages.isNotEmpty()) return pages
            return emptyList()
        }

        if (!legacyPages.isNullOrBlank()) {
            return json.decodeFromString<List<String>>(legacyPages).mapIndexed { index, encodedUrl ->
                Page(index, imageUrl = decodePageUrl(encodedUrl))
            }
        }

        if (proxyUrls.isNullOrBlank() || themePath.isNullOrBlank()) {
            return emptyList()
        }

        val token = getToken(themePath, nonce, chapterUrl)

        return buildPageListFromProxy(proxyUrls, themePath, token, nonce)
    }

    private suspend fun getToken(themePath: String, nonce: String?, chapterUrl: String): String {
        val gatekeeperUrl = buildGatekeeperUrl(baseUrl, themePath, nonce, System.currentTimeMillis())

        if (!nonce.isNullOrBlank()) {
            val nonceCookie = Cookie.Builder()
                .domain(gatekeeperUrl.host)
                .path("/")
                .name("lt_browser_nonce")
                .value(nonce)
                .build()
            runCatching {
                client.cookieJar.saveFromResponse(gatekeeperUrl, listOf(nonceCookie))
            }
        }

        val pageHeaders = buildGatekeeperHeaders(headersBuilder(), nonce, chapterUrl)

        val response = client.get(gatekeeperUrl, pageHeaders, ensureSuccess = false)
        val code = response.code
        val contentType = response.header("Content-Type").orEmpty()
        val bodyString = response.body.string()

        val token = parseTokenFromBody(bodyString)
        val hasToken = token.isNotBlank() && token != bodyString

        Log.d(
            "LittleTyrantLog",
            "GATEKEEPER status=$code host=${gatekeeperUrl.host} contentType=$contentType hasNonce=${!nonce.isNullOrBlank()} hasToken=$hasToken bodySize=${bodyString.length}",
        )

        if (code == 403) {
            val isCloudflare = bodyString.contains("cloudflare", ignoreCase = true) ||
                bodyString.contains("challenge-platform", ignoreCase = true)
            Log.e(
                "LittleTyrantLog",
                "GATEKEEPER 403 detected! isCloudflare=$isCloudflare bodySnippet=${bodyString.take(200).replace("\n", " ")}",
            )
            throw HttpException(403)
        }

        if (!response.isSuccessful) {
            throw HttpException(code)
        }

        if (hasToken) {
            val tokenCookie = Cookie.Builder()
                .domain(gatekeeperUrl.host)
                .path("/")
                .name("lt_sec_val")
                .value(token)
                .build()
            runCatching {
                client.cookieJar.saveFromResponse(gatekeeperUrl, listOf(tokenCookie))
            }
        }

        return token
    }

    // =============================== Images =================================

    override fun imageRequest(page: Page): Request {
        val imageHeaders = headersBuilder()
            .set("Accept", "image/webp,image/*,*/*")
            .set("Referer", "$baseUrl/")
            .set("X-Reader-Sec", "tiraninha-web")
            .build()
        return GET(page.imageUrl!!, imageHeaders)
    }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        private val PROXY_URLS_REGEX = """_proxyUrls\s*=\s*(\[[^]]+])""".toRegex(RegexOption.IGNORE_CASE)
        private val CURRENT_PAGES_REGEX = """(?:var\s+)?pages\s*=\s*(\[[^]]+])""".toRegex(RegexOption.IGNORE_CASE)
        private val BASE_URL_PAGE_REGEX = """_themePath\s*=\s*["']([^"']+)""".toRegex(RegexOption.IGNORE_CASE)
        private val BROWSER_NONCE_REGEX = """_browserNonce\s*=\s*["']([^"']+)["']""".toRegex(RegexOption.IGNORE_CASE)
        private val TOKEN_JSON_REGEX = """"token"\s*:\s*"([^"]+)"""".toRegex()

        internal fun decodePageUrl(value: String): String {
            if (value.startsWith("http://") || value.startsWith("https://")) return value
            return runCatching {
                value.decodeBase64()?.utf8() ?: value
            }.getOrDefault(value)
        }

        internal fun buildGatekeeperUrl(baseUrl: String, themePath: String, nonce: String?, timestamp: Long): HttpUrl {
            val gatekeeperBase = when {
                themePath.startsWith("http://") || themePath.startsWith("https://") -> themePath.trimEnd('/')
                else -> "$baseUrl/${themePath.trim('/')}"
            }
            return "$gatekeeperBase/gatekeeper.php".toHttpUrl().newBuilder()
                .addQueryParameter("t", timestamp.toString())
                .apply {
                    if (!nonce.isNullOrBlank()) {
                        addQueryParameter("browser_nonce", nonce)
                    }
                }
                .build()
        }

        internal fun buildGatekeeperHeaders(
            headersBuilder: Headers.Builder,
            nonce: String?,
            chapterUrl: String? = null,
        ): Headers = headersBuilder
            .set("X-Reader-Sec", "tiraninha-web")
            .set("Sec-Fetch-Mode", "cors")
            .set("Sec-Fetch-Dest", "empty")
            .set("Sec-Fetch-Site", "same-origin")
            .apply {
                if (!chapterUrl.isNullOrBlank()) {
                    set("Referer", chapterUrl)
                }
                if (!nonce.isNullOrBlank()) {
                    add("Cookie", "lt_browser_nonce=$nonce")
                }
            }
            .build()

        internal fun parseTokenFromBody(bodyString: String): String = runCatching {
            json.decodeFromString<TokenDto>(bodyString).token
        }.recoverCatching {
            TOKEN_JSON_REGEX.find(bodyString)?.groupValues?.get(1) ?: bodyString.trim()
        }.getOrElse {
            bodyString.trim()
        }

        internal fun buildPageListFromProxy(
            pagesJson: String,
            themePath: String,
            token: String,
            nonce: String?,
            currentTimestamp: Long = System.currentTimeMillis(),
            defaultBaseUrl: String = "https://tiraninha.world",
        ): List<Page> {
            val fragment = buildJsonObject {
                put("token", token)
                if (!nonce.isNullOrBlank()) {
                    put("nonce", nonce)
                }
            }.toString()

            val imageBase = when {
                themePath.startsWith("http://") || themePath.startsWith("https://") ->
                    themePath.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" } ?: defaultBaseUrl
                else -> defaultBaseUrl
            }

            return json.decodeFromString<List<String>>(pagesJson)
                .mapIndexed { index, pathSegment ->
                    val decodePath = URLDecoder.decode(pathSegment, StandardCharsets.UTF_8.name())
                    val resolvedUrl = when {
                        decodePath.startsWith("http://") || decodePath.startsWith("https://") -> decodePath
                        decodePath.startsWith("/") -> "$imageBase$decodePath"
                        else -> "$imageBase/$decodePath"
                    }
                    val imageUrl = resolvedUrl.toHttpUrl().newBuilder()
                        .addQueryParameter("t_force", currentTimestamp.toString())
                        .fragment(fragment)
                        .build().toString()
                    Page(index, imageUrl = imageUrl)
                }
        }

        internal fun extractNonce(script: String): String? = BROWSER_NONCE_REGEX.find(script)?.groupValues?.last()

        internal fun extractThemePath(script: String): String? = BASE_URL_PAGE_REGEX.find(script)?.groupValues?.last()?.replace("\\/", "/")?.trim()

        internal fun extractProxyUrls(script: String): String? = PROXY_URLS_REGEX.find(script)?.groupValues?.last()

        internal fun extractLegacyPages(script: String): String? = CURRENT_PAGES_REGEX.find(script)?.groupValues?.last()
    }
}
