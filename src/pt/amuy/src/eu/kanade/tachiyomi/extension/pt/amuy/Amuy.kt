package eu.kanade.tachiyomi.extension.pt.amuy

import android.util.Log
import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Amuy : Madara() {
    override val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR"))

    override val client: OkHttpClient = super.client.newBuilder()
        .rateLimit(1, 2.seconds)
        .addInterceptor(::debugInterceptor)
        .build()

    override val useNewChapterEndpoint = true

    override fun popularMangaRequest(page: Int): Request = if (page == 1) {
        GET("$baseUrl/$mangaSubString/${searchPage(page)}?m_orderby=views", headers)
    } else {
        loadMoreRequest(page, popular = true)
    }

    override fun latestUpdatesRequest(page: Int): Request = if (page == 1) {
        GET("$baseUrl/$mangaSubString/${searchPage(page)}?m_orderby=latest", headers)
    } else {
        loadMoreRequest(page, popular = false)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = if (page == 1) {
        searchRequest(page, query, filters)
    } else {
        searchRequest(page, query, filters)
    }

    override fun loadMoreRequest(page: Int, popular: Boolean): Request {
        val formBody = FormBody.Builder().apply {
            add("action", "madara_load_more")
            add("page", (page - 1).toString())
            add("template", "wp-manga/content/content-archive")
            add("vars[paged]", "1")
            add("vars[orderby]", if (popular) "manga_views" else "meta_value_num")
            add("vars[sidebar]", "full")
            add("vars[post_type]", "wp-manga")
            add("vars[post_status]", "publish")
            add("vars[order]", "desc")
            add("vars[manga_archives_item_layout]", "big_thumbnail")
            add("vars[meta_query][relation]", "AND")
            if (popular) {
                add("vars[wp_manga_views_column]", "_wp_manga_week_views_value")
            } else {
                add("vars[meta_key]", "_latest_update")
            }
        }.build()
        return eu.kanade.tachiyomi.network.POST("$baseUrl/wp-admin/admin-ajax.php", xhrHeaders, formBody)
    }

    override fun pageListParse(document: Document): List<Page> {
        val chapterProtector = document.selectFirst(chapterProtectorSelector)

        if (chapterProtector == null) {
            launchIO { countViews(document) }

            val pageElements = document.select(
                "div.page-break img, li.blocks-gallery-item img, .reading-content .text-left:not(:has(.blocks-gallery-item)) img",
            )

            return pageElements.mapIndexed { index, element ->
                Page(index, document.location(), imageFromElement(element))
            }
        }

        return super.pageListParse(document)
    }

    private fun debugInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var operation = when {
            request.url.encodedPath.contains("/manga/") -> "DETAILS"
            request.url.encodedPath.contains("/chapter") -> "CHAPTER"
            request.url.encodedPath.contains("/manga") == true -> "CATALOG"
            else -> "OTHER"
        }
        val bodyFields = request.body?.let { body ->
            val buffer = Buffer()
            body.writeTo(buffer)
            buffer.readUtf8().split('&').filter { it.isNotEmpty() }.map { field ->
                val key = field.substringBefore('=').replace('+', ' ')
                key to field.substringAfter('=', "").replace('+', ' ')
            }
        }.orEmpty()
        val action = bodyFields.firstOrNull { it.first == "action" }?.second
        if (action == "madara_load_more") operation = "AJAX"
        val relevantFields = bodyFields.filter { (key, _) ->
            key == "action" || key == "page" || key == "template" ||
                key.endsWith("[paged]") || key.endsWith("[orderby]") ||
                key.endsWith("[order]") || key.endsWith("[s]") ||
                key.endsWith("[meta_key]")
        }
        val safeUrl = request.url.encodedPath + request.url.query.orEmpty().let { query ->
            if (query.isEmpty()) "" else "?" + query.split('&').map { it.substringBefore('=') }.joinToString("&")
        }
        val hasCookie = request.header("Cookie") != null
        Log.e("AMUY_DEBUG", "REQUEST operation=$operation method=${request.method} path=$safeUrl cookie=$hasCookie headers=${request.headers.names().joinToString(",")} params=${relevantFields.joinToString(";")}")

        val response = chain.proceed(request)
        val contentType = response.header("Content-Type").orEmpty()
        val preview = response.peekBody(1024 * 1024).string()
        val title = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(preview)?.groupValues?.getOrNull(1)?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        val indicators = listOf(
            "login" to Regex("login|sign in|entrar", RegexOption.IGNORE_CASE),
            "challenge" to Regex("challenge|cloudflare|captcha|verify", RegexOption.IGNORE_CASE),
            "error" to Regex("error|not found|forbidden", RegexOption.IGNORE_CASE),
            "json" to Regex("^\\s*[\\[{]"),
        ).filter { (_, pattern) -> pattern.containsMatchIn(preview) }.map { it.first }
        val html = Jsoup.parse(preview)
        val selectors = listOf("div.page-item-detail", ".c-tabs-item__content", ".manga__item")
        val selectorCounts = selectors.joinToString(";") { "$it=${html.select(it).size}" }
        Log.e("AMUY_DEBUG", "${if (operation == "AJAX") "AJAX_RESPONSE" else "RESPONSE"} operation=$operation status=${response.code} finalPath=${response.request.url.encodedPath} contentType=$contentType bodyLength=${response.body?.contentLength()} empty=${preview.isBlank()} selectorCounts=$selectorCounts title=${title.isNotEmpty()} indicators=${indicators.joinToString(",")}")
        return response
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val preview = response.peekBody(1024 * 1024).string()
        if (response.request.url.encodedPath.endsWith("/admin-ajax.php") && preview.isBlank()) {
            Log.e("AMUY_DEBUG", "PARSE operation=Popular ajaxEnd=true mangas=0")
            return MangasPage(emptyList(), false)
        }
        val result = super.popularMangaParse(response)
        Log.e("AMUY_DEBUG", "PARSE operation=Popular page=${response.request.url.queryParameter("page") ?: "1"} selector=popular elements=${Jsoup.parse(preview).select(popularMangaSelector()).size} mangas=${result.mangas.size}")
        return result
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val preview = response.peekBody(1024 * 1024).string()
        if (response.request.url.encodedPath.endsWith("/admin-ajax.php") && preview.isBlank()) {
            Log.e("AMUY_DEBUG", "PARSE operation=Latest ajaxEnd=true mangas=0")
            return MangasPage(emptyList(), false)
        }
        val result = super.latestUpdatesParse(response)
        Log.e("AMUY_DEBUG", "PARSE operation=Latest selector=latest elements=${Jsoup.parse(preview).select(latestUpdatesSelector()).size} mangas=${result.mangas.size}")
        return result
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val preview = response.peekBody(1024 * 1024).string()
        if (response.request.url.encodedPath.endsWith("/admin-ajax.php") && preview.isBlank()) {
            Log.e("AMUY_DEBUG", "PARSE operation=Search ajaxEnd=true mangas=0")
            return MangasPage(emptyList(), false)
        }
        val result = super.searchMangaParse(response)
        Log.e("AMUY_DEBUG", "PARSE operation=Search selector=search elements=${Jsoup.parse(preview).select(searchMangaSelector()).size} mangas=${result.mangas.size}")
        return result
    }
}
