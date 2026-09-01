package eu.kanade.tachiyomi.extension.pt.mangaonlinegreen

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import okhttp3.HttpUrl
import okhttp3.Response
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

@Source
abstract class MangaOnlineGreen : KeiSource() {
    override suspend fun getPopularManga(page: Int) = parseListing(client.get("$baseUrl/populares?page=$page"), "populares")
    override suspend fun getLatestUpdates(page: Int) = parseListing(client.get("$baseUrl/atualizacoes?page=$page"), "atualizacoes")
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList) = parseListing(client.get("$baseUrl/buscar/resultados?q=${URLEncoder.encode(query.trim(), "UTF-8")}&page=$page"), null)

    override suspend fun getMangaByUrl(url: HttpUrl) = SManga.create().apply { this.url = url.encodedPath }

    override suspend fun fetchMangaUpdate(manga: SManga, chapters: List<SChapter>, fetchDetails: Boolean, fetchChapters: Boolean): SMangaUpdate {
        val doc = client.get("$baseUrl${manga.url}").asJsoup()
        val updated = manga.apply {
            title = doc.selectFirst("main h1")?.text()?.trim().orEmpty()
            thumbnail_url = doc.selectFirst(".manga-cover img, img[src*='/uploads/covers/']")?.attr("src")?.let(::absoluteUrl)
            description = doc.selectFirst("[data-synopsis], .synopsis, meta[name=description]")?.let { it.attr("content").ifBlank { it.text() }.trim() }
            genre = doc.select(".manga-meta-grid .tags span").joinToString { it.text() }
            status = when {
                doc.selectFirst(".manga-info-pill-status")?.text()?.contains("andamento", true) == true -> SManga.ONGOING
                doc.selectFirst(".manga-info-pill-status")?.text()?.contains("completo", true) == true -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }
        return SMangaUpdate(updated, if (fetchChapters) parseChapters(doc) else chapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = client.get("$baseUrl${chapter.url}").asJsoup()
        .select("#readerContent img, .reader-content img").mapIndexedNotNull { i, image ->
            image.attr("src").takeIf { it.isNotBlank() }?.let { Page(i, imageUrl = absoluteUrl(it)) }
        }

    override fun getMangaUrl(manga: SManga) = "$baseUrl${manga.url}"
    override fun getChapterUrl(chapter: SChapter) = "$baseUrl${chapter.url}"

    private fun parseListing(response: Response, section: String?): MangasPage {
        val doc = response.asJsoup()
        val mangas = doc.select("article.manga-card, article.latest-manga-card").mapNotNull { card ->
            val link = card.selectFirst("a[href^=/manga/]") ?: return@mapNotNull null
            val image = card.selectFirst("img")
            val title = card.selectFirst(".card-info h3, .latest-card-title")?.text()?.trim()
                ?: image?.attr("alt")?.trim().orEmpty()
            if (title.isBlank()) return@mapNotNull null
            SManga.create().apply {
                url = link.attr("href").substringBefore('#')
                this.title = title
                thumbnail_url = image?.attr("src")?.let(::absoluteUrl)
            }
        }.distinctBy { it.url }
        val hasNext = section?.let { doc.selectFirst("nav.public-pagination[aria-label*=$it] a[href]") != null }
            ?: (doc.selectFirst("nav.public-pagination a[href]") != null)
        return MangasPage(mangas, hasNext)
    }

    private fun parseChapters(doc: Document) = doc.select("article.chapter-row[data-chapter-item]").mapNotNull { row ->
        val link = row.selectFirst("a.chapter-main-link") ?: return@mapNotNull null
        val number = row.attr("data-chapter-number").toFloatOrNull()
        SChapter.create().apply {
            url = link.attr("href").substringBefore('#')
            name = row.selectFirst(".chapter-title-line")?.text()?.trim() ?: number?.let { "Capítulo $it" } ?: "Capítulo"
            chapter_number = number ?: -1f
            date_upload = parseDate(row.selectFirst(".chapter-age")?.text().orEmpty())
        }
    }.distinctBy { it.url }

    private fun parseDate(value: String) = try {
        LocalDate.parse(value, DateTimeFormatter.ofPattern("d 'de' MMM", Locale("pt", "BR")))
            .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        0L
    }

    private fun absoluteUrl(path: String) = when {
        path.startsWith("http") -> path
        path.startsWith("//") -> "https:$path"
        path.startsWith("/") -> "$baseUrl$path"
        else -> "$baseUrl/$path"
    }
}
