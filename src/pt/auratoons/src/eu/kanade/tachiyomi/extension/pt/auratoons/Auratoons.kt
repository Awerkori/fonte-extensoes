package eu.kanade.tachiyomi.extension.pt.auratoons

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import org.jsoup.nodes.Element
import java.time.Instant

@Source
abstract class Auratoons : KeiSource() {
    override suspend fun getPopularManga(page: Int) = home(page, "MAIS LIDOS NO MOMENTO")

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        val doc = client.get(baseUrl).asJsoup()
        val latest = doc.select("section")
            .firstOrNull { it.selectFirst("h2")?.text()?.trim() == "ÚLTIMAS ATUALIZAÇÕES" }
            ?.select("article:has(a[href^=/manga/])")
            .orEmpty()
            .mapNotNull { card ->
                val link = card.selectFirst("a[href^=/manga/]") ?: return@mapNotNull null
                val image = card.selectFirst("img[alt]") ?: return@mapNotNull null
                toManga(link.attr("href"), image)
            }
            .distinctBy { it.url }
        return MangasPage(latest, false)
    }

    private suspend fun home(page: Int, sectionTitle: String): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        val doc = client.get(baseUrl).asJsoup()
        return MangasPage(
            doc.selectFirst("section:has(h2:contains($sectionTitle))")
                ?.select("a[href^=/manga/]:has(img[alt])")
                .orEmpty()
                .mapNotNull(::toManga)
                .distinctBy { it.url },
            false,
        )
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        val all = client.get(baseUrl).asJsoup().select("a[href^=/manga/]:has(img[alt])")
            .mapNotNull(::toManga)
            .distinctBy { it.url }
            .filter { it.title.contains(query, true) }
        return MangasPage(all, false)
    }
    override suspend fun getMangaByUrl(url: HttpUrl) = SManga.create().apply { this.url = url.encodedPath }
    override suspend fun fetchMangaUpdate(manga: SManga, chapters: List<SChapter>, fetchDetails: Boolean, fetchChapters: Boolean): SMangaUpdate {
        val doc = client.get("$baseUrl${manga.url}").asJsoup()
        val details = manga.apply {
            title = doc.selectFirst("h1")?.text().orEmpty()
            thumbnail_url = doc.select("img").firstOrNull { it.attr("alt") == title }?.attr("src")?.let(::absoluteUrl)
            description = doc.selectFirst("meta[name=description]")?.attr("content")
        }
        val list = chapterPattern.findAll(doc.html().replace("\\\"", "\"")).map { m ->
            SChapter.create().apply {
                val id = m.groupValues[1]
                val number = m.groupValues[2]
                url = "${manga.url}/capitulo/$id"
                name = listOf("Capítulo $number", m.groupValues[3].trim().takeUnless { it.isEmpty() || it.equals("null", true) })
                    .joinToString(" - ")
                chapter_number = number.replace(',', '.').toFloatOrNull() ?: -1F
                date_upload = m.groupValues[4].let { runCatching { Instant.parse(it).toEpochMilli() }.getOrDefault(0L) }
                memo = buildJsonObject { put("id", id) }
            }
        }.toList()
        return SMangaUpdate(details, list)
    }
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val id = chapter.memo["id"]?.jsonPrimitive?.content ?: chapter.url.substringAfterLast('/')
        val dto = client.get("$baseUrl/api/nxtoons/chapter-pages?chapterId=$id").parseAs<PagesDto>()
        return dto.urls.mapIndexed { i, url -> Page(i, imageUrl = "$baseUrl$url") }
    }
    override fun getMangaUrl(manga: SManga) = "$baseUrl${manga.url}"
    override fun getChapterUrl(chapter: SChapter) = "$baseUrl${chapter.url}"

    private fun toManga(a: Element): SManga? {
        val url = a.attr("href").substringBefore('#').takeIf { it.count { c -> c == '/' } == 2 } ?: return null
        val image = a.selectFirst("img[alt]") ?: return null
        return toManga(url, image)
    }

    private fun toManga(url: String, image: Element): SManga? {
        val mangaUrl = url.substringBefore('#').takeIf { it.count { c -> c == '/' } == 2 } ?: return null
        return SManga.create().apply {
            this.url = mangaUrl
            title = image.attr("alt").trim()
            thumbnail_url = image.attr("src").let(::absoluteUrl)
        }.takeIf { it.title.isNotBlank() }
    }

    private fun absoluteUrl(url: String) = if (url.startsWith("http")) url else "$baseUrl$url"

    private companion object {
        val chapterPattern = Regex(
            """\{"id":"(\d+)","chapterNumber":"([^"]+)","volume":(?:null|"[^"]*"),"title":(?:null|"([^"]*)"),"readableAt":"([^"]+)""",
        )
    }
}

@Serializable internal class PagesDto(val urls: List<String> = emptyList())
