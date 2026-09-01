package eu.kanade.tachiyomi.extension.pt.manganight

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
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

@Source
abstract class MangaNight : KeiSource() {

    // Short-lived cache so Details and Chapters share a single HTTP fetch of /manga/{slug}.
    // Entry = (Document, timestamp). TTL = 60s — enough for Mihon's sequential calls.
    private val docCache = ConcurrentHashMap<String, Pair<Document, Long>>()

    private suspend fun fetchMangaDoc(url: String): Document {
        val cached = docCache[url]
        if (cached != null && System.currentTimeMillis() - cached.second < 60_000L) {
            return cached.first
        }
        val doc = client.get(url).asJsoup()
        docCache[url] = doc to System.currentTimeMillis()
        return doc
    }

    // ============================== Popular ==============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$baseUrl/busca".toHttpUrl().newBuilder().apply {
            addQueryParameter("sort", "POPULARITY_DESC")
            addQueryParameter("page", page.toString())
        }.build()

        val response = client.get(url)
        return parseBuscaPage(response)
    }

    // ============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$baseUrl/busca".toHttpUrl().newBuilder().apply {
            addQueryParameter("sort", "START_DATE_DESC")
            addQueryParameter("page", page.toString())
        }.build()

        val response = client.get(url)
        return parseBuscaPage(response)
    }

    // ============================== Search ===============================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val sortFilter = filters.firstInstanceOrNull<SortFilter>()
        val genreFilter = filters.firstInstanceOrNull<GenreFilter>()
        val statusFilter = filters.firstInstanceOrNull<StatusFilter>()
        val countryFilter = filters.firstInstanceOrNull<CountryFilter>()

        val genre = genreFilter?.selectedValue.orEmpty()
        val status = statusFilter?.selectedValue.orEmpty()
        val country = countryFilter?.selectedValue.orEmpty()

        val url = "$baseUrl/busca".toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) {
                addQueryParameter("q", query)
            }
            addQueryParameter("sort", sortFilter?.selectedValue ?: "POPULARITY_DESC")
            if (genre.isNotBlank()) {
                addQueryParameter("genres", genre)
            }
            if (status.isNotBlank()) {
                addQueryParameter("status", status)
            }
            if (country.isNotBlank()) {
                addQueryParameter("country", country)
            }
            addQueryParameter("page", page.toString())
        }.build()

        val response = client.get(url)
        return parseBuscaPage(response)
    }

    private fun parseBuscaPage(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangaCards = document.select("a[href^='/manga/']")

        val seenUrls = mutableSetOf<String>()
        val mangas = mutableListOf<SManga>()

        for (card in mangaCards) {
            val href = card.attr("href").trim()
            if (href.contains('?') || href.contains('#')) continue // reject ?order=asc etc.
            if (href.count { it == '/' } != 2) continue // only /manga/{slug}
            if (href.contains("/capitulo/") || href.contains("/banner")) continue
            if (seenUrls.contains(href)) continue
            seenUrls.add(href)

            val img = card.selectFirst("img")
            // img alt is the most reliable title on /busca (confirmed empirically)
            val titleText = img?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
                ?: card.selectFirst("h2, h3")?.text()?.trim()
                ?: href.substringAfterLast("/")

            var coverUrl = img?.attr("src")
            if (coverUrl != null && coverUrl.contains("url=")) {
                coverUrl = coverUrl.substringAfter("url=").substringBefore("&")
                coverUrl = java.net.URLDecoder.decode(coverUrl, "UTF-8")
            }
            if (coverUrl != null && !coverUrl.startsWith("http")) {
                coverUrl = "$baseUrl$coverUrl"
            }

            mangas.add(
                SManga.create().apply {
                    title = Parser.unescapeEntities(titleText, false)
                    setUrlWithoutDomain(href)
                    thumbnail_url = coverUrl
                },
            )
        }

        // Check if there is next page: check if current page had items
        val hasNext = mangas.size >= 12
        return MangasPage(mangas, hasNext)
    }

    // ============================== Details ==============================

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val path = url.encodedPath
        if (path.isBlank() || path == "/") return null

        val document = fetchMangaDoc("$baseUrl$path")
        return parseMangaDetails(document).apply {
            setUrlWithoutDomain(path)
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = fetchMangaDoc(getMangaUrl(manga))
        val updatedManga = if (fetchDetails) parseMangaDetails(document) else manga
        val updatedChapters = if (fetchChapters) parseChapterList(document, manga.url) else chapters
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
        val rawTitle = document.selectFirst("h1")?.text()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")
            ?: ""

        title = Parser.unescapeEntities(rawTitle, false).trim()

        thumbnail_url = document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: document.selectFirst("img[src*='/api/cover/']")?.absUrl("src")

        description = document.selectFirst("meta[property='og:description']")?.attr("content")
            ?.let { Parser.unescapeEntities(it, false).trim() }

        val genres = document.select("a[href*='genres='], span[class*='badge']").map { it.text().trim() }
        if (genres.isNotEmpty()) {
            genre = genres.distinct().joinToString(", ")
        }

        status = when {
            document.text().contains("Completo", ignoreCase = true) || document.text().contains("Finalizado", ignoreCase = true) -> SManga.COMPLETED
            document.text().contains("Em Lançamento", ignoreCase = true) || document.text().contains("Em andamento", ignoreCase = true) -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }
    }

    private fun parseChapterList(document: Document, mangaUrl: String): List<SChapter> {
        val chapterLinks = document.select("a[href*='/capitulo/']")
        val seenUrls = mutableSetOf<String>()
        val chapters = mutableListOf<SChapter>()

        for (link in chapterLinks) {
            val href = link.attr("href").trim()
            if (href.contains('?')) continue
            if (seenUrls.contains(href)) continue
            seenUrls.add(href)

            // Extract structured fields from child elements (confirmed HTML structure):
            //   <p class="font-medium">Capítulo N<span>— Vol.X Ch.N</span></p>
            //   <p class="text-xs ...">Terseu scan</p>   ← scanlator
            //   <div class="text-xs ... shrink-0">07/04/2025</div>  ← date
            val titleP = link.selectFirst("p.font-medium, p[class*='font-medium']")
            val scanlatorP = link.select("p.text-xs, p[class*='text-xs']").lastOrNull()
            val dateDiv = link.selectFirst("div[class*='shrink-0']")

            // Chapter name: prefer structured title element, fall back to full link text
            val chapterName = if (titleP != null) {
                Parser.unescapeEntities(titleP.text().trim(), false)
            } else {
                Parser.unescapeEntities(link.text().trim(), false)
            }

            // Skip entries that are purely navigation shortcuts with no real title
            // (they won't have a titleP and their full text is just a label)
            if (titleP == null && chapterName.equals("Começar a Ler", ignoreCase = true)) continue
            if (titleP == null && chapterName.equals("Continuar Lendo", ignoreCase = true)) continue
            if (titleP == null && chapterName.equals("Ler Último", ignoreCase = true)) continue
            if (titleP == null && chapterName.equals("Ler Primeiro", ignoreCase = true)) continue

            val chapterNum = Regex("""Cap[íi]tulo\s*(\d+(?:\.\d+)?)""").find(chapterName)?.groupValues?.get(1)?.toFloatOrNull()
                ?: Regex("""#\s*(\d+(?:\.\d+)?)""").find(chapterName)?.groupValues?.get(1)?.toFloatOrNull()
                ?: -1f

            val scanlatorText = scanlatorP?.text()?.trim()?.takeIf { it.isNotBlank() }
            val dateText = dateDiv?.text()?.trim() ?: ""
            val dateUpload = parseDate(dateText)

            chapters.add(
                SChapter.create().apply {
                    setUrlWithoutDomain(href)
                    name = chapterName.ifBlank { "Capítulo $chapterNum" }
                    chapter_number = chapterNum
                    this.date_upload = dateUpload
                    this.scanlator = scanlatorText
                },
            )
        }

        return chapters
    }

    private fun parseDate(text: String): Long {
        val dateMatch = Regex("""(\d{2}/\d{2}/\d{4})""").find(text)?.groupValues?.get(1) ?: return 0L
        return runCatching {
            DATE_FORMAT.parse(dateMatch)?.time ?: 0L
        }.getOrDefault(0L)
    }

    // =============================== Pages ===============================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = "$baseUrl${if (chapter.url.startsWith("/")) chapter.url else "/${chapter.url}"}"
        val document = client.get(chapterUrl).asJsoup()
        val html = document.html()

        // 1. Priority: MangaDex provider
        val mdexMatch = Regex("""/api/reader/image/mdex/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})""").find(html)
        if (mdexMatch != null) {
            val uuid = mdexMatch.groupValues[1]
            val atHomeResponse = client.get("https://api.mangadex.org/at-home/server/$uuid")
            val atHomeData = atHomeResponse.parseAs<MangaDexAtHomeDto>()

            val hash = atHomeData.chapter.hash
            val files = atHomeData.chapter.data.takeIf { it.isNotEmpty() } ?: atHomeData.chapter.dataSaver

            if (hash.isNotBlank() && files.isNotEmpty()) {
                return files.mapIndexed { index, fileName ->
                    Page(index, imageUrl = "https://uploads.mangadex.org/data/$hash/$fileName")
                }
            }
        }

        // 2. Internal Manga Night proxy: /api/reader/image/api/v1/manga/{id}/chapter/{n}/page/{idx}
        // Pages are scattered in the SSR HTML in non-sequential order; sort by page index.
        val internalPageRe = Regex("""/api/reader/image/api/v1/manga/\d+/chapter/[^/]+/page/(\d+)""")
        val internalPages = mutableListOf<Pair<Int, String>>()
        for (img in document.select("img[src*='/api/reader/image/api/v1/manga/']")) {
            val src = img.attr("src").takeIf { it.isNotBlank() } ?: continue
            if (IGNORE_IMG_KEYWORDS.any { src.contains(it, ignoreCase = true) }) continue
            val pageIdx = internalPageRe.find(src)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            val absUrl = if (src.startsWith("/")) "$baseUrl$src" else src
            internalPages.add(pageIdx to absUrl)
        }
        if (internalPages.isNotEmpty()) {
            return internalPages.sortedBy { it.first }.mapIndexed { idx, (_, url) ->
                Page(idx, imageUrl = url)
            }
        }

        // 3. Generic fallback for other reader image formats
        val imgElements = document.select("img[src*='/api/reader/image/'], img[src*='/chapter/']")
        val imageList = imgElements.mapNotNull { element ->
            val src = element.attr("src").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (IGNORE_IMG_KEYWORDS.any { src.contains(it, ignoreCase = true) }) return@mapNotNull null
            if (src.startsWith("//")) {
                "https:$src"
            } else if (src.startsWith("/")) {
                "$baseUrl$src"
            } else {
                src
            }
        }.distinct()

        if (imageList.isEmpty()) {
            throw IOException("Nenhuma página de leitura encontrada para este capítulo")
        }

        return imageList.mapIndexed { index, imgUrl ->
            Page(index, imageUrl = imgUrl)
        }
    }

    // ============================== Filters ==============================

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        SortFilter(),
        GenreFilter(),
        StatusFilter(),
        CountryFilter(),
    )

    companion object {
        private val DATE_FORMAT = SimpleDateFormat("dd/MM/yyyy", Locale.ROOT)

        private val IGNORE_IMG_KEYWORDS = listOf(
            "logo",
            "icon",
            "banner",
            "avatar",
            "favicon",
        )
    }
}
