package eu.kanade.tachiyomi.extension.pt.mangaverde

import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
private const val PAGE_LIMIT = 24
private const val API_LANG = "pt-br"
private const val PREF_ADULT_KEY = "pref_adult_content"

@Source
abstract class MangaVerde :
    KeiSource(),
    ConfigurableSource {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val preferences by getPreferencesLazy()

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        rateLimit(permits = 3)
    }

    override fun Headers.Builder.configureHeaders(): Headers.Builder = apply {
        set("User-Agent", USER_AGENT)
        set("Referer", "$baseUrl/")
        set("Accept", "application/json, text/plain, */*")
        set("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
    }

    override fun imageRequest(page: Page): Request {
        val imageUrl = page.imageUrl
        if (imageUrl.isNullOrBlank()) {
            return super.imageRequest(page)
        }
        return buildMangaVerdeImageRequest(imageUrl, baseUrl, headers)
    }

    // ============================== Popular ==============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$baseUrl/api/mangas".toHttpUrl().newBuilder().apply {
            addQueryParameter("limit", PAGE_LIMIT.toString())
            addQueryParameter("page", page.toString())
            addQueryParameter("sort", "popular")
            addQueryParameter("order", "desc")
            addQueryParameter("lang", API_LANG)
            if (!showNsfwPref()) {
                addQueryParameter("isNsfw", "false")
            }
        }.build()

        val response = client.get(url, headers = headers)
        val listDto = json.decodeFromString<MangaVerdeListDto>(response.body.string())
        val mangas = listDto.mangas.map { it.toSManga() }
        val hasNextPage = listDto.page < listDto.totalPages
        return MangasPage(mangas, hasNextPage)
    }

    // ============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$baseUrl/api/mangas".toHttpUrl().newBuilder().apply {
            addQueryParameter("limit", PAGE_LIMIT.toString())
            addQueryParameter("page", page.toString())
            addQueryParameter("sort", "recent")
            addQueryParameter("order", "desc")
            addQueryParameter("lang", API_LANG)
            if (!showNsfwPref()) {
                addQueryParameter("isNsfw", "false")
            }
        }.build()

        val response = client.get(url, headers = headers)
        val listDto = json.decodeFromString<MangaVerdeListDto>(response.body.string())
        val mangas = listDto.mangas.map { it.toSManga() }
        val hasNextPage = listDto.page < listDto.totalPages
        return MangasPage(mangas, hasNextPage)
    }

    // ============================== Search & Filters =====================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val trimmedQuery = query.trim()
        val url = "$baseUrl/api/mangas".toHttpUrl().newBuilder().apply {
            addQueryParameter("limit", PAGE_LIMIT.toString())
            addQueryParameter("page", page.toString())
            addQueryParameter("lang", API_LANG)
            if (trimmedQuery.isNotEmpty()) {
                addQueryParameter("search", trimmedQuery)
            }
            applyFilters(this, filters, showNsfwPref())
        }.build()

        val response = client.get(url, headers = headers)
        val listDto = json.decodeFromString<MangaVerdeListDto>(response.body.string())
        val mangas = listDto.mangas.map { it.toSManga() }
        val hasNextPage = listDto.page < listDto.totalPages
        return MangasPage(mangas, hasNextPage)
    }

    private fun applyFilters(builder: HttpUrl.Builder, filters: FilterList, allowNsfw: Boolean) {
        var hasExplicitAge = false
        var hasSort = false
        var hasOrder = false

        for (filter in filters) {
            when (filter) {
                is SortFilter -> {
                    if (filter.selected.isNotEmpty() && filter.selected != "ALL") {
                        builder.setQueryParameter("sort", filter.selected)
                        hasSort = true
                    }
                }
                is OrderFilter -> {
                    if (filter.selected.isNotEmpty() && filter.selected != "ALL") {
                        builder.setQueryParameter("order", filter.selected)
                        hasOrder = true
                    }
                }
                is TypeFilter -> {
                    if (filter.selected.isNotEmpty() && filter.selected != "ALL") {
                        builder.setQueryParameter("type", filter.selected)
                    }
                }
                is StatusFilter -> {
                    if (filter.selected.isNotEmpty() && filter.selected != "ALL") {
                        builder.setQueryParameter("status", filter.selected)
                    }
                }
                is GenreFilter -> {
                    if (filter.selected.isNotEmpty() && filter.selected != "ALL") {
                        builder.setQueryParameter("genre", filter.selected)
                    }
                }
                is CensorshipFilter -> {
                    when (filter.selected) {
                        "UNCENSORED" -> builder.setQueryParameter("isUncensored", "true")
                        "CENSORED" -> builder.setQueryParameter("isUncensored", "false")
                    }
                }
                is AgeFilter -> {
                    hasExplicitAge = true
                    when (filter.selected) {
                        "ONLY_NSFW" -> builder.setQueryParameter("onlyNsfw", "true")
                        "SAFE_ONLY" -> builder.setQueryParameter("isNsfw", "false")
                        else -> {
                            if (!allowNsfw) {
                                builder.setQueryParameter("isNsfw", "false")
                            }
                        }
                    }
                }
                else -> {}
            }
        }

        if (!hasSort) {
            builder.setQueryParameter("sort", "recent")
        }
        if (!hasOrder) {
            builder.setQueryParameter("order", "desc")
        }
        if (!hasExplicitAge && !allowNsfw) {
            builder.setQueryParameter("isNsfw", "false")
        }
    }

    // ============================== Manga by URL =========================

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val path = url.encodedPath
        if (path.startsWith("/manga/")) {
            val slug = path.substringAfter("/manga/").substringBefore("/").trim()
            if (slug.isNotEmpty()) {
                return SManga.create().apply {
                    this.url = "/manga/$slug"
                    this.title = slug
                }
            }
        }
        return null
    }

    // ============================== Manga Details & Chapters =============

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val slug = manga.url.substringAfter("/manga/").substringBefore("/").trim()
        val fullUrl = "$baseUrl/api/mangas/$slug"
        val response = client.get(fullUrl, headers = headers)
        val body = response.body.string()
        val mangaDto = json.decodeFromString<MangaVerdeMangaDto>(body)

        val sManga = if (fetchDetails) mangaDto.toMangaDetails() else manga
        val chapterList = if (fetchChapters) {
            mangaDto.chapters
                .filter { !it.id.isNullOrBlank() && (it.language.isNullOrBlank() || it.language.equals(API_LANG, ignoreCase = true)) }
                .map { it.toSChapter() }
                .distinctBy { it.url }
        } else {
            chapters
        }

        return SMangaUpdate(sManga, chapterList)
    }

    // ============================== Pages ================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = chapter.url
        val fullUrl = if (chapterUrl.startsWith("http")) {
            chapterUrl
        } else {
            "$baseUrl${if (chapterUrl.startsWith("/")) "" else "/"}$chapterUrl"
        }

        val response = client.get(fullUrl, headers = headers)
        val body = response.body.string()
        val detailDto = json.decodeFromString<MangaVerdeChapterDetailDto>(body)

        val pages = detailDto.pages
            .filter { it.isNotBlank() }
            .mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl.trim()) }

        if (pages.isNotEmpty()) {
            return pages
        }

        throw Exception("Nenhuma página encontrada para este capítulo.")
    }

    // ============================== Filter Fetching ======================

    override val supportsFilterFetching: Boolean get() = true

    override suspend fun fetchFilterData(): JsonElement {
        val response = client.get("$baseUrl/api/genres?lang=$API_LANG", headers = headers)
        return json.parseToJsonElement(response.body.string())
    }

    override fun getFilterList(data: JsonElement?): FilterList = MangaVerdeFilters.get(data, showNsfwPref())

    // ============================== Preferences ==========================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_ADULT_KEY
            title = "Exibir conteúdo adulto"
            summary = "Inclui obras +18 no catálogo e na pesquisa."
            setDefaultValue(false)
        }.let(screen::addPreference)
    }

    private fun showNsfwPref() = preferences.getBoolean(PREF_ADULT_KEY, false)

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String {
        val id = chapter.url.substringAfterLast("/").trim()
        return "$baseUrl/reader/$id"
    }
}

