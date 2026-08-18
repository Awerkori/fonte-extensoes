package eu.kanade.tachiyomi.extension.pt.timelinecomics

import android.app.Application
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

@Source
abstract class TimelineComics : KeiSource() {

    private val cacheDir: File by lazy {
        File(Injekt.get<Application>().cacheDir, "timelinecomics_pdf_cache").apply {
            mkdirs()
        }
    }

    override fun OkHttpClient.Builder.configureClient() = apply {
        connectTimeout(1, TimeUnit.MINUTES)
        readTimeout(3, TimeUnit.MINUTES)
        addInterceptor(PdfPageInterceptor(cacheDir))
    }

    private val catalogMutex = kotlinx.coroutines.sync.Mutex()
    private var catalogList: List<SManga>? = null

    private suspend fun getOrFetchCatalog(): List<SManga> {
        catalogList?.takeIf { it.isNotEmpty() }?.let { return it }

        catalogMutex.withLock {
            catalogList?.takeIf { it.isNotEmpty() }?.let { return it }

            val pagesUrl = "$baseUrl/feeds/pages/default?alt=json".toHttpUrl()
            val pagesDto = client.get(pagesUrl).parseAs<BloggerFeedDto>()
            val staticPages = pagesDto.feed?.entry?.mapNotNull { entry ->
                entry.link?.firstOrNull { it.rel == "alternate" }?.href
            } ?: emptyList()

            val seenUrls = mutableSetOf<String>()
            val items = mutableListOf<SManga>()

            for (pageUrl in staticPages) {
                try {
                    val document = client.get(pageUrl).asJsoup()
                    val links = document.select("a[href*='timelinecomics.blogspot.com/20']")
                    for (link in links) {
                        val href = link.attr("href").replace("http://", "https://")
                        val text = link.text().trim()
                            .replace("&nbsp;", " ")
                            .replace("&amp;", "&")
                            .trim()

                        if (text.isNotBlank() && !text.contains("AQUI", ignoreCase = true) && !text.contains("Linha do Tempo", ignoreCase = true) && !text.contains("Pedidos", ignoreCase = true)) {
                            val path = href.substringAfter("timelinecomics.blogspot.com")
                            if (path.isNotBlank() && seenUrls.add(path)) {
                                items.add(
                                    SManga.create().apply {
                                        setUrlWithoutDomain(path)
                                        title = text
                                    },
                                )
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Ignore transient page fetch error
                }
            }

            items.sortBy { it.title.replace(Regex("^[^a-zA-Z0-9]+"), "").lowercase() }
            catalogList = items
            return items
        }
    }

    // ============================== Popular ==============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val catalog = getOrFetchCatalog()
        val startIndex = (page - 1) * PAGE_SIZE
        if (startIndex >= catalog.size) {
            return MangasPage(emptyList(), false)
        }
        val endIndex = minOf(startIndex + PAGE_SIZE, catalog.size)
        val mangas = catalog.subList(startIndex, endIndex)
        val hasNext = endIndex < catalog.size
        return MangasPage(mangas, hasNext)
    }

    // ============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): MangasPage = getFeedPage(page, query = null)

    // ============================== Search ===============================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = getFeedPage(page, query.takeIf { it.isNotBlank() })

    private suspend fun getFeedPage(page: Int, query: String?): MangasPage {
        val startIndex = (page - 1) * PAGE_SIZE + 1
        val url = "$baseUrl/feeds/posts/default".toHttpUrl().newBuilder().apply {
            addQueryParameter("alt", "json")
            addQueryParameter("start-index", startIndex.toString())
            addQueryParameter("max-results", PAGE_SIZE.toString())
            if (!query.isNullOrBlank()) {
                addQueryParameter("q", query)
            }
        }.build()

        val response = client.get(url)
        val feedDto = response.parseAs<BloggerFeedDto>()
        return parseFeedToMangasPage(feedDto, startIndex)
    }

    private fun parseFeedToMangasPage(feedDto: BloggerFeedDto, startIndex: Int): MangasPage {
        val feed = feedDto.feed ?: return MangasPage(emptyList(), false)
        val totalResults = feed.totalResults?.t?.toIntOrNull() ?: 0
        val entries = feed.entry ?: emptyList()

        val mangaList = entries.mapNotNull { entry ->
            val rawTitle = entry.title?.t?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val alternateLink = entry.link?.firstOrNull { it.rel == "alternate" }?.href
                ?: return@mapNotNull null
            val contentHtml = entry.content?.t ?: entry.summary?.t ?: ""

            SManga.create().apply {
                setUrlWithoutDomain(alternateLink)
                title = rawTitle
                thumbnail_url = extractCoverFromHtml(contentHtml)
                status = if (entry.category?.any { it.term.equals("Completos", ignoreCase = true) } == true) {
                    SManga.COMPLETED
                } else {
                    SManga.UNKNOWN
                }
            }
        }

        val hasNext = (startIndex + entries.size - 1) < totalResults && entries.isNotEmpty()
        return MangasPage(mangaList, hasNext)
    }

    // ============================== Details ==============================

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val path = url.encodedPath
        if (path.isBlank() || path == "/") return null

        val document = client.get("$baseUrl$path").asJsoup()
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
        val document = client.get(getMangaUrl(manga)).asJsoup()
        val updatedManga = if (fetchDetails) parseMangaDetails(document) else manga
        val updatedChapters = if (fetchChapters) parseChapterList(document) else chapters
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
        val titleElement = document.selectFirst(".post-title, h3.post-title, .entry-title")
        val fullTitle = titleElement?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")
            ?: ""

        title = fullTitle
        thumbnail_url = document.selectFirst(".post-body img, .entry-content img")?.absUrl("src")
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")

        val bodyElement = document.selectFirst(".post-body, .entry-content")
        val rawDescription = bodyElement?.children()
            ?.filter { it.tagName() == "p" || it.tagName() == "div" || it.tagName() == "span" }
            ?.map { it.text().trim() }
            ?.filter { it.isNotEmpty() && !it.contains("drive.google.com", ignoreCase = true) && !it.startsWith("Download", ignoreCase = true) }
            ?.joinToString("\n\n")

        description = rawDescription?.takeIf { it.isNotBlank() } ?: bodyElement?.text()?.trim()

        // Extract publisher/author from parentheses e.g. (Marvel), (DC)
        val publisherMatch = Regex("""\(([^)]+)\)""").findAll(fullTitle).lastOrNull()
        author = publisherMatch?.groupValues?.get(1)?.trim()

        // Extract genres from labels
        val genres = document.select("a[rel='tag'], .post-labels a").map { it.text().trim() }.filter { it.isNotEmpty() }
        genre = if (genres.isNotEmpty()) {
            genres.joinToString(", ")
        } else {
            author
        }

        status = if (document.select(".post-labels a:contains(Completos)").isNotEmpty()) {
            SManga.COMPLETED
        } else {
            SManga.UNKNOWN
        }
    }

    private fun parseChapterList(document: Document): List<SChapter> {
        val bodyElement = document.selectFirst(".post-body, .entry-content") ?: return emptyList()
        val links = bodyElement.select("a[href*='drive.google.com']")

        val individualFileChapters = mutableListOf<SChapter>()
        val folderLinks = mutableListOf<Element>()
        val seenFileIds = mutableSetOf<String>()

        for (link in links) {
            val href = link.attr("href")
            val text = link.text().trim()

            val fileId = GoogleDriveResolver.extractFileId(href)
            val folderId = GoogleDriveResolver.extractFolderId(href)

            if (fileId != null && !href.contains("/folders/")) {
                if (seenFileIds.add(fileId)) {
                    val rk = GoogleDriveResolver.extractResourceKey(href)
                    val rkParam = if (!rk.isNullOrBlank()) "&rk=$rk" else ""
                    val chapterUrl = "https://127.0.0.1/gdrive/file?id=$fileId$rkParam"

                    val chapterName = when {
                        text.isNotBlank() && !text.equals("download", ignoreCase = true) -> text
                        else -> "Edição ${individualFileChapters.size + 1}"
                    }

                    val chapterNumber = extractChapterNumber(chapterName, individualFileChapters.size + 1f)

                    individualFileChapters.add(
                        SChapter.create().apply {
                            url = chapterUrl
                            name = chapterName
                            chapter_number = chapterNumber
                        },
                    )
                }
            } else if (folderId != null) {
                folderLinks.add(link)
            }
        }

        if (individualFileChapters.isNotEmpty()) {
            return individualFileChapters.sortedByDescending { it.chapter_number }
        }

        // If no individual file links, parse public folders
        val folderChapters = mutableListOf<SChapter>()
        for (folderLink in folderLinks) {
            val folderHref = folderLink.attr("href")
            val folderId = GoogleDriveResolver.extractFolderId(folderHref) ?: continue
            val driveFiles = GoogleDriveResolver.parseFolder(client, folderId, headers)

            for ((index, file) in driveFiles.withIndex()) {
                if (seenFileIds.add(file.id)) {
                    val rkParam = if (!file.resourceKey.isNullOrBlank()) "&rk=${file.resourceKey}" else ""
                    val chapterUrl = "https://127.0.0.1/gdrive/file?id=${file.id}$rkParam"
                    val cleanName = file.name.removeSuffix(".pdf").trim()

                    folderChapters.add(
                        SChapter.create().apply {
                            url = chapterUrl
                            name = cleanName
                            chapter_number = extractChapterNumber(cleanName, (index + 1).toFloat())
                        },
                    )
                }
            }
        }

        return folderChapters.sortedByDescending { it.chapter_number }
    }

    private fun extractChapterNumber(name: String, defaultNum: Float): Float {
        val numberRegex = Regex("""(?:edição|capítulo|ed\.|cap\.|#|-)?\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
        val match = numberRegex.findAll(name).lastOrNull()
        return match?.groupValues?.get(1)?.toFloatOrNull() ?: defaultNum
    }

    // =============================== Pages ===============================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val fileId = GoogleDriveResolver.extractFileId(chapter.url)
            ?: throw IOException("ID do arquivo Google Drive não encontrado no capítulo")
        val resourceKey = GoogleDriveResolver.extractResourceKey(chapter.url)

        val cacheKey = "${fileId}_${resourceKey.orEmpty()}".filter { it.isLetterOrDigit() || it == '_' }
        val cachedFile = File(cacheDir, "$cacheKey.pdf")

        if (!isValidPdf(cachedFile)) {
            // Clean up old cached files
            cleanOldCacheFiles()

            val downloadUrl = GoogleDriveResolver.getDownloadUrl(fileId, resourceKey)
            val downloadRequest = GET(downloadUrl, headers)

            val tempFile = File(cacheDir, "$cacheKey.tmp")
            try {
                client.newCall(downloadRequest).execute().use { response ->
                    when (response.code) {
                        404 -> throw IOException("Arquivo não encontrado no Google Drive (404 / ID inexistente ou removido)")
                        403 -> throw IOException("Acesso negado no Google Drive (403 / Arquivo privado ou sem permissão)")
                        else -> {
                            if (!response.isSuccessful) {
                                throw IOException("Erro HTTP ${response.code} ao baixar do Google Drive")
                            }
                        }
                    }

                    response.body.byteStream().use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }

                if (!isValidPdf(tempFile)) {
                    val previewBytes = if (tempFile.exists()) tempFile.readBytes().take(200).toByteArray() else ByteArray(0)
                    val previewStr = String(previewBytes)
                    tempFile.delete()

                    if (previewStr.contains("<html", ignoreCase = true) || previewStr.contains("quota", ignoreCase = true)) {
                        throw IOException("Google Drive retornou aviso de quota/bloqueio temporário em vez do PDF")
                    } else {
                        throw IOException("O arquivo baixado não é um documento PDF válido")
                    }
                }

                if (cachedFile.exists()) {
                    cachedFile.delete()
                }
                tempFile.renameTo(cachedFile)
            } finally {
                if (tempFile.exists()) {
                    tempFile.delete()
                }
            }
        }

        val pageCount = ParcelFileDescriptor.open(cachedFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { it.pageCount }
        }

        if (pageCount <= 0) {
            throw IOException("O PDF não contém páginas legíveis")
        }

        return (0 until pageCount).map { pageIndex ->
            Page(pageIndex, imageUrl = "https://127.0.0.1/pdf-page/$cacheKey/$pageIndex")
        }
    }

    private fun isValidPdf(file: File): Boolean {
        if (!file.exists() || file.length() < 10L) return false
        return try {
            FileInputStream(file).use { input ->
                val magic = ByteArray(5)
                val read = input.read(magic)
                read >= 4 && magic[0] == 0x25.toByte() && magic[1] == 0x50.toByte() && magic[2] == 0x44.toByte() && magic[3] == 0x46.toByte()
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun cleanOldCacheFiles() {
        try {
            val now = System.currentTimeMillis()
            cacheDir.listFiles()?.forEach { file ->
                if (now - file.lastModified() > CACHE_TTL_MS) {
                    file.delete()
                }
            }
        } catch (_: Exception) {
            // Ignore cache cleanup errors
        }
    }

    private fun extractCoverFromHtml(html: String): String? {
        val imgMatch = Regex("""<img[^>]+src=["']([^"']+)["']""").find(html)
        return imgMatch?.groupValues?.get(1)?.replace("/s[0-9]+(-c)?/", "/s1600/")
    }

    companion object {
        private const val PAGE_SIZE = 24
        private const val CACHE_TTL_MS = 2 * 60 * 60 * 1000L // 2 hours
    }
}
