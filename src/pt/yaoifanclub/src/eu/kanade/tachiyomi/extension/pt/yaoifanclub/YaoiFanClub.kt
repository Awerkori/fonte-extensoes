package eu.kanade.tachiyomi.extension.pt.yaoifanclub

import eu.kanade.tachiyomi.multisrc.zeistmanga.Genre
import eu.kanade.tachiyomi.multisrc.zeistmanga.Status
import eu.kanade.tachiyomi.multisrc.zeistmanga.Type
import eu.kanade.tachiyomi.multisrc.zeistmanga.ZeistManga
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.annotation.Source
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import org.jsoup.nodes.Document

@Source
abstract class YaoiFanClub : ZeistManga() {
    private val bloggerBaseUrl = "https://yaoifanclube.blogspot.com"

    override fun popularMangaUrl(page: Int) = bloggerBaseUrl

    override fun apiUrl(feed: String): HttpUrl.Builder = "$bloggerBaseUrl/feeds/posts/default/-/".toHttpUrl().newBuilder()
        .addPathSegment(feed)
        .addQueryParameter("alt", "json")

    override fun getMangaUrl(manga: SManga) = bloggerBaseUrl + bloggerPath(manga.url)

    override fun getChapterUrl(chapter: SChapter) = bloggerBaseUrl + bloggerPath(chapter.url)

    override fun parsePopularManga(response: Response): MangasPage = super.parsePopularManga(response).also { it.mangas.forEach(::normalize) }

    override fun parseSearchManga(response: Response): MangasPage = super.parseSearchManga(response).also { it.mangas.forEach(::normalize) }

    override suspend fun getChapterList(feedUrl: String, doc: Document?): List<SChapter> = super.getChapterList(feedUrl, doc).also { chapters -> chapters.forEach { it.url = bloggerPath(it.url) } }

    override val popularMangaSelector = "#PopularPosts4 article"
    override val popularMangaSelectorTitle = ".post-title a"
    override val popularMangaSelectorUrl = popularMangaSelectorTitle

    override val useNewChapterFeed = true
    override val chapterCategory = "Chapter"

    override val hasFilters = true
    override val hasLanguageFilter = false
    override val hasGenreFilter = true
    override val hasStatusFilter = true

    override fun Headers.Builder.configureHeaders() = apply {
        set("Referer", "https://www.blogger.com/blogin.g?blogspotURL=$baseUrl/&type=blog&bpli=1")
    }

    override fun getGenreList() = listOf(
        "ABO", "Ação", "Anjo", "Apocalipse", "Aventura", "Comédia", "Drama", "Demência", "Demônio", "Espaço",
        "Esporte", "Fantasma", "Fantasia", "Ficção", "Game", "Gore", "Harem", "Histórico", "Horror", "Magia",
        "Militar", "Mistério", "Música", "Omegaverso", "Paródia", "Poderes", "Policial", "Psicológico", "Robô",
        "Romance", "Samurai", "Sobrenatural", "Suspense", "Terror", "Vampiro", "Viagem no tempo", "Vida Cotidiana", "Zumbi",
    ).map { Genre(it, it) }

    override fun getTypeList(): List<Type> = listOf(
        Type("Todos", ""),
        Type("Comic", "Comic"),
        Type("Doujinshi", "Doujinshi"),
        Type("Manga", "Manga"),
        Type("Manhua", "Manhua"),
        Type("Manhwa", "Manhwa"),
        Type("Oneshot", "Oneshot"),
        Type("Anime", "Anime"),
    )

    override fun getStatusList(): List<Status> = listOf(
        Status("Ativo", "Ativo"),
        Status("Completo", "Completo"),
        Status("Dropado", "Dropado"),
        Status("Em Breve", "Em Breve"),
    )

    private fun normalize(manga: SManga) = manga.apply { url = bloggerPath(url) }

    private fun bloggerPath(url: String): String {
        val value = url.trim()
        if (value.startsWith("//")) return bloggerPath("https:$value")
        if (value.startsWith("http://") || value.startsWith("https://")) {
            val parsed = value.toHttpUrl()
            return buildString {
                append(parsed.encodedPath)
                parsed.encodedQuery?.let { append('?').append(it) }
                parsed.encodedFragment?.let { append('#').append(it) }
            }
        }
        return if (value.startsWith('/')) value else "/$value"
    }
}
