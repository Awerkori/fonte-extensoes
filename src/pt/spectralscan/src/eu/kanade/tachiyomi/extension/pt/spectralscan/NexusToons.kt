package eu.kanade.tachiyomi.extension.pt.spectralscan

import android.content.SharedPreferences
import android.util.Base64
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
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

@Source
abstract class NexusToons :
    KeiSource(),
    ConfigurableSource {

    override val supportsLatest = true

    private val preferences: SharedPreferences by getPreferencesLazy()

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        addInterceptor(NexusDecrypt.createInterceptor())
        rateLimit(3, 1.seconds)
    }

    private val apiHeaders by lazy {
        headers.newBuilder()
            .set("Accept", "application/json")
            .set("Accept-Encoding", "identity")
            .set("Referer", "$baseUrl/")
            .build()
    }

    private val onlyNsfw: Boolean
        get() = preferences.getBoolean(PREF_ONLY_NSFW_KEY, PREF_ONLY_NSFW_DEFAULT)

    // ==================== URL Helpers ==========================

    override fun getMangaUrl(manga: SManga) = "$baseUrl${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String {
        val parts = chapter.url.substringAfter("/read/").split("/")
        val chapterId = parts[0]
        val mangaSlug = parts.getOrNull(1) ?: ""
        return "$baseUrl/r/${encodeChapterUrl(chapterId, mangaSlug)}"
    }

    private fun encodeChapterUrl(chapterId: String, mangaSlug: String = ""): String {
        val timestamp = System.currentTimeMillis().toString(36)
        val padding = randomString(20 + Random.nextInt(11))
        val data = "$chapterId|$mangaSlug|$timestamp|$padding"

        val xored = xorCipher(data, CHAPTER_ENCRYPTION_KEY)
        val firstEncode = base64UrlEncode(xored)
        val secondEncode = base64UrlEncode("$firstEncode|${randomString(10)}")

        return if (secondEncode.length >= 64) {
            secondEncode
        } else {
            secondEncode + randomString(64 - secondEncode.length)
        }
    }

    private fun xorCipher(input: String, key: String): String = input.mapIndexed { i, char ->
        (char.code xor key[i % key.length].code).toChar()
    }.joinToString("")

    private fun base64UrlEncode(input: String): String {
        val bytes = input.map { it.code.toByte() }.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
            .replace('+', '-')
            .replace('/', '_')
            .trimEnd('=')
    }

    private fun randomString(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return buildString(length) {
            repeat(length) { append(chars.random()) }
        }
    }

    // ==================== Popular ==========================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$baseUrl/api/mangas".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", "50")
            .addQueryParameter("includeNsfw", "true")
            .apply { if (onlyNsfw) addQueryParameter("onlyNsfw", "true") }
            .addQueryParameter("sortBy", "views")
            .build()
        val result = client.get(url, apiHeaders).parseAs<MangaListResponse>()
        val mangas = result.data.orEmpty().map { it.toSManga() }
        val hasNextPage = result.page < result.pages
        return MangasPage(mangas, hasNextPage)
    }

    // ==================== Latest ==========================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$baseUrl/api/mangas".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", "50")
            .addQueryParameter("includeNsfw", "true")
            .apply { if (onlyNsfw) addQueryParameter("onlyNsfw", "true") }
            .addQueryParameter("sortBy", "lastChapterAt")
            .build()
        val result = client.get(url, apiHeaders).parseAs<MangaListResponse>()
        val mangas = result.data.orEmpty().map { it.toSManga() }
        val hasNextPage = result.page < result.pages
        return MangasPage(mangas, hasNextPage)
    }

    // ==================== Search ==========================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/api/mangas".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", "30")
            .addQueryParameter("includeNsfw", "true")

        if (query.isNotBlank()) {
            url.addQueryParameter("search", query)
        }

        var sortBy = "updatedAt"
        var sortOrder = "desc"
        var categoryMode = "or"
        val statusList = mutableListOf<String>()
        val typeList = mutableListOf<String>()
        val genreList = mutableListOf<String>()
        val themeList = mutableListOf<String>()

        filters.forEach { filter ->
            when (filter) {
                is SelectFilter -> {
                    val value = filter.selected()
                    if (value.isNotEmpty()) {
                        when (filter.parameter) {
                            "sortBy" -> sortBy = value
                            "sortOrder" -> sortOrder = value
                            "categoryMode" -> categoryMode = value
                        }
                    }
                }

                is CheckboxGroup -> {
                    val selected = filter.selected()
                    if (selected.isNotEmpty()) {
                        when (filter.parameter) {
                            "status" -> statusList.addAll(selected)
                            "type" -> typeList.addAll(selected)
                            "genres" -> genreList.addAll(selected)
                            "themes" -> themeList.addAll(selected)
                        }
                    }
                }

                else -> {}
            }
        }

        url.addQueryParameter("sortBy", sortBy)
        url.addQueryParameter("sortOrder", sortOrder)
        url.addQueryParameter("categoryMode", categoryMode)

        if (statusList.isNotEmpty()) {
            url.addQueryParameter("status", statusList.joinToString(","))
        }
        if (typeList.isNotEmpty()) {
            url.addQueryParameter("type", typeList.joinToString(","))
        }
        if (genreList.isNotEmpty()) {
            url.addQueryParameter("genres", genreList.joinToString(","))
        }
        if (themeList.isNotEmpty()) {
            url.addQueryParameter("themes", themeList.joinToString(","))
        }

        val result = client.get(url.build(), apiHeaders).parseAs<MangaListResponse>()
        val mangas = result.data.orEmpty().map { it.toSManga() }
        val hasNextPage = result.page < result.pages
        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val slug = getMangaSlug(url.toString())
        return client.get("$baseUrl/api/manga/$slug", apiHeaders).parseAs<MangaDetailsDto>().toSManga()
    }

    // ==================== Details & Chapters =======================

    private fun getMangaSlug(url: String) = url.substringAfter("/manga/").trimEnd('/')

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val slug = getMangaSlug(manga.url)
        val dto = client.get("$baseUrl/api/manga/$slug", apiHeaders).parseAs<MangaDetailsDto>()
        return SMangaUpdate(
            manga = if (fetchDetails) dto.toSManga() else manga,
            chapters = if (fetchChapters) dto.chapters.orEmpty().map { it.toSChapter(dto.slug) } else chapters,
        )
    }

    // ==================== Page ==========================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterId = chapter.url.substringAfter("/read/").substringBefore("/")
        val readResponse = client.get("$baseUrl/api/read/$chapterId", apiHeaders).parseAs<ReadResponse>()
        val pages = readResponse.pages
        if (pages.first().imageUrl != null) {
            return pages.mapIndexed { index, page ->
                Page(
                    index,
                    imageUrl = page.imageUrl!!,
                )
            }
        }
        return pages.mapIndexed { index, _ ->
            Page(
                index,
                imageUrl = "$baseUrl/api/p/${readResponse.pageToken}/$index",
            )
        }
    }

    override fun imageRequest(page: Page): Request {
        val imageHeaders = headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .build()
        return Request.Builder()
            .url(page.imageUrl!!)
            .headers(imageHeaders)
            .get()
            .build()
    }

    // ==================== Filters ==========================

    override fun getFilterList(data: JsonElement?) = FilterList(
        SelectFilter("Ordenar Por", "sortBy", sortList),
        SelectFilter("Ordem", "sortOrder", orderList),
        SelectFilter("Modo de Categoria", "categoryMode", categoryModeList),
        CheckboxGroup(
            "Status",
            "status",
            statusList.map { CheckboxItem(it.first, it.second) },
        ),
        CheckboxGroup(
            "Tipo",
            "type",
            typeList.map { CheckboxItem(it.first, it.second) },
        ),
        CheckboxGroup(
            "Gêneros",
            "genres",
            genreList.map { CheckboxItem(it.first, it.second) },
        ),
        CheckboxGroup(
            "Temas",
            "themes",
            themeList.map { CheckboxItem(it.first, it.second) },
        ),
    )

    // ==================== Settings ==========================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_ONLY_NSFW_KEY
            title = "Mostrar apenas conteúdo +18"
            summary = "Quando habilitado, exibe apenas conteúdo adulto nas listagens."
            setDefaultValue(PREF_ONLY_NSFW_DEFAULT)
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_ONLY_NSFW_KEY = "pref_only_nsfw"
        private const val PREF_ONLY_NSFW_DEFAULT = false
        private const val CHAPTER_ENCRYPTION_KEY = "NexusToons2026SecretKeyForChapterEncryption!@#\$"
    }
}
