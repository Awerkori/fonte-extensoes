package eu.kanade.tachiyomi.extension.pt.xxxyaoi

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.collections.plusAssign
import kotlin.time.Duration.Companion.seconds

@Source
abstract class XXXYaoi : Madara() {
    override val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.ROOT)

    override fun headersBuilder() = super.headersBuilder()
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .set("Upgrade-Insecure-Requests", "1")
        .set("Sec-GPC", "1")
        .set("Sec-Fetch-User", "?1")
        .set("Sec-Fetch-Site", "none")
        .set("Sec-Fetch-Mode", "navigate")
        .set("Sec-Fetch-Dest", "document")
        .set("Priority", "u=0, i")
        .set("Pragma", "no-cache")

    override val client: OkHttpClient = super.client.newBuilder()
        .rateLimit(3, 1.seconds)
        .build()

    override val useNewChapterEndpoint = true

    override val useLoadMoreRequest = LoadMoreStrategy.Never

    override val mangaSubString = "bl"

    // The site uses a fully custom layout — no standard Madara selectors apply here.
    override val mangaDetailsSelectorTitle = "h1.xyaoi-main-title"
    override val mangaDetailsSelectorAuthor = ".xyaoi-prop-col:has(.xyaoi-prop-label:contains(AUTOR)) .xyaoi-prop-value a"
    override val mangaDetailsSelectorArtist = ".xyaoi-prop-col:has(.xyaoi-prop-label:contains(ARTISTA)) .xyaoi-prop-value a"
    override val mangaDetailsSelectorStatus = "span.xyaoi-prop-value[class*=status-value-]"
    override val mangaDetailsSelectorDescription = "div.xyaoi-synopsis-content"
    override val mangaDetailsSelectorGenre = "div.xyaoi-genres-list a.xyaoi-genre-pill"
    override val mangaDetailsSelectorTag = "[data-xxxyaoi-no-tags]"
    override val altNameSelector = "[data-xxxyaoi-no-alt-name]"
    override val altName get() = intl["alt_names_heading"]

    override fun chapterFromElement(element: org.jsoup.nodes.Element): eu.kanade.tachiyomi.source.model.SChapter {
        val chapter = eu.kanade.tachiyomi.source.model.SChapter.create()

        with(element) {
            selectFirst(chapterUrlSelector)!!.let { urlElement ->
                chapter.url = urlElement.attr("abs:href").let {
                    it.substringBefore("?style=paged") + if (!it.endsWith(chapterUrlSuffix)) chapterUrlSuffix else ""
                }
                chapter.name = selectFirst(".xyaoi-chapter-name")?.text() ?: urlElement.text()
            }
            chapter.date_upload = selectFirst(".xyaoi-chapter-date-line span")?.text()?.let(::parseChapterDate)
                ?: selectFirst("img:not(.thumb)")?.attr("alt")?.let { parseRelativeDate(it) }
                ?: selectFirst("span a")?.attr("title")?.let { parseRelativeDate(it) }
                ?: parseChapterDate(selectFirst(chapterDateSelector())?.text())
        }

        return chapter
    }

    override val statusFilterOptions: Map<String, String> =
        mapOf(
            intl["status_filter_completed"] to "end",
        )

    override fun searchMangaSelector() = ".page-item-detail.manga"

    override fun searchRequest(page: Int, query: String, filters: FilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder()

        loop@ for (filter in filters) {
            when (filter) {
                is StatusFilter -> {
                    filter.state.firstOrNull { it.state }?.let {
                        url.addPathSegment(it.name)
                        break@loop
                    }
                }

                is GenreOptions -> {
                    val selected = filter.selected()
                    if (selected.isNotBlank()) {
                        url.addPathSegment("genero")
                            .addPathSegment(selected)
                        break@loop
                    }
                }

                else -> {}
            }
        }

        url.addPathSegments(searchPage(page))
        return GET(url.build(), headers)
    }

    override fun getFilterList(): FilterList {
        launchIO { fetchGenres() }

        val filters: MutableList<Filter<out Any>> = mutableListOf(
            StatusFilter(
                title = intl["status_filter_title"],
                status = statusFilterOptions.map { Tag(it.key, it.value) },
            ),
        )

        if (genresList.isNotEmpty()) {
            val options: Array<Pair<String, String>> = arrayOf("Todos" to "") + genresList.map { it.name to it.id }.toTypedArray()
            filters += listOf(
                Filter.Separator(),
                Filter.Header(intl["genre_filter_header"]),
                GenreOptions(
                    displayName = intl["genre_filter_title"],
                    vals = options,
                ),
            )
        } else if (fetchGenres) {
            filters += listOf(
                Filter.Separator(),
                Filter.Header(intl["genre_missing_warning"]),
            )
        }

        return FilterList(filters)
    }

    class GenreOptions(displayName: String, private val vals: Array<Pair<String, String>>, state: Int = 0) : Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray(), state) {
        fun selected() = vals[state].second
    }
}
