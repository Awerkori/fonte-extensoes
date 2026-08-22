package eu.kanade.tachiyomi.extension.pt.osakascan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import eu.kanade.tachiyomi.multisrc.zeistmanga.ZeistManga
import eu.kanade.tachiyomi.multisrc.zeistmanga.ZeistMangaDto
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
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

    override val client = super.client.newBuilder()
        .rateLimit(2)
        .addInterceptor(
            Interceptor { chain ->
                if (chain.request().url.encodedPath.startsWith("/__nox_combined/")) {
                    return@Interceptor composeBlock(chain.request())
                }
                chain.proceed(chain.request())
            },
        )
        .build()

    override val popularMangaSelector = "#PopularPosts2 article"
    override val popularMangaSelectorTitle = "h3 a"
    override val popularMangaSelectorUrl = popularMangaSelectorTitle

    override fun popularMangaRequest(page: Int): Request {
        val startIndex = 50 * (page - 1) + 1
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("feeds/posts/default/-/$mangaCategory")
            .addQueryParameter("alt", "json")
            .addQueryParameter("max-results", "50")
            .addQueryParameter("start-index", startIndex.toString())
            .addQueryParameter("orderby", "updated")
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val dto = json.decodeFromString<ZeistMangaDto>(response.body.string())
        val entries = dto.feed?.entry.orEmpty()
            .filterNot { isChapterEntry(it.title?.t.orEmpty(), it.category.orEmpty().map { category -> category.term }) }
            .distinctBy { it.url.orEmpty().firstOrNull { link -> link.rel == "alternate" }?.href }
        val total = dto.feed?.totalResults?.t?.toIntOrNull() ?: 0
        val startIndex = response.request.url.queryParameter("start-index")?.toIntOrNull() ?: 1
        return MangasPage(
            entries.map { it.toSManga(baseUrl) },
            total > 0 && startIndex + 50 <= total,
        )
    }

    override fun mangaDetailsParse(response: Response) = SManga.create().apply {
        val document = response.asJsoup()
        val feedUrl = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("feeds/posts/default")
            .addQueryParameter("alt", "json")
            .addQueryParameter("max-results", "1")
            .addQueryParameter("path", response.request.url.encodedPath)
            .build()
        val feedEntry = client.newCall(GET(feedUrl, headers)).execute().use {
            json.decodeFromString<ZeistMangaDto>(it.body.string()).feed?.entry?.firstOrNull()
        }
        val contentDocument = Jsoup.parse(feedEntry?.content?.t.orEmpty(), baseUrl)
        title = feedEntry?.title?.t ?: document.title().substringBefore('|').trim()
        description = contentDocument.selectFirst("#synopsis")?.text()?.takeIf { it.isNotBlank() }
        thumbnail_url = contentDocument.selectFirst("#osaka-cover img")?.attr("abs:src")
        contentDocument.select("#extra-info dt").forEach { keyElement ->
            val value = keyElement.nextElementSibling()?.text().orEmpty()
            when (keyElement.text().trim().removeSuffix(":")) {
                "Autor", "Author" -> author = value
                "Artista", "Artist" -> artist = value
                "Status", "Estado" -> status = parseStatus(value)
            }
        }
        if (status == SManga.UNKNOWN) {
            contentDocument.selectFirst("span[data-status]")?.text()?.let { status = parseStatus(it) }
        }
        genre = contentDocument.select("#extra-info dt")
            .firstOrNull { it.text().contains("Gênero", true) }
            ?.nextElementSibling()?.text()
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val title = document.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() }
            ?: document.title().substringBefore('|').trim()
        if (title.isBlank()) return emptyList()

        val labels = document.select("a[rel=tag], .post-labels a, .labels a").map { it.text() }
        val marker = labels.firstOrNull { label ->
            label.isNotBlank() && title.contains(label, ignoreCase = true) &&
                !label.equals("Series", true) && !label.equals("Leitor", true)
        }
        val queries = buildList {
            add(
                baseUrl.toHttpUrl().newBuilder().addPathSegments("feeds/posts/default")
                    .addQueryParameter("alt", "json").addQueryParameter("q", title).build(),
            )
            if (marker != null) {
                add(
                    baseUrl.toHttpUrl().newBuilder()
                        .addPathSegments("feeds/posts/default/-/").addPathSegment(marker)
                        .addQueryParameter("alt", "json").build(),
                )
            }
        }
        val entries = queries.flatMap { query ->
            buildList {
                var startIndex = 1
                var totalResults = Int.MAX_VALUE
                repeat(20) {
                    if (size >= totalResults) return@repeat
                    val pageUrl = query.newBuilder()
                        .setQueryParameter("max-results", "50")
                        .setQueryParameter("start-index", startIndex.toString())
                        .build()
                    val pageEntries = client.newCall(GET(pageUrl, headers)).execute().use { chapterResponse ->
                        val dto = json.decodeFromString<ZeistMangaDto>(chapterResponse.body.string())
                        totalResults = dto.feed?.totalResults?.t?.toIntOrNull() ?: size
                        dto.feed?.entry.orEmpty()
                    }
                    if (pageEntries.isEmpty()) return@repeat
                    addAll(pageEntries)
                    startIndex += pageEntries.size
                }
            }
        }

        return entries
            .filter { entry ->
                val entryTitle = entry.title?.t.orEmpty()
                val labels = entry.category.orEmpty().map { it.term }
                isChapterEntry(entryTitle, labels) &&
                    (entryTitle.contains(title, ignoreCase = true) || labels.any { it.equals("Leitor", true) })
            }
            .distinctBy { it.url.orEmpty().firstOrNull { link -> link.rel == "alternate" }?.href }
            .map { entry ->
                entry.toSChapter(baseUrl, entry.getPublishedDate().let { parseDate(it) }).apply {
                    CHAPTER_NUMBER_REGEX.find(name)?.groups?.get(1)?.value?.let {
                        chapter_number = it.toFloat()
                    }
                }
            }
            .sortedByDescending(SChapter::chapter_number)
    }

    override val pageListSelector = "div.separator"

    override fun pageListRequest(chapter: SChapter): Request {
        val feedUrl = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("feeds/posts/default")
            .addQueryParameter("alt", "json")
            .addQueryParameter("max-results", "1")
            .addQueryParameter("path", chapter.url)
            .build()
        return GET(feedUrl, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val body = response.body.string()
        val content = json.decodeFromString<ZeistMangaDto>(body)
            .feed?.entry?.firstOrNull()?.content?.t
            ?: return emptyList()
        val document = Jsoup.parse(content, baseUrl)
        val imageUrls = document.select(".post-body div.separator, .entry-content div.separator, div.separator")
            .mapNotNull { separator ->
                val image = separator.selectFirst("img") ?: return@mapNotNull null
                val url = separator.selectFirst("a[href]")?.attr("abs:href")
                    ?.takeIf { it.contains("bloggerusercontent.com") }
                    ?: image.attr("abs:data-original").ifBlank { image.attr("abs:src") }
                url.takeIf { it.isNotBlank() }
            }
            .distinct()
        return imageUrls.chunked(PAGES_PER_BLOCK).mapIndexed { index, urls ->
            val synthetic = "$baseUrl/__nox_combined/${response.request.url.queryParameter("path") ?: "chapter"}/$index"
            combinedBlocks[synthetic] = urls
            Page(index, imageUrl = synthetic)
        }
    }

    private fun composeBlock(request: Request): Response {
        val urls = combinedBlocks[request.url.toString()]
            ?: throw IOException("Osaka Scan: bloco de imagens ausente")
        synchronized(combinedCache) {
            combinedCache[request.url.toString()]?.let { cached ->
                return imageResponse(request, cached)
            }
        }
        return runCatching {
            val bitmaps = urls.map { url ->
                imageExecutor.submit<Bitmap> {
                    val bytes = synchronized(pageCache) {
                        pageCache[url]
                    } ?: downloadImage(url).also { downloaded ->
                        synchronized(pageCache) { pageCache[url] = downloaded }
                    }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: throw IOException("Osaka Scan: imagem inválida")
                }
            }.map { it.get() }
            val width = bitmaps.maxOf { it.width }
            val height = bitmaps.sumOf { it.height }
            val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            canvas.drawColor(Color.WHITE)
            var top = 0f
            bitmaps.forEach { bitmap ->
                canvas.drawBitmap(bitmap, 0f, top, null)
                top += bitmap.height
                if (!bitmap.isRecycled) bitmap.recycle()
            }
            val output = ByteArrayOutputStream()
            result.compress(Bitmap.CompressFormat.PNG, 100, output)
            result.recycle()
            val bytes = output.toByteArray()
            synchronized(combinedCache) { combinedCache[request.url.toString()] = bytes }
            imageResponse(request, bytes)
        }.getOrElse { error ->
            throw IOException("Osaka Scan: erro ao compor bloco", error)
        }
    }

    private fun downloadImage(url: String): ByteArray = client.newCall(GET(url, headers)).execute().use { response ->
        if (!response.isSuccessful) {
            throw IOException("Osaka Scan: imagem HTTP ${response.code}")
        }
        response.body.bytes()
    }

    private fun imageResponse(request: Request, bytes: ByteArray): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .header("Content-Type", "image/png")
        .body(bytes.toResponseBody("image/png".toMediaType()))
        .build()

    companion object {
        const val PAGES_PER_BLOCK = 7
        const val MAX_IMAGE_DOWNLOADS = 3
        val CHAPTER_NUMBER_REGEX = """(?:chapter|cap[ií]tulo)\s*(\d+(?:\.\d+)?)""".toRegex(RegexOption.IGNORE_CASE)

        private fun isChapterEntry(title: String, labels: List<String>): Boolean = CHAPTER_NUMBER_REGEX.containsMatchIn(title) &&
            (
                title.contains("capítulo", true) || title.contains("capitulo", true) ||
                    title.contains("chapter", true) || labels.any { it.equals("Leitor", true) || it.equals("Chapter", true) }
                )
    }
}
