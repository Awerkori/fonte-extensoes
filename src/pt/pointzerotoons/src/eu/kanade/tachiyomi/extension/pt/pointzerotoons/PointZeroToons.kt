package eu.kanade.tachiyomi.extension.pt.pointzerotoons

import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

@Source
abstract class PointZeroToons : MangaThemesia() {
    override val datePattern = "dd.MM.yyyy"

    override fun OkHttpClient.Builder.configureClient() = rateLimit(3)

    override val seriesDetailsSelector = ".inkra-series-shell, div.bigcontent, div.animefull, div.main-info, div.postbody"

    override val seriesTitleSelector = ".inkra-page-title, .entry-title, .ts-breadcrumb li:last-child span"

    override val seriesDescriptionSelector = ".inkra-series-synopsis, .desc, .entry-content[itemprop=description]"

    override val seriesAuthorSelector = ".inkra-series-tech__card:nth-of-type(1) strong, span:contains(autor)"

    override val seriesArtistSelector = ".inkra-series-tech__card:nth-of-type(2) strong, span:contains(artista)"

    override val seriesThumbnailSelector = ".inkra-series-cover img, .tx-hero-cover > img.wp-post-image, .infomanga > div[itemprop=image] img, .thumb img"

    override val pageSelector = ".inkra-reader-pages .inkra-reader-page img, main .inkra-reader-pages img, div#readerarea img"

    override fun chapterListParse(document: Document): List<SChapter> {
        if (document.select(".inkra-chapter-list .inkra-chapter-item").isEmpty()) {
            return super.chapterListParse(document)
        }

        return parsePointZeroChapters(document).map { chapter ->
            SChapter.create().apply {
                name = chapter.name
                setUrlWithoutDomain(chapter.url)
            }
        }
    }

    override fun searchMangaUrl(page: Int, query: String, filters: FilterList) = pointZeroSearchUrl(super.searchMangaUrl(page, query, filters), page)

    override fun searchMangaParse(document: Document): MangasPage {
        val mangas = parsePointZeroCards(document).map { card ->
            SManga.create().apply {
                title = card.title
                thumbnail_url = card.thumbnail
                setUrlWithoutDomain(card.url)
            }
        }
        return MangasPage(mangas, hasPointZeroNextPage(document))
    }
}
