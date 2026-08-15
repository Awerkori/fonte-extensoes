package eu.kanade.tachiyomi.extension.pt.shadowmanga

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

@Source
class ShadowManga(
    override val lang: String,
    override val id: Long,
) : HttpSource() {
    override val name = "Shadow Manga"
    override val baseUrl = "https://shadow-mang.onrender.com"
    override val supportsLatest = true

    private val json = Json { ignoreUnknownKeys = true }

    override fun popularMangaRequest(page: Int): Request = catalogRequest(page, "")
    override fun popularMangaParse(response: Response): MangasPage = catalogParse(response)
    override fun latestUpdatesRequest(page: Int): Request = catalogRequest(page, "")
    override fun latestUpdatesParse(response: Response): MangasPage = catalogParse(response)
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = catalogRequest(page, query)
    override fun searchMangaParse(response: Response): MangasPage = catalogParse(response)

    private fun catalogRequest(page: Int, query: String): Request {
        val offset = (page - 1) * PAGE_SIZE
        val url = "$baseUrl/api/trending".toHttpUrl().newBuilder()
            .addQueryParameter("title", query)
            .addQueryParameter("offset", offset.toString())
            .build()
        return GET(url, headers)
    }

    private fun catalogParse(response: Response): MangasPage {
        val root = json.parseToJsonElement(response.body.string()).jsonObject
        val data = root["data"] as? JsonArray ?: JsonArray(emptyList())
        val mangas = data.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val mangaId = obj.string("id") ?: return@mapNotNull null
            val title = obj.string("title") ?: return@mapNotNull null
            SManga.create().apply {
                this.title = title
                url = "/manga/$mangaId"
                thumbnail_url = obj.firstString("image", "cover", "cover_url", "thumbnail", "thumbnail_url")?.toAbsoluteUrl()
                description = obj.string("description")
                genre = (obj["genres"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.joinToString()
            }
        }
        return MangasPage(mangas, data.size >= PAGE_SIZE)
    }

    override fun mangaDetailsParse(response: Response): SManga = catalogParse(response).mangas.firstOrNull()
        ?: throw Exception("A API do Shadow Manga não retornou os detalhes da obra.")

    override fun mangaDetailsRequest(manga: SManga): Request {
        val url = "$baseUrl/api/trending".toHttpUrl().newBuilder()
            .addQueryParameter("title", manga.title)
            .addQueryParameter("offset", "0")
            .build()
        return GET(url, headers)
    }

    override fun chapterListRequest(manga: SManga): Request {
        val mangaId = manga.url.substringAfterLast('/')
        return GET("$baseUrl/api/manga/$mangaId/chapters", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val root = json.parseToJsonElement(response.body.string()).jsonObject
        val data = root["data"] as? JsonArray ?: return emptyList()
        return data.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val chapterId = obj.string("id") ?: return@mapNotNull null
            val number = obj.string("chapter") ?: return@mapNotNull null
            SChapter.create().apply {
                name = "Capítulo $number"
                url = "/chapter/$chapterId"
                chapter_number = number.toFloatOrNull() ?: -1f
            }
        }
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val chapterId = chapter.url.substringAfterLast('/')
        return GET("$baseUrl/api/chapter/$chapterId", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val root = json.parseToJsonElement(response.body.string()).jsonObject
        if (root.string("type") != "images") return emptyList()
        val images = root["images"] as? JsonArray ?: return emptyList()
        return images.mapIndexedNotNull { index, element ->
            val image = (element as? JsonPrimitive)?.contentOrNull ?: return@mapIndexedNotNull null
            Page(index, "", image.toAbsoluteUrl())
        }
    }

    override fun imageUrlParse(response: Response): String = response.request.url.toString()

    override fun imageRequest(page: Page): Request {
        val imageUrl = requireNotNull(page.imageUrl)
        return GET(imageUrl, directImageHeaders(imageUrl))
    }

    private fun directImageHeaders(url: String): Headers {
        val host = runCatching { url.toHttpUrl().host }.getOrNull().orEmpty()
        return Headers.Builder().apply {
            set("User-Agent", USER_AGENT)
            set("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            set("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
            // Do not force Cache-Control: max-age=0 here. Let OkHttp/Yomotsu reuse
            // cached responses and keep persistent connections to the image CDN.
            if (host.endsWith("onrender.com")) {
                set("Referer", "$baseUrl/")
            }
        }.build()
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.firstString(vararg keys: String): String? = keys.firstNotNullOfOrNull { string(it) }

    private fun String.toAbsoluteUrl(): String = when {
        startsWith("http://") || startsWith("https://") -> this
        startsWith("//") -> "https:$this"
        startsWith("/") -> baseUrl + this
        else -> "$baseUrl/$this"
    }

    companion object {
        private const val PAGE_SIZE = 20
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0 Mobile Safari/537.36"
    }
}
