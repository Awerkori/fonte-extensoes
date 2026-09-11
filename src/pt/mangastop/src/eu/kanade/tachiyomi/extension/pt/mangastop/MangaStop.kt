package eu.kanade.tachiyomi.extension.pt.mangastop

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.multisrc.mangathemesia.ClientHintsInterceptor
import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.lib.cookieinterceptor.CookieInterceptor
import keiyoushi.lib.randomua.addRandomUAPreference
import keiyoushi.lib.randomua.setRandomUserAgent
import keiyoushi.network.rateLimit
import keiyoushi.utils.firstInstanceOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import uy.kohesive.injekt.injectLazy
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class MangaStop :
    MangaThemesia(),
    ConfigurableSource {

    private val apiUrl get() = "$baseUrl/wp-json/mangastop/v1"

    private val json: Json by injectLazy()

    private val dateFormatCustom = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)

    override fun OkHttpClient.Builder.configureClient() = apply {
        addInterceptor(
            Interceptor { chain ->
                val request = chain.request()
                if (request.url.host.contains("images")) {
                    val newRequest = request.newBuilder()
                        .header("Sec-Fetch-Dest", "image")
                        .header("Sec-Fetch-Mode", "no-cors")
                        .header("Sec-Fetch-Site", "same-site")
                        .build()
                    chain.proceed(newRequest)
                } else {
                    chain.proceed(request)
                }
            },
        )
        addNetworkInterceptor(CookieInterceptor(baseUrl.substringAfter("//"), "wpmanga-ada" to "1"))
        addInterceptor(ClientHintsInterceptor())
        rateLimit(2)
    }

    override fun Headers.Builder.configureHeaders() = apply {
        set("Accept", "application/json, text/plain, */*")
        set("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
        set("Origin", baseUrl)
        set("Referer", "$baseUrl/")
        setRandomUserAgent()
    }

    // ============================== Popular ===============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val request = GET("$apiUrl/mais-populares?pagina=$page&por_pagina=20", headers)
        return popularMangaParse(client.newCall(request).awaitSuccess())
    }

    private fun popularMangaParse(response: okhttp3.Response): MangasPage {
        val data = json.decodeFromString<JsonObject>(response.body.string())
        val mangasArray = data["mangas"]?.jsonArray ?: return MangasPage(emptyList(), false)
        val paginacao = data["paginacao"]?.jsonObject

        val mangas = mangasArray.map {
            val obj = it.jsonObject
            SManga.create().apply {
                val id = obj["id"]?.jsonPrimitive?.content ?: ""
                val slug = obj["slug"]?.jsonPrimitive?.content ?: ""
                url = "/obra/$id#$slug"
                title = obj["titulo"]?.jsonPrimitive?.content ?: ""
                thumbnail_url = obj["thumbnail"]?.jsonPrimitive?.content ?: obj["capa_url"]?.jsonPrimitive?.content
            }
        }

        val hasNextPage = paginacao?.get("tem_proxima")?.jsonPrimitive?.booleanOrNull ?: false

        return MangasPage(mangas, hasNextPage)
    }

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val request = GET("$apiUrl/recentes?pagina=$page&por_pagina=24&tipo=Manhwa", headers)
        return popularMangaParse(client.newCall(request).awaitSuccess())
    }

    // =============================== Search ===============================

    // =============================== Search ===============================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val request = if (query.isNotBlank()) {
            val url = "$apiUrl/busca".toHttpUrl().newBuilder().apply {
                addQueryParameter("pagina", page.toString())
                addQueryParameter("q", query)

                val tipo = filters.firstInstanceOrNull<TypeFilter>()?.toUriPart()
                if (!tipo.isNullOrBlank()) {
                    addQueryParameter("tipo", tipo)
                }
            }.build()

            GET(url.toString(), headers)
        } else {
            val url = "$apiUrl/manga".toHttpUrl().newBuilder().apply {
                addQueryParameter("pagina", page.toString())
                addQueryParameter("por_pagina", "24")

                val tipo = filters.firstInstanceOrNull<TypeFilter>()?.toUriPart()
                if (!tipo.isNullOrBlank()) {
                    addQueryParameter("tipo", tipo)
                }
            }.build()

            GET(url.toString(), headers)
        }

        val response = client.newCall(request).awaitSuccess()
        val requestUrl = response.request.url.toString()
        if (requestUrl.contains("/busca")) {
            val data = json.decodeFromString<JsonObject>(response.body.string())
            val obras = data["obras"]?.jsonObject ?: return MangasPage(emptyList(), false)
            val mangasArray = obras["lista"]?.jsonArray ?: return MangasPage(emptyList(), false)

            val mangas = mangasArray.map {
                val obj = it.jsonObject
                SManga.create().apply {
                    val id = obj["id"]?.jsonPrimitive?.content ?: ""
                    val slug = obj["slug"]?.jsonPrimitive?.content ?: ""
                    url = "/obra/$id#$slug"
                    title = obj["titulo"]?.jsonPrimitive?.content ?: ""
                    thumbnail_url = obj["thumbnail"]?.jsonPrimitive?.content ?: obj["capa_url"]?.jsonPrimitive?.content
                }
            }

            val totalPaginas = obras["total_paginas"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
            val pagina = data["pagina"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
            val hasNextPage = pagina < totalPaginas

            return MangasPage(mangas, hasNextPage)
        } else {
            return popularMangaParse(response)
        }
    }

    // =========================== Manga Details & Chapters ============================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val id = manga.url.substringAfter("/obra/").substringBefore("#")
        val response = client.newCall(GET("$apiUrl/obra/$id", headers)).awaitSuccess()
        val obj = json.decodeFromString<JsonObject>(response.body.string())

        val details = if (fetchDetails) {
            SManga.create().apply {
                title = obj["titulo"]?.jsonPrimitive?.content ?: ""
                thumbnail_url = obj["capa_url"]?.jsonPrimitive?.content ?: obj["thumbnail"]?.jsonPrimitive?.content

                var syn = obj["sinopse"]?.jsonPrimitive?.content ?: obj["descricao_html"]?.jsonPrimitive?.content ?: obj["descricao"]?.jsonPrimitive?.content ?: ""
                syn = syn.replace("<p>", "").replace("</p>", "\n").replace("<br />", "\n").replace("<br>", "\n").trim()
                description = syn

                author = obj["manga_autor"]?.jsonArray?.joinToString { it.jsonObject["nome"]?.jsonPrimitive?.content ?: "" } ?: obj["autor"]?.jsonPrimitive?.content
                artist = obj["manga_artista"]?.jsonArray?.joinToString { it.jsonObject["nome"]?.jsonPrimitive?.content ?: "" } ?: obj["artista"]?.jsonPrimitive?.content

                genre = obj["generos"]?.jsonArray?.joinToString { it.jsonObject["nome"]?.jsonPrimitive?.content ?: "" }

                status = customParseStatus(obj["status"]?.jsonPrimitive?.content)
            }
        } else {
            manga
        }

        val chList = if (fetchChapters) {
            val chaptersArray = obj["capitulos"]?.jsonArray ?: return SMangaUpdate(details, emptyList())
            chaptersArray.map {
                val chObj = it.jsonObject
                SChapter.create().apply {
                    val chId = chObj["id"]?.jsonPrimitive?.content ?: ""
                    url = "/leitor/$chId"
                    chapter_number = chObj["numero"]?.jsonPrimitive?.content?.toFloatOrNull() ?: -1f
                    name = "Capítulo ${chObj["numero"]?.jsonPrimitive?.content}"
                    date_upload = parseDate(chObj["data"]?.jsonPrimitive?.content)
                }
            }.sortedByDescending { it.chapter_number }
        } else {
            chapters
        }

        return SMangaUpdate(details, chList)
    }

    override fun getMangaUrl(manga: SManga): String {
        val slug = manga.url.substringAfterLast("#")
        return "$baseUrl/manga/$slug"
    }

    private fun customParseStatus(status: String?) = when {
        status == null -> SManga.UNKNOWN
        status.contains("Ongoing", ignoreCase = true) -> SManga.ONGOING
        status.contains("Completed", ignoreCase = true) -> SManga.COMPLETED
        status.contains("Hiatus", ignoreCase = true) -> SManga.ON_HIATUS
        else -> SManga.UNKNOWN
    }

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl${chapter.url}"

    private fun parseDate(dateStr: String?): Long {
        if (dateStr == null) return 0L
        return try {
            dateFormatCustom.parse(dateStr)?.time ?: 0L
        } catch (e: ParseException) {
            0L
        }
    }

    // =============================== Pages ================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val id = chapter.url.substringAfter("/leitor/")
        val response = client.newCall(GET("$apiUrl/leitor/$id", headers)).awaitSuccess()
        val obj = json.decodeFromString<JsonObject>(response.body.string())
        val imagesArray = obj["imagens"]?.jsonArray ?: return emptyList()

        return imagesArray.mapIndexed { i, it ->
            val url = it.jsonObject["url"]?.jsonPrimitive?.content ?: ""
            Page(i, imageUrl = url)
        }
    }

    override fun imageRequest(page: Page): Request {
        val newHeaders = headersBuilder()
            .set("Accept", "image/avif,image/webp,image/png,image/jpeg,*/*")
            .set("Sec-Fetch-Dest", "image")
            .set("Sec-Fetch-Mode", "no-cors")
            .set("Sec-Fetch-Site", "same-site")
            .set("Referer", baseUrl)
            .build()
        return GET(page.imageUrl!!, newHeaders)
    }

    // ============================== Filters ===============================

    override fun getFilterList(data: JsonElement?) = FilterList(
        TypeFilter(),
    )

    class TypeFilter :
        Filter.Select<String>(
            "Tipo",
            arrayOf(
                "Qualquer",
                "Mangá",
                "Manhwa",
                "Manhua",
                "Pornhwa",
            ),
        ) {
        fun toUriPart(): String = when (state) {
            1 -> "Manga"
            2 -> "Manhwa"
            3 -> "Manhua"
            4 -> "Pornhwa"
            else -> ""
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addRandomUAPreference()
    }
}
