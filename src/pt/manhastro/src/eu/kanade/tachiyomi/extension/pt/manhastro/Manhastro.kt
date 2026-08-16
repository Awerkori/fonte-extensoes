package eu.kanade.tachiyomi.extension.pt.manhastro

import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.brotli.BrotliInterceptor
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Manhastro :
    HttpSource(),
    ConfigurableSource {

    private val apiUrl = "https://api2.manhastro.net"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    override val client: OkHttpClient = network.client.newBuilder()
        .connectTimeout(30.seconds)
        .readTimeout(30.seconds)
        .apply {
            val index = networkInterceptors().indexOfFirst { it is BrotliInterceptor }
            if (index >= 0) interceptors().add(networkInterceptors().removeAt(index))
        }
        .rateLimit(2)
        .build()

    // dataClient removed

    // ============================== Popular ==============================

    override fun popularMangaRequest(page: Int) = GET("$apiUrl/dados?sort=views&order=desc&limit=100&page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val result = response.parseAs<ApiResponse<List<MangaDto>>>(transform = ::cleanJsonResponse)
        val mangas = result.data.map { it.toSManga() }

        val hasNextPage = result.data.size >= 20

        return MangasPage(mangas, hasNextPage)
    }

    // ============================== Latest ==============================

    override fun latestUpdatesRequest(page: Int): okhttp3.Request = GET("$apiUrl/lancamentos?p=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val result = response.parseAs<ApiResponse<List<MangaDto>>>(transform = ::cleanJsonResponse)

        val mangas = result.data.map { it.toSManga() }

        val hasNextPage = result.data.size >= 100

        return MangasPage(mangas, hasNextPage)
    }

    // ============================== Search ==============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): okhttp3.Request {
        val url = "$apiUrl/dados".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())

        if (query.isNotBlank()) {
            url.addQueryParameter("nome", query)
        }

        var sortOption = "views"
        var sortOrder = "desc"

        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> {
                    when (filter.selected) {
                        "popular" -> {
                            sortOption = "views"
                            sortOrder = "desc"
                        }
                        "recent" -> {
                            sortOption = "ultimo_capitulo"
                            sortOrder = "desc"
                        }
                        "alphabetical" -> {
                            sortOption = "titulo"
                            sortOrder = "asc"
                        }
                        "chapters" -> {
                            sortOption = "capitulos"
                            sortOrder = "desc"
                        }
                    }
                }
                is TypeFilter -> {
                    val types = filter.state.filter { it.state }.map { it.value }
                    if (types.isNotEmpty()) {
                        url.addQueryParameter("categoria", types.joinToString(","))
                    }
                }
                is GenreFilter -> {
                    val genres = filter.state.filter { it.state }.map { it.value }
                    if (genres.isNotEmpty()) {
                        url.addQueryParameter("generos", genres.joinToString(","))
                    }
                }
                else -> {}
            }
        }

        url.addQueryParameter("sort", sortOption)
        url.addQueryParameter("order", sortOrder)
        url.addQueryParameter("limit", "100")

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val result = response.parseAs<ApiResponse<List<MangaDto>>>(transform = ::cleanJsonResponse)
        val mangas = result.data.map { it.toSManga() }

        val meta = result.meta
        val hasNextPage = meta?.hasMore ?: false

        return MangasPage(mangas, hasNextPage)
    }

    private fun String.normalize(): String = java.text.Normalizer.normalize(this, java.text.Normalizer.Form.NFD)
        .replace(Regex("[\\p{InCombiningDiacriticalMarks}]"), "")

    override fun getFilterList() = getFilters()

    // ============================== Details ==============================

    override fun mangaDetailsRequest(manga: SManga): okhttp3.Request {
        val mangaId = manga.url.substringAfterLast("/")
        return GET("$apiUrl/dados?manga_id=$mangaId", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val result = response.parseAs<ApiResponse<List<MangaDto>>>(transform = ::cleanJsonResponse)
        val mangaDto = result.data.firstOrNull() ?: throw Exception("Manga not found")
        return mangaDto.toSManga()
    }

    // ============================== Chapters ==============================

    override fun chapterListRequest(manga: SManga): okhttp3.Request {
        val mangaId = manga.url.substringAfterLast("/")
        return GET("$apiUrl/dados/$mangaId", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val result = response.parseAs<ApiResponse<List<ChapterDto>>>(transform = ::cleanJsonResponse)

        return result.data.map { chapter ->
            SChapter.create().apply {
                url = "/capitulo/${chapter.capituloId}"
                name = chapter.capituloNome
                chapter_number = extractChapterNumber(chapter.capituloNome)
                date_upload = DATE_FORMAT.tryParse(chapter.capituloData)
            }
        }.sortedByDescending { it.chapter_number }
    }

    private fun extractChapterNumber(name: String): Float {
        val regex = Regex("""(\d+(?:\.\d+)?)""")
        val match = regex.find(name)
        return match?.value?.toFloatOrNull() ?: -1f
    }

    // ============================== Pages ==============================

    override fun pageListRequest(chapter: SChapter) = GET("$apiUrl/paginas/${chapter.url.substringAfterLast("/")}", headers)

    override fun pageListParse(response: Response): List<Page> {
        val result = response.parseAs<PagesResponse>(transform = ::cleanJsonResponse)
        val chapter = result.data.chapter ?: return emptyList()

        return chapter.data.mapIndexed { i, filename ->
            Page(i, imageUrl = "${chapter.baseUrl}/${chapter.hash}/$filename")
        }
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    // ============================== URLs ==============================

    override fun getMangaUrl(manga: SManga): String {
        val mangaId = manga.url.substringAfterLast("/")
        return "$baseUrl/manga/$mangaId"
    }

    override fun getChapterUrl(chapter: SChapter): String {
        val chapterId = chapter.url.substringAfterLast("/")
        return "$baseUrl/capitulo/$chapterId"
    }

    // ============================== Helpers ==============================

    private fun cleanJsonResponse(body: String): String = body.removePrefix("\uFEFF")
        .removePrefix(")]}'")
        .removePrefix(",")
        .removePrefix("_")
        .trim()

    private fun MangaDto.toSManga() = SManga.create().apply {
        url = "/manga/$mangaId"
        title = if (useEnglishTitle) {
            titulo.takeIf { it.isNotBlank() } ?: displayTitle
        } else {
            displayTitle
        }
        description = displayDescription
        genre = generos.joinToString()
        thumbnail_url = thumbnailUrl
        status = SManga.UNKNOWN
    }

    private val useEnglishTitle: Boolean
        get() =
            preferences.getBoolean(ENGLISH_TITLE_PREF, false)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = ENGLISH_TITLE_PREF
            title = "Títulos em inglês"
            summary = "Use títulos em inglês como principal quando disponível. (Requer ativar \"Atualizar os títulos dos mangás da biblioteca para corresponder à fonte\" em \"Avançado\")"
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    companion object {
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
        private const val ENGLISH_TITLE_PREF = "englishTitlePref"
        private const val PER_PAGE = 30
    }
}
