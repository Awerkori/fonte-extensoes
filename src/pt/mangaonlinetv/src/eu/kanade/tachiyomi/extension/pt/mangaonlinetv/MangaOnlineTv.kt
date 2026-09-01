package eu.kanade.tachiyomi.extension.pt.mangaonlinetv

import eu.kanade.tachiyomi.source.model.Filter
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
import keiyoushi.utils.firstInstanceOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.Instant

@Source
abstract class MangaOnlineTv : KeiSource() {

    override suspend fun getPopularManga(page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        val document = client.get(baseUrl).asJsoup()
        val mangas = document.select("[data-mo-ranking-panel='week'] .mo-ranking-item")
            .mapNotNull(::parseRankingManga)
            .distinctBy(SManga::url)
        return MangasPage(mangas, false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        val document = client.get(baseUrl).asJsoup()
        val mangas = document.select(".mo-latest-panel .mo-manga-card")
            .mapNotNull(::parseLatestManga)
            .distinctBy(SManga::url)
        return MangasPage(mangas, false)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val genre = filters.firstInstanceOrNull<GenreFilter>()?.selectedValue
        val document = when {
            genre != null -> client.get("$baseUrl/genero/$genre/${if (page == 1) "" else "page/$page/"}").asJsoup()
            query.isNotEmpty() -> client.get(
                "$baseUrl/${if (page == 1) "" else "page/$page/"}".toHttpUrl().newBuilder()
                    .addQueryParameter("s", query)
                    .addQueryParameter("post_type", "wp-manga")
                    .build(),
            ).asJsoup()
            else -> client.get("$baseUrl/manga/${if (page == 1) "" else "page/$page/"}").asJsoup()
        }
        val selector = if (genre != null || query.isEmpty()) ".mo-archive-card" else ".c-tabs-item__content"
        val mangas = document.select(selector).mapNotNull(::parseMangaCard).distinctBy(SManga::url)
        return MangasPage(mangas, document.selectFirst("a.next.page-numbers, .nav-previous a") != null)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.pathSegments.firstOrNull() != "manga") return null
        val manga = SManga.create().apply { this.url = url.encodedPath }
        return fetchMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga.apply {
            initialized = true
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        val details = if (fetchDetails) async { parseDetails(document, manga.url) } else null
        val chapterList = if (fetchChapters) async { parseChapters(document) } else null
        SMangaUpdate(details?.await() ?: manga, chapterList?.await() ?: chapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()
        val script = document.selectFirst("script#chapter_preloaded_images")?.data()
            ?: error("O leitor não retornou as imagens do capítulo.")
        val urls = IMAGE_URL_REGEX.findAll(script).map { it.value.replace("\\/", "/") }.toList()
        require(urls.isNotEmpty()) { "O leitor não retornou páginas para este capítulo." }
        return urls.mapIndexed { index, url -> Page(index, imageUrl = url) }
    }

    override fun getMangaUrl(manga: SManga) = "$baseUrl/${manga.url.trimStart('/')}"

    override fun getChapterUrl(chapter: SChapter) = "$baseUrl/${chapter.url.trimStart('/')}"

    override fun getFilterList(data: JsonElement?) = FilterList(
        GenreFilter(),
    )

    private fun parseRankingManga(element: Element): SManga? {
        val link = element.selectFirst("h3 a") ?: return null
        return manga(link, element.selectFirst("img")?.absUrl("src"))
    }

    private fun parseLatestManga(element: Element): SManga? {
        val link = element.selectFirst("h3 a") ?: return null
        return manga(link, element.selectFirst(".mo-manga-cover img")?.absUrl("src"))
    }

    private fun parseMangaCard(element: Element): SManga? {
        val link = element.selectFirst(".mo-archive-card-title a, .post-title a") ?: return null
        return manga(link, element.selectFirst("img")?.absUrl("src"))
    }

    private fun manga(link: Element, thumbnail: String?): SManga? {
        val href = link.attr("abs:href").takeIf(String::isNotEmpty) ?: return null
        val title = link.text().takeIf(String::isNotEmpty) ?: return null
        return SManga.create().apply {
            url = href.toHttpUrl().encodedPath
            this.title = title
            thumbnail_url = thumbnail
        }
    }

    private fun parseDetails(document: Document, url: String) = SManga.create().apply {
        this.url = url
        title = document.selectFirst(".post-title h1")?.text() ?: error("Título ausente")
        thumbnail_url = document.selectFirst(".summary_image img")?.absUrl("src")
        author = document.select(".author-content a").eachText().joinToString().ifEmpty { null }
        artist = document.select(".artist-content a").eachText().joinToString().ifEmpty { null }
        genre = document.select(".genres-content a").eachText().joinToString().ifEmpty { null }
        description = document.selectFirst(".description-summary .summary__content")?.text()
        document.select(".post-content_item").firstOrNull { it.selectFirst(".summary-heading")?.text() == "Nome Alternativo" }
            ?.selectFirst(".summary-content")?.text()?.takeIf(String::isNotEmpty)?.let { alternative ->
                description = listOfNotNull(description, "Título alternativo: $alternative").joinToString("\n\n")
            }
        status = when (
            document.select(".post-status .post-content_item")
                .firstOrNull { it.selectFirst(".summary-heading")?.text() == "Status" }
                ?.selectFirst(".summary-content")?.text()?.lowercase()
        ) {
            "completo", "completa" -> SManga.COMPLETED
            "em andamento", "ongoing" -> SManga.ONGOING
            "hiato", "em hiato" -> SManga.ON_HIATUS
            "cancelado", "cancelada" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    private fun parseChapters(document: Document) = document.select(".listing-chapters-wrap .chapter-box")
        .mapNotNull { element ->
            val link = element.selectFirst("a") ?: return@mapNotNull null
            val name = link.selectFirst("strong")?.text()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val href = link.attr("abs:href").takeIf(String::isNotEmpty) ?: return@mapNotNull null
            SChapter.create().apply {
                url = href.toHttpUrl().encodedPath
                this.name = name
                chapter_number = CHAPTER_NUMBER_REGEX.find(name)?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
                date_upload = link.selectFirst("time")?.attr("datetime")?.let(Instant::parse)?.toEpochMilli() ?: 0L
            }
        }
        .sortedByDescending(SChapter::chapter_number)

    private class GenreFilter : Filter.Select<String>("Categoria/Gênero", GENRES.map { it.first }.toTypedArray()) {
        val selectedValue get() = GENRES[state].second.takeIf(String::isNotEmpty)
    }

    private companion object {
        val IMAGE_URL_REGEX = "https?:\\\\/\\\\/[^\"\\s]+".toRegex()
        val CHAPTER_NUMBER_REGEX = "(?i)cap[ií]tulo\\s+(\\d+(?:[.,]\\d+)?)".toRegex()
        val GENRES = listOf(
            "Todos" to "",
            "Mangá" to "manga",
            "Manhwa" to "manhwa",
            "Manhua" to "manhuas",
            "Light Novel" to "light-novels",
            "Ação" to "acao",
            "Aventura" to "aventura",
            "Comédia" to "comedia",
            "Drama" to "drama",
            "Fantasia" to "fantasia",
            "Romance" to "romance",
            "Adulto" to "adulto",
            "+18" to "18",
        )
    }
}
