package eu.kanade.tachiyomi.extension.pt.osakascan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import eu.kanade.tachiyomi.multisrc.zeistmanga.ZeistManga
import eu.kanade.tachiyomi.multisrc.zeistmanga.ZeistMangaDto
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

@Source
abstract class OsakaScan : ZeistManga() {
    private val combinedBlocks = ConcurrentHashMap<String, List<String>>()
    private val combinedCache = object : LinkedHashMap<String, ByteArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>) = size > 8
    }
    private val pageCache = object : LinkedHashMap<String, ByteArray>(14, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>) = size > 14
    }
    private val imageExecutor = Executors.newFixedThreadPool(MAX_IMAGE_DOWNLOADS)

    override fun OkHttpClient.Builder.configureClient() = rateLimit(2)
        .addInterceptor(
            Interceptor { chain ->
                if (chain.request().url.encodedPath.startsWith("/__nox_combined/")) {
                    return@Interceptor composeBlock(chain.request())
                }
                chain.proceed(chain.request())
            },
        )

    override fun popularMangaUrl(page: Int): String = baseUrl.toHttpUrl().newBuilder()
        .addPathSegments("feeds/posts/default/-/$mangaCategory")
        .addQueryParameter("alt", "json")
        .addQueryParameter("max-results", "50")
        .addQueryParameter("start-index", (50 * (page - 1) + 1).toString())
        .addQueryParameter("orderby", "updated")
        .build().toString()

    override fun parsePopularManga(response: Response): MangasPage {
        val dto = response.parseAs<ZeistMangaDto>()
        val entries = dto.feed?.entry.orEmpty()
            .filterNot { isChapterEntry(it.title?.t.orEmpty(), it.category.orEmpty().map { category -> category.term }) }
            .distinctBy { it.url.orEmpty().firstOrNull { link -> link.rel == "alternate" }?.href }
        val total = dto.feed?.totalResults?.t?.toIntOrNull() ?: 0
        val startIndex = response.request.url.queryParameter("start-index")?.toIntOrNull() ?: 1
        return MangasPage(entries.map { it.toSManga(baseUrl) }, total > 0 && startIndex + 50 <= total)
    }

    override fun mangaDetailsParse(document: Document) = SManga.create().apply {
        val path = document.location().toHttpUrl().encodedPath
        val feedUrl = baseUrl.toHttpUrl().newBuilder().addPathSegments("feeds/posts/default")
            .addQueryParameter("alt", "json").addQueryParameter("max-results", "1")
            .addQueryParameter("path", path).build().toString()
        val feedEntry = client.newCall(Request.Builder().url(feedUrl).headers(headers).build()).execute().use {
            it.parseAs<ZeistMangaDto>().feed?.entry?.firstOrNull()
        }
        val contentDocument = Jsoup.parse(feedEntry?.content?.t.orEmpty(), baseUrl)
        title = feedEntry?.title?.t ?: document.title().substringBefore('|').trim()
        description = contentDocument.selectFirst("#synopsis")?.text()?.takeIf { it.isNotBlank() }
        thumbnail_url = contentDocument.selectFirst("#osaka-cover img")?.attr("abs:src")
        contentDocument.select("#extra-info dt").forEach { key ->
            val value = key.nextElementSibling()?.text().orEmpty()
            when (key.text().trim().removeSuffix(":")) {
                "Autor", "Author" -> author = value
                "Artista", "Artist" -> artist = value
                "Status", "Estado" -> status = parseStatus(value)
            }
        }
        if (status == SManga.UNKNOWN) contentDocument.selectFirst("span[data-status]")?.text()?.let { status = parseStatus(it) }
        genre = contentDocument.select("#extra-info dt").firstOrNull { it.text().contains("Gênero", true) }
            ?.nextElementSibling()?.text()
    }

    override suspend fun getChapterList(feedUrl: String, doc: Document?): List<SChapter> {
        if (doc == null) return super.getChapterList(feedUrl, null)
        val title = doc.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() } ?: doc.title().substringBefore('|').trim()
        if (title.isBlank()) return emptyList()
        val marker = doc.select("a[rel=tag], .post-labels a, .labels a").map { it.text() }.firstOrNull { label ->
            label.isNotBlank() && title.contains(label, true) && !label.equals("Series", true) && !label.equals("Leitor", true)
        }
        val queries = buildList {
            add(baseUrl.toHttpUrl().newBuilder().addPathSegments("feeds/posts/default").addQueryParameter("alt", "json").addQueryParameter("q", title).build())
            marker?.let { add(baseUrl.toHttpUrl().newBuilder().addPathSegments("feeds/posts/default/-/").addPathSegment(it).addQueryParameter("alt", "json").build()) }
        }
        val entries = mutableListOf<eu.kanade.tachiyomi.multisrc.zeistmanga.ZeistMangaEntryDto>()
        for (query in queries) {
            var start = 1
            var total = Int.MAX_VALUE
            repeat(20) {
                if (entries.size >= total) return@repeat
                val dto = client.get(query.newBuilder().setQueryParameter("max-results", "50").setQueryParameter("start-index", start.toString()).build().toString()).parseAs<ZeistMangaDto>()
                total = dto.feed?.totalResults?.t?.toIntOrNull() ?: entries.size
                val page = dto.feed?.entry.orEmpty()
                if (page.isEmpty()) return@repeat
                entries += page
                start += page.size
            }
        }
        return entries.filter { entry ->
            val labels = entry.category.orEmpty().map { it.term }
            isChapterEntry(entry.title?.t.orEmpty(), labels) && (entry.title?.t.orEmpty().contains(title, true) || labels.any { it.equals("Leitor", true) })
        }.distinctBy { it.url.orEmpty().firstOrNull { link -> link.rel == "alternate" }?.href }
            .map { entry -> entry.toSChapter(baseUrl, parseDate(entry.getPublishedDate())).apply { CHAPTER_NUMBER_REGEX.find(name)?.groups?.get(1)?.value?.let { chapter_number = it.toFloat() } } }
            .sortedByDescending(SChapter::chapter_number)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val feedUrl = baseUrl.toHttpUrl().newBuilder().addPathSegments("feeds/posts/default")
            .addQueryParameter("alt", "json").addQueryParameter("max-results", "1").addQueryParameter("path", chapter.url).build().toString()
        val content = client.get(feedUrl).parseAs<ZeistMangaDto>().feed?.entry?.firstOrNull()?.content?.t ?: return emptyList()
        val urls = Jsoup.parse(content, baseUrl).select(".post-body div.separator, .entry-content div.separator, div.separator").mapNotNull { separator ->
            val image = separator.selectFirst("img") ?: return@mapNotNull null
            (
                separator.selectFirst("a[href]")?.attr("abs:href")?.takeIf { it.contains("bloggerusercontent.com") }
                    ?: image.attr("abs:data-original").ifBlank { image.attr("abs:src") }
                ).takeIf { it.isNotBlank() }
        }.distinct()
        return urls.chunked(PAGES_PER_BLOCK).mapIndexed { index, urls ->
            val synthetic = "$baseUrl/__nox_combined/${chapter.url}/$index"
            combinedBlocks[synthetic] = urls
            Page(index, imageUrl = synthetic)
        }
    }

    private fun composeBlock(request: Request): Response {
        val urls = combinedBlocks[request.url.toString()] ?: throw IOException("Osaka Scan: bloco de imagens ausente")
        synchronized(combinedCache) { combinedCache[request.url.toString()]?.let { return imageResponse(request, it) } }
        return runCatching {
            val bitmaps = urls.map { url ->
                imageExecutor.submit<Bitmap> {
                    val bytes = synchronized(pageCache) { pageCache[url] } ?: downloadImage(url).also { synchronized(pageCache) { pageCache[url] = it } }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IOException("Osaka Scan: imagem inválida")
                }
            }.map { it.get() }
            val result = Bitmap.createBitmap(bitmaps.maxOf { it.width }, bitmaps.sumOf { it.height }, Bitmap.Config.ARGB_8888)
            Canvas(result).apply {
                drawColor(Color.WHITE)
                var top = 0f
                bitmaps.forEach {
                    drawBitmap(it, 0f, top, null)
                    top += it.height
                    if (!it.isRecycled) it.recycle()
                }
            }
            val output = ByteArrayOutputStream()
            result.compress(Bitmap.CompressFormat.PNG, 100, output)
            result.recycle()
            output.toByteArray().also { synchronized(combinedCache) { combinedCache[request.url.toString()] = it } }.let { imageResponse(request, it) }
        }.getOrElse { throw IOException("Osaka Scan: erro ao compor bloco", it) }
    }

    private fun downloadImage(url: String) = client.newCall(Request.Builder().url(url).headers(headers).build()).execute().use { response ->
        if (!response.isSuccessful) throw IOException("Osaka Scan: imagem HTTP ${response.code}")
        response.body.bytes()
    }

    private fun imageResponse(request: Request, bytes: ByteArray) = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
        .header("Content-Type", "image/png").body(bytes.toResponseBody("image/png".toMediaType())).build()

    companion object {
        const val PAGES_PER_BLOCK = 7
        const val MAX_IMAGE_DOWNLOADS = 3
        val CHAPTER_NUMBER_REGEX = """(?:chapter|cap[ií]tulo)\s*(\d+(?:\.\d+)?)""".toRegex(RegexOption.IGNORE_CASE)
        private fun isChapterEntry(title: String, labels: List<String>) = CHAPTER_NUMBER_REGEX.containsMatchIn(title) &&
            (title.contains("capítulo", true) || title.contains("capitulo", true) || title.contains("chapter", true) || labels.any { it.equals("Leitor", true) || it.equals("Chapter", true) })
    }
}
