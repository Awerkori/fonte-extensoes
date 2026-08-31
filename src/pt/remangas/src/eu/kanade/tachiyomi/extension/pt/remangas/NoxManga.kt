package eu.kanade.tachiyomi.extension.pt.remangas

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

@Source
abstract class NoxManga : HttpSource() {
    override val supportsLatest = true

    private val apiPath = "/api/v1"

    private val apiClient by lazy { NixApiClient(network.client, baseUrl, headers) }

    override val client by lazy {
        network.client.newBuilder()
            .addInterceptor(apiClient)
            .build()
    }

    override fun popularMangaRequest(page: Int): Request = apiRequest(
        "/comics",
        "page" to page,
        "per_page" to PAGE_SIZE,
        "sort" to "popular",
        "period" to "all",
        "adult" to false,
    )

    override fun popularMangaParse(response: Response): MangasPage = parseComicList(response)

    override fun latestUpdatesRequest(page: Int): Request = apiRequest(
        "/chapters/recent",
        "page" to page,
        "per_page" to PAGE_SIZE,
        "unique" to true,
        "adult" to false,
        "sort" to "new",
    )

    override fun latestUpdatesParse(response: Response): MangasPage {
        val root = response.parseAs<JsonObject>()
        val mangas = root.array("data")
            .mapNotNull { (it as? JsonObject)?.toManga(latest = true) }
            .distinctBy { it.url }
        return MangasPage(mangas, root.hasNextPage())
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = apiRequest(
        "/comics",
        "q" to query.trim(),
        "page" to page,
        "per_page" to SEARCH_PAGE_SIZE,
        "adult" to false,
    )

    override fun searchMangaParse(response: Response): MangasPage = parseComicList(response)

    private fun parseComicList(response: Response): MangasPage {
        val root = response.parseAs<JsonObject>()
        val mangas = root.array("comics", "data")
            .mapNotNull { (it as? JsonObject)?.toManga() }
            .distinctBy { it.url }
        return MangasPage(mangas, root.hasNextPage())
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/manga/${manga.slug()}"

    override fun mangaDetailsRequest(manga: SManga): Request = apiRequest("/comics/slug/${manga.slug()}")

    override fun mangaDetailsParse(response: Response): SManga {
        val root = response.parseAs<JsonObject>()
        val comic = root.obj("comic") ?: root
        return SManga.create().apply {
            title = comic.text("title") ?: throw Exception("Título da obra não encontrado.")
            thumbnail_url = comic.text("cover", "cover_url")?.toAbsoluteUrl()
            description = comic.text("synopsis", "description")
            author = comic.people("authors", "author")
            artist = comic.people("artists", "artist")
            genre = comic.array("genres").mapNotNull { genre ->
                (genre as? JsonObject)?.text("name") ?: (genre as? JsonPrimitive)?.contentOrNull
            }.joinToString().ifBlank { null }
            status = when (comic.text("status")?.lowercase()) {
                "ongoing", "em andamento" -> SManga.ONGOING
                "completed", "complete", "completo" -> SManga.COMPLETED
                "hiatus", "on_hiatus", "hiato" -> SManga.ON_HIATUS
                "cancelled", "canceled", "cancelado" -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
        }
    }

    override fun chapterListRequest(manga: SManga): Request = apiRequest(
        "/comics/slug/${manga.slug()}/chapters",
        "page" to 1,
        "per_page" to MAX_CHAPTERS,
        "sort" to "newest",
    )

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url.substringBefore('?')

    override fun chapterListParse(response: Response): List<SChapter> {
        val root = response.parseAs<JsonObject>()
        val slugIndex = response.request.url.pathSegments.indexOf("slug")
        val mangaSlug = response.request.url.pathSegments.getOrNull(slugIndex + 1).orEmpty()
        return root.array("chapters", "data")
            .mapNotNull { (it as? JsonObject)?.toChapter(mangaSlug) }
            .distinctBy { it.url }
            .sortedByDescending { it.chapter_number }
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val chapterId = baseUrl.toHttpUrl().resolve(chapter.url)?.queryParameter(CHAPTER_ID_PARAM)
            ?: chapter.url.substringAfter("/$CHAPTER_ID_PARAM/", "").substringBefore('/').takeIf { it.isNotBlank() }
            ?: throw Exception("Atualize a lista de capítulos antes de abrir este capítulo.")
        return apiRequest("/chapters/$chapterId")
    }

    override fun pageListParse(response: Response): List<Page> {
        val root = response.parseAs<JsonObject>()
        val pages = root.array("pages", "data")
            .mapNotNull { it as? JsonObject }
            .sortedBy { it.number("number") ?: Int.MAX_VALUE.toFloat() }
            .mapNotNull { it.text("image_url", "url")?.toAbsoluteUrl() }
            .distinct()
        require(pages.isNotEmpty()) { "Nenhuma página encontrada para este capítulo." }
        return pages.mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    private fun apiRequest(path: String, vararg query: Pair<String, Any>): Request {
        val url = "$baseUrl$apiPath$path".toHttpUrl().newBuilder().apply {
            query.forEach { (name, value) -> addQueryParameter(name, value.toString()) }
        }.build()
        return GET(url, headers)
    }

    private fun JsonObject.toManga(latest: Boolean = false): SManga? {
        val slug = text(if (latest) "comic_slug" else "slug", "slug", "comic_slug") ?: return null
        val mangaTitle = text(if (latest) "comic_title" else "title", "title", "comic_title") ?: return null
        return SManga.create().apply {
            url = slug
            title = mangaTitle
            thumbnail_url = text(if (latest) "comic_cover" else "cover", "cover", "cover_url", "comic_cover")
                ?.toAbsoluteUrl()
        }
    }

    private fun JsonObject.toChapter(mangaSlug: String): SChapter? {
        val id = text("id") ?: return null
        val slug = text("slug") ?: return null
        val number = number("number") ?: return null
        val rawTitle = text("title").orEmpty().trim()
        val chapterName = when {
            rawTitle.isBlank() || rawTitle.toFloatOrNull() != null -> "Capítulo ${number.clean()}"
            rawTitle.startsWith("capítulo", ignoreCase = true) -> rawTitle
            else -> "Capítulo ${number.clean()} - $rawTitle"
        }
        return SChapter.create().apply {
            url = "/read/$mangaSlug/$slug?$CHAPTER_ID_PARAM=$id"
            name = chapterName
            chapter_number = number
            scanlator = text("scanlator_name") ?: obj("scanlator")?.text("name")
            date_upload = text("published_at", "created_at")?.toTimestamp() ?: 0L
        }
    }

    private fun JsonObject.hasNextPage(): Boolean {
        val current = number("page")?.toInt() ?: 1
        val total = number("total_pages")?.toInt() ?: current
        return current < total
    }

    private fun JsonObject.text(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.number(key: String): Float? = (get(key) as? JsonPrimitive)
        ?.contentOrNull
        ?.toFloatOrNull()

    private fun JsonObject.array(vararg keys: String): JsonArray = keys.firstNotNullOfOrNull { get(it) as? JsonArray } ?: JsonArray(emptyList())

    private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject

    private fun JsonObject.people(arrayKey: String, valueKey: String): String? {
        val values = array(arrayKey).mapNotNull {
            (it as? JsonObject)?.text("name") ?: (it as? JsonPrimitive)?.contentOrNull
        }
        return values.joinToString().ifBlank { text(valueKey) }
    }

    private fun String.toAbsoluteUrl(): String? {
        val value = trim()
        if (value.isEmpty()) return null
        return when {
            value.startsWith("//") -> "https:$value"
            else -> baseUrl.toHttpUrl().resolve(value)?.toString()
        }
    }

    private fun String.toTimestamp(): Long = runCatching {
        OffsetDateTime.parse(this, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli()
    }.getOrDefault(0L)

    private fun Float.clean(): String = if (this % 1f == 0f) toInt().toString() else toString()

    private fun SManga.slug(): String = url.substringAfter("/manga/", url).trim('/').substringBefore('?')

    companion object {
        private const val PAGE_SIZE = 24
        private const val SEARCH_PAGE_SIZE = 64
        private const val MAX_CHAPTERS = 500
        private const val CHAPTER_ID_PARAM = "nix_id"
    }
}
