package eu.kanade.tachiyomi.extension.pt.yaoifanclub

import eu.kanade.tachiyomi.multisrc.zeistmanga.Genre
import eu.kanade.tachiyomi.multisrc.zeistmanga.Status
import eu.kanade.tachiyomi.multisrc.zeistmanga.Type
import eu.kanade.tachiyomi.multisrc.zeistmanga.ZeistManga
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.annotation.Source
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

@Source
abstract class YaoiFanClub : ZeistManga() {

    private val bloggerBaseUrl = "https://yaoifanclube.blogspot.com"

    override fun popularMangaRequest(page: Int): Request = GET(bloggerBaseUrl, headers)

    override fun latestUpdatesRequest(page: Int): Request {
        val startIndex = 10 * (page - 1) + 1
        return GET(
            "$bloggerBaseUrl/feeds/posts/default/-/Series?alt=json&orderby=published&max-results=11&start-index=$startIndex",
            headers,
        )
    }

    override val popularMangaSelector = "#PopularPosts4 article"
    override val popularMangaSelectorTitle = ".post-title a"
    override val popularMangaSelectorUrl = popularMangaSelectorTitle

    private fun bloggerPath(url: String): String {
        val value = url.trim()
        if (value.startsWith("//")) return bloggerPath("https:$value")
        if (value.startsWith("http://") || value.startsWith("https://")) {
            val parsed = value.toHttpUrl()
            return buildString {
                append(parsed.encodedPath)
                if (parsed.encodedQuery != null) append('?').append(parsed.encodedQuery)
                if (parsed.encodedFragment != null) append('#').append(parsed.encodedFragment)
            }
        }
        return if (value.startsWith('/')) value else "/$value"
    }

    private fun normalize(manga: SManga): SManga = manga.apply { url = bloggerPath(url) }

    override fun popularMangaParse(response: Response): MangasPage = super.popularMangaParse(response).also { it.mangas.forEach(::normalize) }

    override fun latestUpdatesParse(response: Response): MangasPage = super.latestUpdatesParse(response).also { it.mangas.forEach(::normalize) }

    override fun searchMangaParse(response: Response): MangasPage = super.searchMangaParse(response).also { it.mangas.forEach(::normalize) }

    override fun chapterListParse(response: Response): List<SChapter> = super.chapterListParse(response).also { it.forEach { chapter -> chapter.url = bloggerPath(chapter.url) } }

    override fun mangaDetailsRequest(manga: SManga): Request = GET(bloggerBaseUrl + bloggerPath(manga.url), headers)

    override fun chapterListRequest(manga: SManga): Request = GET(bloggerBaseUrl + bloggerPath(manga.url), headers)

    override fun pageListRequest(chapter: SChapter): Request = GET(bloggerBaseUrl + bloggerPath(chapter.url), headers)

    override fun apiUrl(feed: String): okhttp3.HttpUrl.Builder = "$bloggerBaseUrl/feeds/posts/default/-/".toHttpUrl().newBuilder()
        .addPathSegment(feed)
        .addQueryParameter("alt", "json")

    override val useNewChapterFeed = true
    override val chapterCategory = "Chapter"

    override val hasFilters = true
    override val hasLanguageFilter = false
    override val hasGenreFilter = true
    override val hasStatusFilter = true

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "https://www.blogger.com/blogin.g?blogspotURL=$baseUrl/&type=blog&bpli=1")

    override fun getGenreList(): List<Genre> = listOf(
        Genre("ABO", "ABO"),
        Genre("Ação", "Ação"),
        Genre("Anjo", "Anjo"),
        Genre("Apocalipse", "Apocalipse"),
        Genre("Aventura", "Aventura"),
        Genre("Comédia", "Comédia"),
        Genre("Drama", "Drama"),
        Genre("Demência", "Demência"),
        Genre("Demônio", "Demônio"),
        Genre("Espaço", "Espaço"),
        Genre("Esporte", "Esporte"),
        Genre("Fantasma", "Fantasma"),
        Genre("Fantasia", "Fantasia"),
        Genre("Ficção", "Ficção"),
        Genre("Game", "Game"),
        Genre("Gore", "Gore"),
        Genre("Harem", "Harem"),
        Genre("Histórico", "Histórico"),
        Genre("Horror", "Horror"),
        Genre("Magia", "Magia"),
        Genre("Militar", "Militar"),
        Genre("Mistério", "Mistério"),
        Genre("Música", "Música"),
        Genre("Omegaverso", "Omegaverso"),
        Genre("Paródia", "Paródia"),
        Genre("Poderes", "Poderes"),
        Genre("Policial", "Policial"),
        Genre("Psicológico", "Psicológico"),
        Genre("Robô", "Robô"),
        Genre("Romance", "Romance"),
        Genre("Samurai", "Samurai"),
        Genre("Sobrenatural", "Sobrenatural"),
        Genre("Suspense", "Suspense"),
        Genre("Terror", "Terror"),
        Genre("Vampiro", "Vampiro"),
        Genre("Viagem no tempo", "Viagem no tempo"),
        Genre("Vida Cotidiana", "Vida Cotidiana"),
        Genre("Zumbi", "Zumbi"),
    )

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
}
