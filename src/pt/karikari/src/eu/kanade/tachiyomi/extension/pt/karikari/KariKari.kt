package eu.kanade.tachiyomi.extension.pt.karikari

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
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import java.io.IOException

@Source
abstract class KariKari :
    KeiSource(),
    ConfigurableSource {

    override val supportsLatest = true

    override fun OkHttpClient.Builder.configureClient() = rateLimit(2)

    private val preferences by getPreferencesLazy()

    private val auth by lazy {
        KariKariAuth(preferences, network.client, AUTH_URL, ANON_KEY)
    }

    private val apiHeaders: Headers
        get() = apiHeaders()

    private fun apiHeaders(accessToken: String? = null): Headers = headersBuilder()
        .set("apikey", ANON_KEY)
        .set("Authorization", "Bearer ${accessToken ?: ANON_KEY}")
        .set("Accept", "application/json")
        .build()

    override suspend fun getPopularManga(page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        val mangas = parseRanking(client.get("$baseUrl/em-alta").asJsoup())
        return MangasPage(mangas, false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val ids = getRecentWorkIds(page)
        val pageIds = ids.drop((page - 1) * PAGE_SIZE).take(PAGE_SIZE + 1)
        val mangas = getWorksByIds(pageIds.take(PAGE_SIZE))
        return MangasPage(mangas, pageIds.size > PAGE_SIZE)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val genreId = filters.firstInstanceOrNull<GenreFilter>()?.selectedId
        val sort = filters.firstInstanceOrNull<SortFilter>()?.selectedValue ?: "visualizacoes"
        val minRating = filters.firstInstanceOrNull<RatingFilter>()?.selectedValue ?: 0.0
        val deepSearch = sort == "nota" || minRating > 0

        val url = api("obras")
            .addQueryParameter("select", WORK_LIST_SELECT)
            .addQueryParameter("publico", "eq.true")
            .addQueryParameter("ocultada", "eq.false")
            .apply {
                query.trim().takeIf(String::isNotBlank)?.let {
                    addQueryParameter("titulo", "ilike.*${sanitizeQuery(it)}*")
                }
                genreId?.let { addQueryParameter("or", "(genero_principal_id.eq.$it,genero_secundario_id.eq.$it)") }
                filters.firstInstanceOrNull<StatusFilter>()?.selectedValue?.let { addQueryParameter("status", "eq.$it") }
                filters.firstInstanceOrNull<AgeFilter>()?.selectedValues?.takeIf { it.isNotEmpty() }?.let {
                    addQueryParameter("classificacao_etaria", "in.(${it.joinToString(",")})")
                }
                when (filters.firstInstanceOrNull<TypeFilter>()?.selectedValue) {
                    "autoral" -> addQueryParameter("autoral", "eq.true")
                    "traducao" -> addQueryParameter("autoral", "eq.false")
                }
                addQueryParameter(
                    "order",
                    when (sort) {
                        "atualizado" -> "atualizado_em.desc.nullslast"
                        "criado" -> "created_at.desc"
                        else -> "visualizacoes.desc"
                    },
                )
            }
        var works: List<MangaDto> = if (deepSearch) {
            val all = mutableListOf<MangaDto>()
            var offset = 0
            do {
                val batch = client.get(
                    url.setQueryParameter("limit", WORK_BATCH.toString())
                        .setQueryParameter("offset", offset.toString()).build(),
                    apiHeaders,
                ).parseAs<List<MangaDto>>()
                all += batch
                offset += batch.size
            } while (batch.size == WORK_BATCH)
            all
        } else {
            client.get(
                url.setQueryParameter("limit", (PAGE_SIZE + 1).toString())
                    .setQueryParameter("offset", ((page - 1) * PAGE_SIZE).toString()).build(),
                apiHeaders,
            ).parseAs()
        }

        if (deepSearch) {
            val ratings = getRatings(works.map(MangaDto::id))
            if (minRating > 0) works = works.filter { ratings[it.id]?.let { rating -> rating.average >= minRating && rating.total >= 3 } == true }
            if (sort == "nota") works = works.sortedByDescending { ratings[it.id]?.average ?: 0.0 }
            val pageItems = works.drop((page - 1) * PAGE_SIZE).take(PAGE_SIZE + 1)
            return MangasPage(pageItems.take(PAGE_SIZE).map { it.toSManga() }, pageItems.size > PAGE_SIZE)
        }

        return MangasPage(works.take(PAGE_SIZE).map { it.toSManga() }, works.size > PAGE_SIZE)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.pathSegments.firstOrNull() != "obra") return null
        val slug = url.pathSegments.getOrNull(1)?.takeIf(String::isNotBlank) ?: return null
        val work = getWork(slug) ?: return null
        return buildManga(work)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val slug = manga.url.substringAfterLast('/').takeIf(String::isNotBlank)
            ?: return@coroutineScope SMangaUpdate(manga, chapters)
        val work = getWork(slug)
        val details = if (fetchDetails && work != null) async { buildManga(work) } else null
        val chapterList = if (fetchChapters && work != null) async { getChapters(work.id) } else null
        SMangaUpdate(details?.await() ?: manga, chapterList?.await() ?: chapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val slug = chapter.url.substringAfterLast('/').takeIf(String::isNotBlank) ?: return emptyList()
        val accessUrl = api("capitulos")
            .addQueryParameter("select", "status,publico,ocultada,excluido,visibilidade,publicacao_agendada,apenas_membros,exclusivo_apoia_campanha_id,obras(conteudo_adulto)")
            .addQueryParameter("slug", "eq.$slug")
            .build()
        val access = client.get(accessUrl, apiHeaders).parseAs<List<ChapterAccessDto>>().firstOrNull()
            ?: return emptyList()
        val isAdult = access.obras?.adult == true
        if (!access.isAccessible(allowAdult = isAdult)) return emptyList()

        val session = if (isAdult) {
            val hadSession = auth.hasStoredSession()
            auth.getValidSession() ?: throw IOException(
                if (hadSession) SESSION_EXPIRED_MESSAGE else ADULT_LOGIN_MESSAGE,
            )
        } else {
            null
        }

        val pagesUrl = api("capitulos")
            .addQueryParameter("select", "capitulos_paginas(ordem,imagem_path)")
            .addQueryParameter("slug", "eq.$slug")
            .build()
        val pages = client.get(pagesUrl, session?.let { apiHeaders(it.accessToken) } ?: apiHeaders)
            .parseAs<List<ChapterPagesDto>>().firstOrNull()?.pages.orEmpty()
        val imageUrls = pages.orderedImageUrls()
        if (imageUrls.isEmpty()) {
            val pageCountUrl = api("capitulos")
                .addQueryParameter("select", "capitulos_paginas(id)")
                .addQueryParameter("slug", "eq.$slug")
                .build()
            val pageCount = client.get(pageCountUrl, apiHeaders)
                .parseAs<List<ChapterPageIdsDto>>().firstOrNull()?.pages.orEmpty().size
            if (isAdult && pageCount > 0) throw IOException(ADULT_CONFIRMATION_MESSAGE)
            throw IOException(NO_PAGES_MESSAGE)
        }
        return imageUrls.mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }
    }

    override fun imageRequest(page: Page): Request = Request.Builder()
        .url(page.imageUrl!!)
        .header("Accept", "image/*")
        .get()
        .build()

    override val supportsFilterFetching = true

    override suspend fun fetchFilterData(): JsonElement = client.get(
        api("generos_obras")
            .addQueryParameter("select", "id,nome")
            .addQueryParameter("order", "nome.asc")
            .build(),
        apiHeaders,
    ).parseAs<List<GenreOption>>().let(::FilterData).toJsonElement()

    override fun getFilterList(data: JsonElement?): FilterList {
        val filterData = data?.parseAs<FilterData>() ?: FilterData()
        return FilterList(
            GenreFilter(filterData.genres),
            StatusFilter(),
            AgeFilter(),
            TypeFilter(),
            RatingFilter(),
            SortFilter(),
        )
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl${chapter.url}"

    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) = auth.setupPreferenceScreen(screen)

    private suspend fun getWork(slug: String): MangaDto? = client.get(
        api("obras")
            .addQueryParameter("select", WORK_DETAILS_SELECT)
            .addQueryParameter("slug", "eq.$slug")
            .addQueryParameter("publico", "eq.true")
            .addQueryParameter("ocultada", "eq.false")
            .build(),
        apiHeaders,
    ).parseAs<List<MangaDto>>().firstOrNull()

    private suspend fun buildManga(work: MangaDto): SManga = coroutineScope {
        val genreIds = listOfNotNull(work.primaryGenreId, work.secondaryGenreId).distinct()
        val genres = async {
            if (genreIds.isEmpty()) {
                emptyList()
            } else {
                client.get(
                    api("generos_obras").addQueryParameter("select", "id,nome")
                        .addQueryParameter("id", "in.(${genreIds.joinToString(",")})").build(),
                    apiHeaders,
                ).parseAs<List<GenreOption>>()
            }
        }
        val author = async {
            work.creatorId?.let {
                client.get(
                    api("usuarios").addQueryParameter("select", "nome")
                        .addQueryParameter("id", "eq.$it").build(),
                    apiHeaders,
                ).parseAs<List<AuthorDto>>().firstOrNull()?.nome
            }
        }
        work.toSManga(genres.await().map(GenreOption::nome), author.await())
    }

    private suspend fun getChapters(workId: String): List<SChapter> {
        val result = mutableListOf<ChapterDto>()
        var offset = 0
        do {
            val page = client.get(
                api("capitulos")
                    .addQueryParameter("select", CHAPTER_SELECT)
                    .addQueryParameter("obra_id", "eq.$workId")
                    .addQueryParameter("order", "ordem.desc")
                    .addQueryParameter("limit", CHAPTER_BATCH.toString())
                    .addQueryParameter("offset", offset.toString())
                    .build(),
                apiHeaders,
            ).parseAs<List<ChapterDto>>()
            result += page
            offset += page.size
        } while (page.size == CHAPTER_BATCH)
        return result.filter { !it.ocultada && !it.excluido && !it.unlisted && it.status == "Aprovado" }
            .map(ChapterDto::toSChapter)
    }

    private suspend fun getRecentWorkIds(page: Int): List<String> {
        val ids = LinkedHashSet<String>()
        var offset = 0
        val needed = page * PAGE_SIZE + 1
        while (ids.size < needed) {
            val rows = client.get(
                api("capitulos")
                    .addQueryParameter("select", "obra_id")
                    .addQueryParameter("publico", "eq.true")
                    .addQueryParameter("status", "eq.Aprovado")
                    .addQueryParameter("ocultada", "eq.false")
                    .addQueryParameter("excluido", "eq.false")
                    .addQueryParameter("nao_listado", "eq.false")
                    .addQueryParameter("publicacao_agendada", "eq.false")
                    .addQueryParameter("apenas_membros", "eq.false")
                    .addQueryParameter("exclusivo_apoia_campanha_id", "is.null")
                    .addQueryParameter("visibilidade", "eq.Disponível para todos")
                    .addQueryParameter("order", "publicado_em.desc")
                    .addQueryParameter("limit", RECENT_BATCH.toString())
                    .addQueryParameter("offset", offset.toString())
                    .build(),
                apiHeaders,
            ).parseAs<List<RecentChapterDto>>()
            ids += rows.distinctWorkIds()
            if (rows.size < RECENT_BATCH) break
            offset += rows.size
        }
        return ids.toList()
    }

    private suspend fun getWorksByIds(ids: List<String>): List<SManga> {
        if (ids.isEmpty()) return emptyList()
        val works = client.get(
            api("obras").addQueryParameter("select", WORK_LIST_SELECT)
                .addQueryParameter("id", "in.(${ids.joinToString(",")})").build(),
            apiHeaders,
        ).parseAs<List<MangaDto>>().associateBy(MangaDto::id)
        return ids.mapNotNull { works[it]?.toSManga() }
    }

    private suspend fun getRatings(ids: List<String>): Map<String, RatingDto> {
        if (ids.isEmpty()) return emptyMap()
        return client.get(
            api("view_obras_avaliacoes_media").addQueryParameter("select", "obra_id,media_avaliacao,total_avaliacoes")
                .addQueryParameter("obra_id", "in.(${ids.joinToString(",")})").build(),
            apiHeaders,
        ).parseAs<List<RatingDto>>().associateBy(RatingDto::workId)
    }

    private fun api(table: String): HttpUrl.Builder = "$API_URL/$table".toHttpUrl().newBuilder()

    private fun parseRanking(document: Document): List<SManga> = parseRankingCards(document).map { card ->
        SManga.create().apply {
            url = card.url
            title = card.title
            thumbnail_url = card.cover
        }
    }

    private fun sanitizeQuery(query: String): String = query.replace(Regex("[*,()]"), " ").trim().replace(Regex("\\s+"), " ")

    internal data class RankingCard(val url: String, val title: String, val cover: String?)

    companion object {
        private const val API_URL = "https://mlkrbaaowgpamvpxiqrw.supabase.co/rest/v1"
        private const val AUTH_URL = "https://mlkrbaaowgpamvpxiqrw.supabase.co/auth/v1"
        private const val PAGE_SIZE = 24
        private const val RECENT_BATCH = 200
        private const val CHAPTER_BATCH = 1000
        private const val WORK_BATCH = 1000
        private const val WORK_LIST_SELECT = "id,slug,titulo,capa_vertical_path,status,conteudo_adulto,classificacao_etaria"
        private const val WORK_DETAILS_SELECT = "id,slug,titulo,capa_vertical_path,descricao,status,conteudo_adulto,classificacao_etaria,autoral,publico,ocultada,criador_id,genero_principal_id,genero_secundario_id"
        private const val CHAPTER_SELECT = "id,titulo,slug,ordem,status,publico,ocultada,excluido,nao_listado,visibilidade,publicacao_agendada,apenas_membros,exclusivo_apoia_campanha_id,data_de_lancamento,publicado_em"
        private const val ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im1sa3JiYWFvd2dwYW12cHhpcXJ3Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzY3MzAzMDgsImV4cCI6MjA5MjMwNjMwOH0.AY77uVkjUYCh51o9T3DyXz1v-02sN2Kp3bRcur_JVjY"
        private const val ADULT_LOGIN_MESSAGE = "Esta obra possui conteúdo +18. Faça login na sua conta KariKari nas configurações da extensão e tente novamente."
        private const val ADULT_CONFIRMATION_MESSAGE = "Conteúdo +18 ainda não habilitado para esta conta. A KariKari exige a confirmação de maioridade antes da leitura."
        private const val SESSION_EXPIRED_MESSAGE = "Sua sessão da KariKari expirou. Faça login novamente nas configurações da extensão."
        private const val NO_PAGES_MESSAGE = "Nenhuma página encontrada. O capítulo pode ter mudado."

        internal fun parseRankingCards(document: Document): List<RankingCard> = document
            .select("a[href^=/obra/]")
            .mapNotNull { element ->
                val path = element.attr("href").substringBefore('?').takeIf { it.startsWith("/obra/") } ?: return@mapNotNull null
                val title = element.selectFirst("p")?.text()?.takeIf(String::isNotBlank)
                    ?: element.selectFirst("img[alt]")?.attr("alt")?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                RankingCard(path, title, element.selectFirst("img[src]")?.absUrl("src"))
            }
            .distinctBy(RankingCard::url)
    }
}
