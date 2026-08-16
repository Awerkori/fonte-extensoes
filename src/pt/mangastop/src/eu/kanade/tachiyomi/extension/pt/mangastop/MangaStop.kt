package eu.kanade.tachiyomi.extension.pt.mangastop

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.lib.cookieinterceptor.CookieInterceptor
import keiyoushi.lib.randomua.addRandomUAPreference
import keiyoushi.lib.randomua.setRandomUserAgent
import keiyoushi.network.rateLimit
import keiyoushi.utils.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class MangaStop :
    HttpSource(),
    ConfigurableSource {

    override val supportsLatest = true

    private val apiUrl get() = "$baseUrl/wp-json/mangastop/v1"

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            if (request.url.host.contains("images")) {
                chain.proceed(
                    request.newBuilder()
                        .header("Sec-Fetch-Dest", "image")
                        .header("Sec-Fetch-Mode", "no-cors")
                        .header("Sec-Fetch-Site", "same-site")
                        .build(),
                )
            } else {
                chain.proceed(request)
            }
        }
        .addNetworkInterceptor(
            CookieInterceptor(baseUrl.substringAfter("//"), "wpmanga-ada" to "1"),
        )
        .addInterceptor(ClientHintsInterceptor())
        .rateLimit(2)
        .build()

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("Accept", "application/json, text/plain, */*")
        .add("Origin", baseUrl)
        .add("Referer", "$baseUrl/")
        .setRandomUserAgent()

    // --- Popular ---

    override fun popularMangaRequest(page: Int): Request = GET("$apiUrl/mais-populares?pagina=$page&por_pagina=20", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val dto = response.parseAs<PopularDto>()
        val mangas = dto.mangas.map { it.toSManga() }
        return MangasPage(mangas, dto.pagina < dto.totalPaginas)
    }

    // --- Latest ---

    override fun latestUpdatesRequest(page: Int): Request = GET("$apiUrl/recentes?pagina=$page&por_pagina=24", headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val dto = response.parseAs<RecentesDto>()
        val mangas = dto.mangas
            .filter { !isAdult(it.tipo) }
            .map { it.toSManga() }
        return MangasPage(mangas, dto.paginacao.temProxima)
    }

    // --- Search ---

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = GET("$apiUrl/manga?pagina=$page&por_pagina=24&pesquisa=$query", headers)

    override fun searchMangaParse(response: Response): MangasPage {
        val dto = response.parseAs<SearchDto>()
        val mangas = dto.mangas.map { it.toSManga() }
        return MangasPage(mangas, dto.paginacao.temProxima)
    }

    override fun getMangaUrl(manga: SManga): String {
        val slug = manga.url.substringAfterLast("#")
        return "$baseUrl/manga/$slug"
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        val id = manga.url.substringBefore("#").substringAfterLast("/")
        return GET("$apiUrl/obra/$id", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val dto = response.parseAs<ObraDto>()
        return SManga.create().apply {
            title = dto.titulo
            thumbnail_url = dto.capaUrl
            description = dto.sinopse?.replace(Regex("<[^>]+>"), "")?.trim()
            author = dto.mangaAutor?.joinToString { it.nome }
                ?.takeUnless { it.isBlank() }
                ?: dto.autor
            artist = dto.mangaArtista?.joinToString { it.nome }
                ?.takeUnless { it.isBlank() }
                ?: dto.artista
            genre = dto.generos?.joinToString { it.nome }
            status = when (dto.status?.lowercase()) {
                "ongoing" -> SManga.ONGOING
                "completed" -> SManga.COMPLETED
                "hiatus" -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
        }
    }

    // --- Chapters (reuses obra endpoint which returns capitulos[]) ---

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val dto = response.parseAs<ObraDto>()
        return dto.capitulos.orEmpty().map { cap ->
            SChapter.create().apply {
                url = "/leitor/${cap.id}"
                name = cap.tituloPost ?: "Capítulo ${cap.numero}"
                chapter_number = cap.numero?.toFloatOrNull() ?: -1f
                date_upload = cap.dataPublicacao?.let { parseDate(it) } ?: 0L
            }
        }
    }

    // --- Pages ---

    override fun pageListRequest(chapter: SChapter): Request {
        val id = chapter.url.substringAfterLast("/leitor/")
        return GET("$apiUrl/leitor/$id", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val dto = response.parseAs<LeitorDto>()
        return dto.imagens.mapIndexed { i, img -> Page(i, "", img.url) }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

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

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addRandomUAPreference()
    }

    // --- Helpers ---

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale("pt", "BR"))

    private fun parseDate(raw: String): Long = runCatching {
        dateFormat.parse(raw.substringBefore(" "))!!.time
    }.getOrDefault(0L)

    // Adult content is identified by `tipo` field in the API response.
    private fun isAdult(tipo: String?): Boolean {
        if (tipo == null) return false
        val t = tipo.lowercase()
        return ADULT_TYPES.any { t.contains(it) }
    }

    companion object {
        private val ADULT_TYPES = setOf(
            "pornhwa", "hentai", "yaoi", "yuri", "adulto", "erotico", "eroge", "smut", "+18", "18+",
        )
    }
}

// --- DTOs ---

private fun MangaListItem.toSManga() = SManga.create().apply {
    url = "/obra/$id#$slug"
    title = titulo
    thumbnail_url = thumbnail
}

@Serializable
private class PopularDto(
    val mangas: List<MangaListItem>,
    val pagina: Int,
    @SerialName("total_paginas") val totalPaginas: Int,
)

@Serializable
private class RecentesDto(
    val mangas: List<MangaListItem>,
    val paginacao: Paginacao,
)

@Serializable
private class SearchDto(
    val mangas: List<MangaListItem>,
    val paginacao: Paginacao,
)

@Serializable
private class Paginacao(
    @SerialName("tem_proxima") val temProxima: Boolean,
)

@Serializable
private class MangaListItem(
    val id: Long,
    val slug: String,
    val titulo: String,
    val thumbnail: String,
    val tipo: String? = null,
)

@Serializable
private class ObraDto(
    val titulo: String,
    @SerialName("capa_url") val capaUrl: String? = null,
    val sinopse: String? = null,
    val status: String? = null,
    val autor: String? = null,
    val artista: String? = null,
    val generos: List<GeneroDto>? = null,
    @SerialName("manga_autor") val mangaAutor: List<PessoaDto>? = null,
    @SerialName("manga_artista") val mangaArtista: List<PessoaDto>? = null,
    val capitulos: List<CapituloDto>? = null,
)

@Serializable
private class GeneroDto(val nome: String)

@Serializable
private class PessoaDto(val nome: String)

@Serializable
private class CapituloDto(
    val id: Long,
    @SerialName("titulo_post") val tituloPost: String? = null,
    val numero: String? = null,
    @SerialName("data_publicacao") val dataPublicacao: String? = null,
)

@Serializable
private class LeitorDto(
    val imagens: List<ImagemDto>,
)

@Serializable
private class ImagemDto(val url: String)
