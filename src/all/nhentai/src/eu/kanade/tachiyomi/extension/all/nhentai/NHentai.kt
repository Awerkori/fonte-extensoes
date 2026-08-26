package eu.kanade.tachiyomi.extension.all.nhentai

import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import keiyoushi.annotation.Source
import keiyoushi.lib.randomua.addRandomUAPreference
import keiyoushi.lib.randomua.setRandomUserAgent
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

@Source
abstract class NHentai :
    KeiSource(),
    ConfigurableSource {
    private val apiUrl = "$baseUrl/api/v2"
    private val imageUrl = "https://i.nhentai.net"
    private val thumbnailUrl = "https://t3.nhentai.net"
    private val languageQuery: String
        get() = when (lang) {
            "en" -> "english"
            "ja" -> "japanese"
            "zh" -> "chinese"
            else -> ""
        }

    private val preferences: SharedPreferences by getPreferencesLazy()
    private var fullTitles = preferences.getString(TITLE_PREFERENCE, "full") == "full"
    private val shortenTitleRegex = Regex("""(\[[^]]*]|[({][^)}]*[)}])""")

    override fun OkHttpClient.Builder.configureClient() = rateLimit(4)

    override fun okhttp3.Headers.Builder.configureHeaders() = setRandomUserAgent(
        filterInclude = listOf("chrome"),
    )

    override suspend fun getPopularManga(page: Int): MangasPage {
        val query = languageQuery.takeIf(String::isNotBlank) ?: "\"\""
        return getListing(
            apiUrl.toHttpUrl().newBuilder()
                .addPathSegment("search")
                .addQueryParameter("query", query)
                .addQueryParameter("sort", "popular")
                .addQueryParameter("page", page.toString())
                .build(),
        )
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val builder = apiUrl.toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
        if (languageQuery.isBlank()) {
            builder.addPathSegment("galleries")
        } else {
            builder.addPathSegment("search")
                .addQueryParameter("query", "language:$languageQuery")
        }
        return getListing(builder.build())
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val filterQuery = filters.filterIsInstance<AdvSearchFilter>()
            .flatMap { filter ->
                filter.state.split(',')
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .map { value ->
                        val tag = value.removePrefix("-")
                        val quoted = filter.name != "Pages" && filter.name != "Uploaded"
                        "${if (value.startsWith('-')) "-" else ""}${filter.name}:${if (quoted) "\"$tag\"" else tag}"
                    }
            }
        val searchQuery = listOfNotNull(
            query.trim().takeIf(String::isNotBlank),
            languageQuery.takeIf(String::isNotBlank)?.let { "language:$it" },
            filterQuery.takeIf(List<String>::isNotEmpty)?.joinToString(" "),
        ).joinToString(" ").ifBlank { "\"\"" }

        val builder = apiUrl.toHttpUrl().newBuilder()
            .addPathSegment("search")
            .addQueryParameter("query", searchQuery)
            .addQueryParameter("page", page.toString())
        filters.filterIsInstance<SortFilter>().firstOrNull()?.let { filter ->
            builder.addQueryParameter("sort", filter.sortValues[filter.state])
        }
        return getListing(builder.build())
    }

    private suspend fun getListing(url: HttpUrl): MangasPage {
        val result = client.get(url).parseAs<GalleryListDto>()
        val currentPage = url.queryParameter("page")?.toIntOrNull() ?: 1
        return MangasPage(
            result.result.map(::summaryToManga),
            currentPage < result.num_pages && result.result.isNotEmpty(),
        )
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val id = galleryId(url.toString()) ?: return null
        return fetchGallery(id).toManga("/g/$id/")
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val id = galleryId(manga.url) ?: error("Invalid NHentai gallery URL: ${manga.url}")
        val gallery = fetchGallery(id)
        val updatedManga = if (fetchDetails) gallery.toManga(manga.url) else manga
        val updatedChapters = if (fetchChapters) listOf(gallery.toChapter(id)) else chapters
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val id = galleryId(chapter.url) ?: error("Invalid NHentai chapter URL: ${chapter.url}")
        return fetchGallery(id).pages.mapIndexed { index, page ->
            Page(index, imageUrl = "$imageUrl/${page.path.trimStart('/')}")
        }
    }

    private suspend fun fetchGallery(id: Long) = client.get("$apiUrl/galleries/$id").parseAs<GalleryDto>()

    private fun summaryToManga(summary: GallerySummary) = SManga.create().apply {
        setUrlWithoutDomain("/g/${summary.id}/")
        title = summaryTitle(summary)
        thumbnail_url = summary.thumbnail?.let { "$thumbnailUrl/${it.trimStart('/')}" }
        update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
    }

    private fun GalleryDto.toManga(url: String) = SManga.create().apply {
        setUrlWithoutDomain(url)
        title = galleryTitle(this@toManga.title)
        thumbnail_url = "$thumbnailUrl/${thumbnail.path.trimStart('/')}"
        status = SManga.COMPLETED
        author = tagsByType("artist")
        artist = tagsByType("artist")
        genre = tagsByType("tag")
        description = buildString {
            append("Pages: ", pages.size, "\n")
            append("Favorited by: ", num_favorites, "\n")
            tags.filter { it.type in setOf("category", "language", "parody", "character", "group") }
                .groupBy { it.type }
                .forEach { (type, values) -> append(type.replaceFirstChar { it.uppercase() }, ": ", values.joinToString { it.name }, "\n") }
        }
        update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
    }

    private fun GalleryDto.toChapter(id: Long) = SChapter.create().apply {
        name = "Chapter"
        date_upload = upload_date * 1000
        scanlator = tagsByType("group")
        setUrlWithoutDomain("/g/$id/")
    }

    private fun GalleryDto.tagsByType(type: String) = tags.filter { it.type == type }.joinToString { it.name }.ifBlank { null }

    private fun summaryTitle(summary: GallerySummary): String = listOfNotNull(summary.english_title, summary.japanese_title)
        .firstOrNull()
        ?.formatTitle()
        ?: "#${summary.id}"

    private fun galleryTitle(title: GalleryTitle): String = listOfNotNull(title.english, title.japanese, title.pretty)
        .firstOrNull()
        ?.formatTitle()
        ?: "Untitled"

    private fun String.formatTitle() = if (fullTitles) this else shortenTitleRegex.replace(this, "").trim()

    private fun galleryId(value: String): Long? = Regex("(?:/g/|/api/v2/galleries/)(\\d+)").find(value)?.groupValues?.get(1)?.toLongOrNull()

    override fun getFilterList(data: JsonElement?) = FilterList(
        Filter.Header("Separate tags with commas (,)"),
        Filter.Header("Prepend with dash (-) to exclude"),
        AdvSearchFilter("Tags"),
        AdvSearchFilter("Categories"),
        AdvSearchFilter("Groups"),
        AdvSearchFilter("Artists"),
        AdvSearchFilter("Parodies"),
        AdvSearchFilter("Characters"),
        Filter.Header("Uploaded units: h, d, w, m, y. Example: (>20d)"),
        AdvSearchFilter("Uploaded"),
        Filter.Header("Pages example: (>20)"),
        AdvSearchFilter("Pages"),
        Filter.Separator(),
        SortFilter(),
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = TITLE_PREFERENCE
            title = TITLE_PREFERENCE
            entries = arrayOf("Full Title", "Short Title")
            entryValues = arrayOf("full", "short")
            summary = "%s"
            setDefaultValue("full")
            setOnPreferenceChangeListener { _, value ->
                fullTitles = value == "full"
                true
            }
        }.also(screen::addPreference)
        screen.addRandomUAPreference()
    }

    class AdvSearchFilter(name: String) : Filter.Text(name)

    class SortFilter :
        Filter.Select<String>(
            "Sort By",
            arrayOf("Popular: All Time", "Popular: Month", "Popular: Week", "Popular: Today", "Recent"),
        ) {
        val sortValues = arrayOf("popular", "popular-month", "popular-week", "popular-today", "date")
    }

    private companion object {
        const val TITLE_PREFERENCE = "Display manga title as:"
    }
}
