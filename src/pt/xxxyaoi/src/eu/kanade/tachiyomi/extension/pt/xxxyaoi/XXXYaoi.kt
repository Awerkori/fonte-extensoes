package eu.kanade.tachiyomi.extension.pt.xxxyaoi

import eu.kanade.tachiyomi.multisrc.madara.MadaraNoAjax
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.utils.asJsoup
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.seconds

@Source
abstract class XXXYaoi : MadaraNoAjax() {

    override val chapterDateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    override fun OkHttpClient.Builder.configureClient() = rateLimit(3, 1.seconds)

    override fun Headers.Builder.configureHeaders() = set("Upgrade-Insecure-Requests", "1")
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .set("Sec-GPC", "1")
        .set("Sec-Fetch-User", "?1")
        .set("Sec-Fetch-Site", "none")
        .set("Sec-Fetch-Mode", "navigate")
        .set("Sec-Fetch-Dest", "document")
        .set("Priority", "u=0, i")
        .set("Pragma", "no-cache")

    override val mangaSubString = "bl"
    override val hiatusStatus = super.hiatusStatus + "hiato"

    override val mangaDetailsSelectorTitle = ".xyaoi-main-title, h1"
    override val mangaDetailsSelectorAuthor = ".xyaoi-prop-col:has(.xyaoi-prop-label:contains(AUTOR)) a, a[href*=author]"
    override val mangaDetailsSelectorArtist = ".xyaoi-prop-col:has(.xyaoi-prop-label:contains(ARTISTA)) a, a[href*=artist]"
    override val mangaDetailsSelectorStatus = ".xyaoi-prop-value[class*=status-value-], span:matchesOwn((?i)^status:?$) + span"
    override val mangaDetailsSelectorDescription = ".xyaoi-synopsis-content, [itemprop=description], [class*=synopsis-content]"

    override val mangaDetailsSelectorGenre = ".xyaoi-genres-list a, a[href*='/genero/']"
    override val mangaDetailsSelectorTag = "[data-xxxyaoi-no-tags]"
    override val altNameSelector = "[data-xxxyaoi-no-alt-name]"
    override val genreDirectory = "genero"

    override fun searchCardSelector() = ".xyaoi-search-card, .c-tabs-item__content"

    override val archiveUrlSelector = "h3 a, .post-title a"

    override fun parseArchive(document: Document): List<SManga> = super.parseArchive(document).ifEmpty {
        Layout.cards(document).mapNotNull { card ->
            val id = card.id ?: return@mapNotNull null
            SManga.create().apply {
                url = id
                title = card.title
                thumbnail_url = card.image?.let(::imageFromElement)
                memo = mangaMemo(card.path, emptyList())
            }
        }
    }

    override fun parseSearchCards(document: Document): List<SearchCard> = super.parseSearchCards(document).ifEmpty {
        Layout.cards(document).map { SearchCard(it.title, it.path, it.image?.let(::imageFromElement)) }
    }

    override fun parseChapterList(document: Document, mangaPath: String): List<SChapter> = Layout.chapters(document, mangaPath).map { entry ->
        SChapter.create().apply {
            url = entry.path.substringBefore('?').trimEnd('/').substringAfterLast('/')
            name = entry.name
            date_upload = Layout.absoluteDate(entry.date) ?: parseChapterDate(entry.date)
            memo = buildJsonObject {
                put("mangaPath", mangaPath)
                put("chapterPath", entry.path)
            }
        }
    }

    override suspend fun fetchChapters(mangaPath: String, id: String, mangaPage: Document?): List<SChapter> {
        val inline = mangaPage?.let { parseChapterList(it, mangaPath) }.orEmpty()
        return inline.ifEmpty {
            val url = "$baseUrl${mangaPath.trimEnd('/')}/ajax/chapters/"
            parseChapterList(client.post(url, xhrHeaders, FormBody.Builder().build()).asJsoup(), mangaPath)
        }.also { check(it.isNotEmpty()) { "XXX Yaoi: capítulos não encontrados (chapter-links-not-found)." } }
    }

    override fun parseDetails(document: Document, id: String, preserveUrl: String?): SManga {
        val title = (document.selectFirst(".xyaoi-main-title") ?: document.selectFirst("h1"))?.text()
        check(!title.isNullOrBlank()) {
            "XXX Yaoi: título da obra não encontrado."
        }
        return super.parseDetails(document, id, preserveUrl).apply {
            this.title = title
            author = author ?: Layout.property(document, "Autor")?.text()?.takeIf(String::isNotBlank)
            artist = artist ?: Layout.property(document, "Artista")?.text()?.takeIf(String::isNotBlank)
            if (status == SManga.UNKNOWN) status = Layout.property(document, "Status")?.text()?.toStatus() ?: status
            description = description ?: Layout.property(document, "Sinopse")?.text()?.takeIf(String::isNotBlank)
        }
    }

    // Keep saved legacy chapter paths usable while Madara migrates chapter memos.
    override fun getChapterUrl(chapter: SChapter): String = Layout.chapterUrl(
        baseUrl,
        chapter.url,
        (chapter.memo["mangaPath"] as? JsonPrimitive)?.content,
        (chapter.memo["chapterPath"] as? JsonPrimitive)?.content,
    )

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = getChapterUrl(chapter)
        val first = client.get(chapterUrl).asJsoup()
        val document = if (first.selectFirst("#single-pager") != null) {
            val listUrl = first.location().toHttpUrlOrNull()?.newBuilder()?.setQueryParameter("style", "list")?.build()
                ?: error("XXX Yaoi: endereço de capítulo inválido.")
            client.get(listUrl).asJsoup()
        } else {
            first
        }
        return Reader.load(document, { super.parsePages(document).mapNotNull(Page::imageUrl) }) { scriptUrl ->
            client.get(scriptUrl, ensureSuccess = false).use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body
                if (body.contentLength() > 512_000) return@use null
                val source = body.source()
                if (source.request(512_001)) null else source.readUtf8()
            }
        }.mapIndexed { index, url -> Page(index, document.location(), url) }
    }

    override fun getFilterList(data: JsonElement?) = FilterList(
        *super.getFilterList(data).toTypedArray(),
        CompletedFilter(),
    )

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList) = if (filters.filterIsInstance<CompletedFilter>().firstOrNull()?.state == true) {
        archivePage(page, "", "/end/", query)
    } else {
        super.getSearchMangaList(page, query, filters)
    }

    private class CompletedFilter : Filter.CheckBox("Concluídos", false)
}
