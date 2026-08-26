package eu.kanade.tachiyomi.extension.pt.mangalivreorg

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class MangaLivreOrg : KeiSource() {

    override fun OkHttpClient.Builder.configureClient() = this

    override fun Headers.Builder.configureHeaders() = add("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
        .add("X-ML-Nonce", NONCE)

    override suspend fun getPopularManga(page: Int): MangasPage {
        val result = client.get("$baseUrl/home/most_read_period?adult_content=0&period=week")
            .parseAs<HomeMostReadDto>()
        return MangasPage(result.series.map(::toSManga).distinctBy { it.url }, false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val result = client.get("$baseUrl/home/releases?page=$page&adult_content=0")
            .parseAs<HomeReleasesDto>()
        return MangasPage(
            result.releases.map { release ->
                SManga.create().apply {
                    url = release.link.substringAfterLast('/')
                    title = release.name
                    thumbnail_url = release.image
                }
            }.distinctBy { it.url },
            result.releases.isNotEmpty(),
        )
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isNotBlank()) {
            val body = FormBody.Builder().add("search", query).build()
            val result = client.post("$baseUrl/lib/search/series.json", body)
                .parseAs<Map<String, List<SearchItemDto>>>()
            return MangasPage(result.values.flatten().map(SearchItemDto::toSManga), false)
        }
        return when (filters.firstInstanceOrNull<SortFilter>()?.selectedValue) {
            "views" -> getPopularManga(page)
            else -> getLatestUpdates(page)
        }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val slug = url.pathSegments.getOrNull(1)?.takeIf(String::isNotBlank) ?: return null
        return parseMangaPage(slug, client.get("$baseUrl/manga/$slug").asJsoup()).apply { initialized = true }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val slug = manga.url.substringAfterLast('/')
        val parsed = parseMangaPage(slug, client.get("$baseUrl/manga/$slug").asJsoup())
        val chapters = resolveSeriesId(parsed.title, slug)?.let { seriesId ->
            fetchChapterPages(seriesId).map { it.toSChapter(slug) }
        }.orEmpty().ifEmpty {
            fetchReleaseChapters(slug)
        }
            .distinctBy { it.url.substringAfterLast("/online/") }
            .sortedByDescending(SChapter::chapter_number)
        return SMangaUpdate(parsed, chapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val releaseId = Regex("/online/([^/?#]+)").find(chapter.url)?.groupValues?.get(1)
            ?: chapter.url.substringAfterLast('/').takeIf { it.matches(RELEASE_ID_REGEX) }
            ?: return emptyList()
        val result = client.get("$baseUrl/leitor/pages/$releaseId.json").parseAs<LegacyPagesDto>()
        return result.images.filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct().mapIndexed { index, image -> Page(index, imageUrl = image) }
    }

    override fun getMangaUrl(manga: SManga) = "$baseUrl/manga/${manga.url}"

    override fun getChapterUrl(chapter: SChapter) = "$baseUrl${chapter.url}"

    override val supportsFilterFetching get() = false

    override suspend fun fetchFilterData(): JsonElement = FilterData().toJsonElement()

    override fun getFilterList(data: JsonElement?) = FilterList(SortFilter(), PeriodFilter())

    private suspend fun resolveSeriesId(title: String, slug: String): String? {
        val currentSeries = client.get(
            API_URL.toHttpUrl().newBuilder()
                .addPathSegment("mangas")
                .addQueryParameter("q", title)
                .build(),
        ).parseAs<List<HomeSeriesDto>>()
            .firstOrNull { it.link.substringAfterLast('/') == slug }
            ?.id
        if (currentSeries != null) return currentSeries

        val recentSeries = client.get("$baseUrl/home/getNewSeries?type=&adult_content=0")
            .parseAs<HomeSeriesDtoContainer>().series
            .firstOrNull { it.link.substringAfterLast('/') == slug }
            ?.id
        if (recentSeries != null) return recentSeries

        val body = FormBody.Builder().add("search", title).build()
        val results = client.post("$baseUrl/lib/search/series.json", body)
            .parseAs<Map<String, List<SearchItemDto>>>().values.flatten()
        return results.firstOrNull { it.idSerie != null && it.toSManga().url == slug }
            ?.idSerie?.jsonPrimitive?.contentOrNull
            ?: results.firstOrNull { it.idSerie != null }?.idSerie?.jsonPrimitive?.contentOrNull
    }

    private suspend fun fetchChapterPages(seriesId: String): List<LegacyChapterDto> {
        val chapters = mutableListOf<LegacyChapterDto>()
        for (page in 1..MAX_CHAPTER_PAGES) {
            val current = client.get("$baseUrl/series/chapters_list.json?page=$page&id_serie=$seriesId")
                .parseAs<LegacyChapterListDto>().chapters
            if (current.isEmpty()) break
            chapters += current
        }
        return chapters.distinctBy { it.releases.values.firstOrNull()?.idRelease ?: it.idChapter }
    }

    private suspend fun fetchReleaseChapters(slug: String): List<SChapter> {
        val chapters = mutableListOf<HomeReleaseChapterDto>()
        for (page in 1..MAX_RELEASE_PAGES) {
            val releases = client.get("$baseUrl/home/releases?page=$page&adult_content=0")
                .parseAs<HomeReleasesDto>().releases
            if (releases.isEmpty()) break
            releases.firstOrNull { it.link.substringAfterLast('/') == slug }
                ?.let { chapters += it.chapters }
        }
        return chapters.distinctBy { it.url }.map { it.toSChapter() }
    }

    private fun toSManga(item: HomeSeriesDto) = SManga.create().apply {
        url = item.link.substringAfterLast('/')
        title = item.displayName
        thumbnail_url = item.thumbnail
    }

    private fun parseMangaPage(slug: String, document: org.jsoup.nodes.Document): SManga {
        val noscript = document.selectFirst("noscript")?.html()?.let(org.jsoup.Jsoup::parse)
        val title = noscript?.selectFirst("h1")?.text()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" - ")
            ?: slug.replace('-', ' ').replaceFirstChar(Char::uppercase)
        val description = noscript?.selectFirst("p")?.text()
            ?: document.selectFirst("meta[name=description]")?.attr("content").orEmpty()
        val genres = noscript?.select("a[href*='/lista-de-categorias/']")?.joinToString { it.text() }.orEmpty()
        val type = noscript?.select("strong")?.firstOrNull { it.text().equals("Tipo:", true) }?.parent()?.text()
            ?.substringAfter(':')?.trim()
        return SManga.create().apply {
            url = slug
            this.title = title
            this.description = listOf(description, type?.let { "Tipo: $it" }, genres.takeIf(String::isNotBlank)?.let { "Gêneros: $it" })
                .filterNotNull().filter(String::isNotBlank).joinToString("\n\n")
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
        }
    }

    private fun LegacyChapterDto.toSChapter(slug: String) = SChapter.create().apply {
        val release = releases.values.firstOrNull { it.link != null }
        url = release?.link ?: "/ler/$slug/online/${release?.idRelease ?: idChapter}/$number"
        name = "Capítulo ${number.formatNumber()}"
        chapter_number = number
        scanlator = release?.scanlators?.firstOrNull()?.name ?: scanName
        date_upload = date?.let { runCatching { SimpleDateFormat("dd/MM/yyyy", Locale.ROOT).parse(it)?.time }.getOrNull() } ?: 0L
    }

    private fun HomeReleaseChapterDto.toSChapter() = SChapter.create().apply {
        url = this@toSChapter.url
        name = "Capítulo ${number.formatNumber()}"
        chapter_number = number
    }

    private fun Float.formatNumber() = if (this % 1 == 0f) toInt().toString() else toString()

    private companion object {
        const val API_URL = "https://api.mangalivre.org/api/v1"
        const val NONCE = "3dce95d4540e54086a970da4ea44cf46"
        const val MAX_CHAPTER_PAGES = 100
        const val MAX_RELEASE_PAGES = 20
        val RELEASE_ID_REGEX = Regex("[0-9a-fA-F-]{8,}")
    }
}
