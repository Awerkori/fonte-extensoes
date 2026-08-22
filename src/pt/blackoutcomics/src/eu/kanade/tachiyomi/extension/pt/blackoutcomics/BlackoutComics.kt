package eu.kanade.tachiyomi.extension.pt.blackoutcomics

import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.addCookie
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParseDate
import kotlinx.serialization.json.JsonElement
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.ByteString.Companion.decodeBase64
import org.jsoup.nodes.Document
import java.time.format.DateTimeFormatter

@Source
abstract class BlackoutComics :
    KeiSource(),
    ConfigurableSource {

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()
    private var loggedIn = false

    override fun Headers.Builder.configureHeaders() = apply {
        set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
        set("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
        set("Sec-Fetch-Dest", "document")
        set("Sec-Fetch-Mode", "navigate")
        set("Sec-Fetch-Site", "same-origin")
        set("Sec-Fetch-User", "?1")
        set("Upgrade-Insecure-Requests", "1")
    }

    override fun OkHttpClient.Builder.configureClient() = apply {
        rateLimit(2)
        addCookie {
            val now = System.currentTimeMillis()
            val expires = now + 6 * 24 * 60 * 60 * 1000L
            listOf("age_gate_consent" to "%7B%22consentAt%22%3A$now%2C%22expiresAt%22%3A$expires%7D")
        }
    }

    override fun getMangaUrl(manga: SManga): String {
        val path = if (manga.url.startsWith("/")) manga.url else "/${manga.url}"
        return if (manga.url.startsWith("http")) manga.url else "$baseUrl$path"
    }

    override fun getChapterUrl(chapter: SChapter): String {
        val path = if (chapter.url.startsWith("/")) chapter.url else "/${chapter.url}"
        return if (chapter.url.startsWith("http")) chapter.url else "$baseUrl$path"
    }

    // ============================== Popular ==============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)

        val document = client.get("$baseUrl/ranking").asJsoup()
        val mangas = document.select("div.ranking-grid a.ranking-item, a.ranking-item").mapNotNull { element ->
            val href = element.attr("href")
            val title = element.selectFirst("div.card-title span")?.text()
                ?: element.selectFirst("img")?.attr("alt")
                ?: return@mapNotNull null
            val cover = element.selectFirst("img")?.absUrl("src")

            SManga.create().apply {
                setUrlWithoutDomain(href)
                this.title = title
                thumbnail_url = cover
            }
        }

        return MangasPage(mangas, false)
    }

    // ============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)

        val document = client.get("$baseUrl/atualizados-recente").asJsoup()
        val mangas = document.select("div.webtoon-grid a.webtoon-card, a.webtoon-card").mapNotNull { element ->
            val href = element.attr("href")
            val title = element.selectFirst("div.card-title span")?.text()
                ?: element.selectFirst("img")?.attr("alt")
                ?: return@mapNotNull null
            val cover = element.selectFirst("img")?.absUrl("src")

            SManga.create().apply {
                setUrlWithoutDomain(href)
                this.title = title
                thumbnail_url = cover
            }
        }

        return MangasPage(mangas, false)
    }

    // ============================== Search ===============================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)

        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("comics")
            if (query.isNotEmpty()) {
                addQueryParameter("src", query)
            }
            filters.firstInstanceOrNull<StatusFilter>()?.toUriPart()?.takeIf { it.isNotEmpty() }?.let {
                addQueryParameter("status", it)
            }
            filters.firstInstanceOrNull<GenreFilter>()?.toUriPart()?.takeIf { it.isNotEmpty() }?.let {
                addQueryParameter("gen", it)
            }
            filters.firstInstanceOrNull<OrderFilter>()?.toUriPart()?.takeIf { it.isNotEmpty() }?.let {
                addQueryParameter("order", it)
            }
        }.build()

        val document = client.get(url).asJsoup()
        val mangas = document.select("div.webtoon-grid a.webtoon-card, a.webtoon-card").mapNotNull { element ->
            val href = element.attr("href")
            val title = element.selectFirst("div.card-title span")?.text()
                ?: element.selectFirst("img")?.attr("alt")
                ?: return@mapNotNull null
            val cover = element.selectFirst("img")?.absUrl("src")

            SManga.create().apply {
                setUrlWithoutDomain(href)
                this.title = title
                thumbnail_url = cover
            }
        }

        return MangasPage(mangas, false)
    }

    // ============================== Manga Details & Chapters =============

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val segments = url.pathSegments.filter { it.isNotEmpty() }
        if (segments.size < 2 || segments[0] != "comics") return null
        val id = segments[1]
        if (id.toLongOrNull() == null) return null

        val mangaPath = "/comics/$id"
        val document = client.get("$baseUrl$mangaPath").asJsoup()
        return parseMangaDetails(document).apply {
            this.url = mangaPath
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        val updatedManga = if (fetchDetails) parseMangaDetails(document) else manga
        val updatedChapters = if (fetchChapters) parseChapterList(document, manga) else chapters
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst("h1.project-title")?.text()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.substringBefore(" |")
            ?: ""

        author = document.selectFirst("div.project-quick-info span.quick-info-item:has(.fa-pen-nib) strong")?.text()
        artist = document.selectFirst("div.project-quick-info span.quick-info-item:has(.fa-palette) strong")?.text()

        genre = document.select("div.project-genres span.genre-tag").joinToString { it.text() }.takeIf { it.isNotEmpty() }

        description = buildString {
            document.selectFirst("div.project-description p")?.text()?.let { append(it) }
            document.selectFirst("p.project-subtitle")?.text()?.takeIf { it.isNotEmpty() }?.let {
                if (isNotEmpty()) append("\n\n")
                append("Título alternativo: ").append(it)
            }
        }

        thumbnail_url = document.selectFirst("img.project-cover")?.absUrl("src")
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")

        val statusText = document.selectFirst("div.detail-item:has(span.detail-label:contains(Status)) span.status-pill")?.text().orEmpty()
        status = when {
            statusText.contains("Completo", ignoreCase = true) -> SManga.COMPLETED
            statusText.contains("Em Lançamento", ignoreCase = true) || statusText.contains("Lançamento", ignoreCase = true) -> SManga.ONGOING
            statusText.contains("Hiato", ignoreCase = true) -> SManga.ON_HIATUS
            statusText.contains("Cancelado", ignoreCase = true) -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    private fun parseChapterList(document: Document, manga: SManga): List<SChapter> {
        val chapterElements = document.select("ol#tab-capitulos-list li, ol.list-ep li, div.chapter-link-wrap")
        if (chapterElements.isNotEmpty()) {
            val chapters = chapterElements.mapNotNull { item ->
                val linkWrap = if (item.hasClass("chapter-link-wrap")) item else item.selectFirst("div.chapter-link-wrap") ?: item
                val onclick = linkWrap.attr("onclick")
                val capNumText = linkWrap.selectFirst("div.cell-num span.num, span.num")?.text()
                    ?.replace("Capítulo", "", ignoreCase = true)
                    ?.replace("Cap", "", ignoreCase = true)
                    ?.trim()
                val capNum = capNumText?.toFloatOrNull() ?: -1f

                val chapterUrl = CHAPTER_URL_REGEX.find(onclick)?.value
                    ?: linkWrap.selectFirst("a[href]")?.attr("href")
                    ?: if (capNum >= 0) {
                        val mangaPath = if (manga.url.startsWith("/")) manga.url else "/${manga.url}"
                        "$mangaPath/ler/capitulo-${capNumText ?: capNum.toInt()}"
                    } else {
                        null
                    }
                    ?: return@mapNotNull null

                val titleText = linkWrap.selectFirst("div.cell-title strong")?.text()?.takeIf { it.isNotBlank() }
                val timeAttr = linkWrap.selectFirst("time[datetime]")?.attr("datetime")

                SChapter.create().apply {
                    setUrlWithoutDomain(chapterUrl)
                    name = buildString {
                        if (!capNumText.isNullOrEmpty()) {
                            append("Capítulo ").append(capNumText)
                        } else {
                            append("Capítulo")
                        }
                        if (!titleText.isNullOrEmpty()) {
                            append(" - ").append(titleText)
                        }
                    }
                    chapter_number = capNum
                    date_upload = DATE_FORMATTER.tryParseDate(timeAttr)
                }
            }
            if (chapters.isNotEmpty()) return chapters
        }

        return document.select("div.chapters-modal-item").mapNotNull { item ->
            val numText = item.selectFirst("span.chapters-modal-num")?.text().orEmpty()
            val capNumText = numText.replace("Capítulo", "", ignoreCase = true).replace("Cap", "", ignoreCase = true).trim()
            val capNum = capNumText.toFloatOrNull() ?: -1f
            val mangaPath = if (manga.url.startsWith("/")) manga.url else "/${manga.url}"
            val chapterUrl = "$mangaPath/ler/capitulo-${capNumText.ifEmpty { capNum.toInt().toString() }}"

            SChapter.create().apply {
                setUrlWithoutDomain(chapterUrl)
                name = numText.ifEmpty { "Capítulo $capNumText" }
                chapter_number = capNum
            }
        }
    }

    // ============================== Pages & Login ========================

    private suspend fun ensureLogin() {
        val email = preferences.getString(PREF_EMAIL, "")?.trim().orEmpty()
        val password = preferences.getString(PREF_PASSWORD, "")?.trim().orEmpty()
        if (email.isEmpty() || password.isEmpty()) return

        if (!loggedIn) login(email, password)
    }

    private suspend fun login(email: String, password: String) {
        val homeDoc = client.get(baseUrl).asJsoup()
        val csrfToken = homeDoc.selectFirst("meta[name='csrf-token']")?.attr("content").orEmpty()

        val formBody = FormBody.Builder()
            .add("_token", csrfToken)
            .add("USE_EMAIL", email)
            .add("password", password)
            .build()

        val loginHeaders = headersBuilder()
            .set("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .set("X-CSRF-TOKEN", csrfToken)
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Referer", "$baseUrl/")
            .set("Origin", baseUrl)
            .build()

        client.post("$baseUrl/entrar", loginHeaders, formBody).close()
        loggedIn = true
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        ensureLogin()

        val mangaPath = chapter.url.substringBefore("/ler")
        val mangaUrl = if (mangaPath.startsWith("http")) mangaPath else "$baseUrl$mangaPath"
        val chapterUrl = getChapterUrl(chapter)

        val chapterHeaders = headersBuilder()
            .set("Referer", mangaUrl)
            .build()

        var document = client.get(chapterUrl, chapterHeaders).asJsoup()
        var payload = document.selectFirst("#reader-payload")?.attr("data-payload")

        if (payload.isNullOrEmpty()) {
            val email = preferences.getString(PREF_EMAIL, "")?.trim().orEmpty()
            val password = preferences.getString(PREF_PASSWORD, "")?.trim().orEmpty()
            if (email.isNotEmpty() && password.isNotEmpty()) {
                login(email, password)
                document = client.get(chapterUrl, chapterHeaders).asJsoup()
                payload = document.selectFirst("#reader-payload")?.attr("data-payload")
            }
        }

        if (payload.isNullOrEmpty()) {
            throw Exception("Configure seu email e senha nas configurações da extensão.")
        }

        val jsonString = payload.decodeBase64()?.utf8()
            ?: throw Exception("Falha ao decodificar payload")

        val imageUrls = jsonString.parseAs<List<String>>()
        return imageUrls.mapIndexed { index, imageUrl ->
            Page(index, imageUrl = imageUrl)
        }
    }

    override fun imageRequest(page: Page): Request {
        val imageHeaders = headersBuilder()
            .set("Accept", "image/*")
            .set("Sec-Fetch-Dest", "empty")
            .set("Sec-Fetch-Mode", "cors")
            .set("Sec-Fetch-Site", "same-origin")
            .build()
        return GET(page.imageUrl!!, imageHeaders)
    }

    // ============================== Preferences ==========================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_EMAIL
            title = "E-mail"
            summary = preferences.getString(PREF_EMAIL, "")?.ifEmpty { "Não configurado" }
            setDefaultValue("")
            setOnPreferenceChangeListener { _, newValue ->
                summary = (newValue as? String)?.ifEmpty { "Não configurado" }
                loggedIn = false
                true
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_PASSWORD
            title = "Senha"
            summary = if (preferences.getString(PREF_PASSWORD, "").isNullOrEmpty()) "Não configurado" else "••••••••"
            setDefaultValue("")
            setOnPreferenceChangeListener { _, newValue ->
                summary = if ((newValue as? String).isNullOrEmpty()) "Não configurado" else "••••••••"
                loggedIn = false
                true
            }
        }.also(screen::addPreference)

        createPreference(screen.context).apply {
            key = PREF_CLEAR_SESSION
            title = "Limpar sessão"
            summary = "Remove somente a sessão da Blackout Comics."
            setOnPreferenceClickListener {
                val url = baseUrl.toHttpUrl()
                client.cookieJar.saveFromResponse(
                    url,
                    listOf(SESSION_COOKIE, XSRF_COOKIE).map { name ->
                        Cookie.Builder().name(name).value("").domain(url.host).path("/").expiresAt(0L).build()
                    },
                )
                loggedIn = false
                true
            }
        }.also(screen::addPreference)
    }

    private fun createPreference(context: android.content.Context): Preference = runCatching {
        Preference::class.java.getConstructor(android.content.Context::class.java).newInstance(context)
    }.getOrElse { Preference() }

    // ============================== Filters ==============================

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        StatusFilter(),
        GenreFilter(),
        OrderFilter(),
    )

    companion object {
        private val CHAPTER_URL_REGEX = Regex("""/comics/\d+/ler/[a-zA-Z0-9_-]+|https?://[^'"]+/comics/\d+/ler/[a-zA-Z0-9_-]+""")
        private val DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE

        private const val PREF_EMAIL = "blackout_email"
        private const val PREF_PASSWORD = "blackout_password"
        private const val PREF_CLEAR_SESSION = "blackout_clear_session"
        private const val SESSION_COOKIE = "blackout-comics-session"
        private const val XSRF_COOKIE = "XSRF-TOKEN"
    }
}