internal fun buildMangaVerdeImageRequest(imageUrl: String, baseUrl: String, baseHeaders: Headers? = null): Request {
    val trimmed = imageUrl.trim().replace(" ", "%20")
    val fullUrl = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        trimmed
    } else {
        "$baseUrl${if (trimmed.startsWith("/")) "" else "/"}$trimmed"
    }

    val uri = fullUrl.toHttpUrlOrNull() ?: fullUrl.toHttpUrl()
    val builder = baseHeaders?.newBuilder() ?: Headers.Builder()

    builder
        .set("User-Agent", USER_AGENT)
        .set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
        .set("Sec-Fetch-Dest", "image")
        .set("Sec-Fetch-Mode", "no-cors")
        .removeAll("Origin")

    val host = uri.host.lowercase()
    when {
        host.contains("mangadex") -> {
            builder.set("Referer", "https://mangadex.org/")
            builder.set("Sec-Fetch-Site", "cross-site")
        }
        host.contains("mangaverde") -> {
            builder.set("Referer", "$baseUrl/")
            builder.set("Sec-Fetch-Site", "same-site")
        }
        host.contains("mangaread") -> {
            builder.set("Referer", "https://www.mangaread.org/")
            builder.set("Sec-Fetch-Site", "cross-site")
        }
        else -> {
            builder.removeAll("Referer")
            builder.set("Sec-Fetch-Site", "cross-site")
        }
    }

    return Request.Builder()
        .url(uri)
        .headers(builder.build())
        .get()
        .build()
}
