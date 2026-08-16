package eu.kanade.tachiyomi.extension.pt.tiamanhwa

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.annotation.Source
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class TiaManhwa : Madara() {
    override val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR"))

    override val mangaSubString = "manhwa"

    override val useLoadMoreRequest = LoadMoreStrategy.Never
    override val useNewChapterEndpoint = true

    // Search
    override fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ): Request {
        val url = "$baseUrl/page/$page/".toHttpUrl().newBuilder()
            .addQueryParameter("s", query)
            .addQueryParameter("post_type", "wp-manga")
            .build()

        return GET(url, headers)
    }

    override fun searchMangaSelector() = "div.page-item-detail.manga"

    // Search results parsing
    override fun searchMangaFromElement(element: Element): SManga = SManga.create().apply {
        val titleElement = element.selectFirst(".post-title a")!!
        title = titleElement.text()
        setUrlWithoutDomain(titleElement.attr("abs:href"))
        thumbnail_url = element.selectFirst(".item-thumb img")?.let { imageFromElement(it) }
    }

    override fun searchMangaNextPageSelector() = "a.next, a.page-numbers.next"

    // Details
    override val mangaDetailsSelectorStatus = "div.summary-heading:contains(Status) + div"

    // Chapters
    override fun chapterListSelector() = "li.wp-manga-chapter, li.chapter-item, div.chapter"
}
