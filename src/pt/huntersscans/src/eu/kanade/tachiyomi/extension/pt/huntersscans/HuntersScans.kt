package eu.kanade.tachiyomi.extension.pt.huntersscans

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.nodes.Document
import rx.Observable
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Source
abstract class HuntersScans : Madara() {
    override val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR"))
    override val client = super.client.newBuilder()
        .readTimeout(1.minutes)
        .addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            // Only treat as a login redirect when the FIRST non-empty path segment is
            // "logar" or "registrar". Checking any segment would false-positive on manga
            // slugs that contain these words (e.g. /read/manga-logar-x/capitulo-1/).
            val firstSegment = response.request.url.pathSegments.firstOrNull { it.isNotEmpty() }
            if (firstSegment.equals("logar", true) || firstSegment.equals("registrar", true)) {
                response.close()
                throw IOException("Faça o login na WebView")
            }
            response
        }
        .addInterceptor(::imageInterceptor)
        // scrambler.php is a CDN-style image endpoint – allow more throughput there
        // while keeping the conservative limit on HTML/AJAX requests.
        // First matching rule wins, so the specific rule must come before the broad one.
        .rateLimit(10, 1.seconds) { it.pathSegments.any { seg -> seg.equals("scrambler.php", true) } }
        .rateLimit(2)
        .build()

    // Dedicated client for chapter-list pagination. Intentionally has no rate limit so
    // that the 16+ sequential AJAX calls don't monopolize the main client's 2-req/s
    // window and starve the concurrent mangaDetailsRequest.
    // Sequential calls + natural server latency (~300 ms/call) already self-throttle
    // to ~3 req/s without any artificial cap.
    private val chapterClient = super.client.newBuilder()
        .readTimeout(1.minutes)
        .build()

    override val mangaSubString = "comics"

    override val useLoadMoreRequest = LoadMoreStrategy.Always

    override val mangaDetailsSelectorStatus = "div.summary-heading:contains(Status) + div"

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable { fetchAllChapters(manga) }

    private fun fetchAllChapters(manga: SManga): List<SChapter> {
        val mangaUrl = getMangaUrl(manga)

        // Page 1: collect chapters and read the pagination widget to learn the last page.
        // The site includes a <div class="pagination"> whose <a data-page> links expose all
        // page numbers, so we avoid the "probe until empty" pattern entirely.
        val firstDoc = chapterClient.newCall(POST("${mangaUrl}ajax/chapters?t=1", xhrHeaders))
            .execute()
            .asJsoup()

        val firstPage = firstDoc.select(chapterListSelector()).map(::chapterFromElement)

        val lastPage = firstDoc.select("div.pagination a[data-page]")
            .mapNotNull { it.attr("data-page").toIntOrNull() }
            .maxOrNull() ?: 1

        if (lastPage <= 1) return firstPage

        val chapters = firstPage.toMutableList()
        for (page in 2..lastPage) {
            val doc = chapterClient.newCall(POST("${mangaUrl}ajax/chapters?t=$page", xhrHeaders))
                .execute()
                .asJsoup()
            chapters += doc.select(chapterListSelector()).map(::chapterFromElement)
        }

        return chapters
    }

    override fun pageListParse(document: Document): List<Page> {
        val script = document.selectFirst("script:containsData(_HuntersOpts)")?.data()

        if (script != null) {
            val payload = PAYLOAD_REGEX.find(script)?.groupValues?.get(1)
            val sk = SK_REGEX.find(script)?.groupValues?.get(1)

            if (payload != null && sk != null) {
                try {
                    val urls = HuntersScanDescrambler.decryptHuntersPayload(payload, sk)
                    if (urls.isNotEmpty()) {
                        return urls.mapIndexed { index, url -> Page(index, document.location(), url) }
                    }
                } catch (e: Exception) {
                }
            }

            // _HuntersOpts is present but payload decryption failed or returned empty –
            // try canvas elements before falling through to the generic Madara selector.
            val canvasPages = document.select("canvas[data-page-url]").mapIndexed { index, el ->
                val url = el.attr("abs:data-page-url").takeIf { it.isNotEmpty() }
                Page(index, document.location(), url)
            }
            if (canvasPages.isNotEmpty()) return canvasPages
        }

        return super.pageListParse(document)
    }

    private fun imageInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        if (request.url.toString().contains("scrambler.php")) {
            val scrambleKeyHeader = response.header("X-Scramble-Key")
            if (scrambleKeyHeader != null) {
                val imageStream = HuntersScanDescrambler.unscrambleImage(response.body.byteStream(), scrambleKeyHeader)
                val body = imageStream.readBytes().toResponseBody("image/jpeg".toMediaType())
                return response.newBuilder()
                    .body(body)
                    .build()
            }
        }

        return response
    }

    companion object {
        private val PAYLOAD_REGEX = Regex("""payload:\s*"(.*?)"""")
        private val SK_REGEX = Regex("""sk:\s*"(.*?)"""")
    }
}
