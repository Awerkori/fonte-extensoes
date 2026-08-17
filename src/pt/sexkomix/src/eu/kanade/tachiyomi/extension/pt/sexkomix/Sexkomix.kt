package eu.kanade.tachiyomi.extension.pt.sexkomix

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
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import java.net.URLEncoder

@Source
abstract class Sexkomix : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(2)

    // ============================== Popular (Navegar) ==============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val response = client.get("$baseUrl/search/?lang=pt&sort=date&page=$page".toHttpUrl())
        return parseMangaList(response.body.string(), response.request.url.toString())
    }

    // ============================== Latest (Recentes) ==============================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val response = client.get("$baseUrl/home/?lang=pt&sort=date&page=$page".toHttpUrl())
        return parseMangaList(response.body.string(), response.request.url.toString())
    }

    // ============================== Search ==============================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val response = client.get("$baseUrl/search/?lang=pt&s=$encoded&page=$page".toHttpUrl())
        return parseMangaList(response.body.string(), response.request.url.toString())
    }

    // ============================== Details ==============================

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (!url.toString().contains("/comicsx_pt/")) return null
        val response = client.get(url)
        return parseMangaDetails(response.body.string(), response.request.url.toString())
            .apply { setUrlWithoutDomain(url.toString()) }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val mangaUrl = buildAbsUrl(manga.url)
        val response = client.get(mangaUrl.toHttpUrl())
        val html = response.body.string()
        val reqUrl = response.request.url.toString()

        val details = if (fetchDetails) parseMangaDetails(html, reqUrl).apply { url = manga.url } else manga
        val chapterList = if (fetchChapters) {
            listOf(
                SChapter.create().apply {
                    url = manga.url
                    name = "Ler Comic"
                    chapter_number = 1f
                },
            )
        } else {
            chapters
        }

        return SMangaUpdate(manga = details, chapters = chapterList)
    }

    // ============================== Pages ==============================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val response = client.get(buildAbsUrl(chapter.url).toHttpUrl())
        val doc = Jsoup.parse(response.body.string(), response.request.url.toString())
        return doc.select("#comix_box #comix_pages_ul img.gallery-img").mapIndexed { i, el ->
            val src = el.attr("data-src").ifBlank { el.attr("src") }
            Page(i, "", resolveUrl(src, response.request.url.toString()))
        }
    }

    // ============================== Helpers ==============================

    private fun parseMangaList(html: String, baseUrlStr: String): MangasPage {
        val doc = Jsoup.parse(html, baseUrlStr)
        val mangas = doc.select("#comix_directory li.comix").mapNotNull { el ->
            val a = el.selectFirst("a[href*='/comicsx_pt/']") ?: return@mapNotNull null
            SManga.create().apply {
                val href = resolveUrl(a.attr("href"), baseUrlStr)
                setUrlWithoutDomain(href)
                title = el.selectFirst(".comix_title h2 p, .comix_title h2")?.text()?.trim()
                    ?: el.selectFirst("img.comix_img")?.attr("alt")?.trim()
                    ?: href.trimEnd('/').substringAfterLast("/")
                thumbnail_url = el.selectFirst("img.comix_img")?.let {
                    resolveUrl(it.attr("data-src").ifBlank { it.attr("src") }, baseUrlStr)
                }
            }
        }
        val hasNext = doc.selectFirst("a.pstr-next") != null
        return MangasPage(mangas, hasNext)
    }

    private fun parseMangaDetails(html: String, baseUrlStr: String): SManga {
        val doc = Jsoup.parse(html, baseUrlStr)
        return SManga.create().apply {
            title = doc.selectFirst("h1, .comix_title h1, .comix_title h2")?.text()?.trim().orEmpty()
            thumbnail_url = doc.selectFirst("img.comix_img, .comix_img_box img")?.let {
                resolveUrl(it.attr("data-src").ifBlank { it.attr("src") }, baseUrlStr)
            }
            description = doc.selectFirst(".description, .sinopsis, .comix_description")?.text()?.trim()
            genre = doc.select(".right_box .info_box a[href*='/categories/'], .right_box .info_box a[href*='/tag_pagex/']")
                .joinToString { it.text().trim() }
        }
    }

    private fun buildAbsUrl(path: String): String = when {
        path.startsWith("http") -> path
        path.startsWith("//") -> "https:$path"
        path.startsWith("/") -> "$baseUrl$path"
        else -> "$baseUrl/$path"
    }

    private fun resolveUrl(src: String, base: String): String {
        if (src.isBlank()) return ""
        return when {
            src.startsWith("http") -> src
            src.startsWith("//") -> "https:$src"
            src.startsWith("/") -> {
                val u = base.toHttpUrl()
                "${u.scheme}://${u.host}$src"
            }
            else -> "$baseUrl/$src"
        }
    }
}
