package eu.kanade.tachiyomi.extension.pt.galaxscanlator

import eu.kanade.tachiyomi.multisrc.zeistmanga.ZeistManga
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.asJsoup
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document

private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

@Source
abstract class GalaxScanlator : ZeistManga() {
    override fun OkHttpClient.Builder.configureClient() = rateLimit(3) { it.host == baseUrl.toHttpUrl().host }

    override val popularMangaSelector = "#PopularPosts2 article"
    override val popularMangaSelectorTitle = "h4"
    override val popularMangaSelectorUrl = "a"

    override val mangaDetailsSelector = ".grid.gta-series"
    override val mangaDetailsSelectorGenres = "dt:contains(Genre) + dd a[rel=tag]"

    override val useNewChapterFeed = true
    override val chapterCategory = "Chapter"
    override val pageListSelector = ".separator"

    override val hasFilters = true
    override val hasLanguageFilter = false

    override fun mangaDetailsParse(document: Document): SManga {
        val manga = super.mangaDetailsParse(document)
        val synopsis = document.selectFirst("#synopsis, .synopsis, [itemprop=description]")
            ?.text()
            ?.trim()
            .orEmpty()

        if (synopsis.isNotBlank()) {
            manga.description = if (manga.description.isNullOrBlank()) {
                synopsis
            } else {
                "$synopsis\n\n${manga.description}".trim()
            }
        }
        return manga
    }

    override suspend fun getChapterList(feedUrl: String, doc: Document?): List<SChapter> = super.getChapterList(feedUrl, doc).map { chapter ->
        chapter.apply {
            name = cleanChapterName(name, url)
        }
    }

    override fun pageListParse(document: Document): List<Page> {
        val redirectUrl = findRedirectUrl(document)
        val redirectUrls = if (redirectUrl != null) {
            resolveRedirectUrls(client, headers, redirectUrl)
        } else {
            emptyList()
        }

        val urls = if (redirectUrls.isNotEmpty()) {
            redirectUrls
        } else {
            parseBloggerImages(document, baseUrl)
        }

        return urls.mapIndexed { index, url ->
            Page(index, imageUrl = url)
        }
    }

    companion object {
        private val REDIRECT_REGEX = """(?:window\.)?location(?:\.replace|\.assign|\.href)?\s*(?:=|\()\s*["']([^"']+)["']""".toRegex()
        private val META_REFRESH_REGEX = """url=([^"'\s>]+)""".toRegex(RegexOption.IGNORE_CASE)
        private val CUBARI_GIST_REGEX = """(?:https?:)?//cubari\.moe/read/gist/([^/]+)/([^/]+)""".toRegex()
        private val MANGADEX_CHAPTER_REGEX = """mangadex\.org/chapter/([0-9a-fA-F-]+)""".toRegex()
        private val UUID_REGEX = """[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""".toRegex()
        private val CHAPTER_NAME_REGEX = """\b((?:Chapter|Cap[íi]tulo|Cap\.?|Volume|Vol\.?)\s*[\d\.\-]+(?:.*)?)$""".toRegex(RegexOption.IGNORE_CASE)
        private val SLUG_CHAPTER_REGEX = """[-_](?:chapter|cap[íi]tulo|cap)?[-_]?(\d+(?:[\.\-]\d+)?)(?:\.html)?$""".toRegex(RegexOption.IGNORE_CASE)

        internal fun cleanChapterName(rawTitle: String, url: String = ""): String {
            val title = rawTitle.trim()
            val match = CHAPTER_NAME_REGEX.find(title)
            if (match != null) {
                return match.groupValues[1].trim()
            }

            val slug = url.substringAfterLast('/').substringBefore('?').removeSuffix(".html")
            val slugMatch = SLUG_CHAPTER_REGEX.find(slug)
            if (slugMatch != null) {
                return "Chapter ${slugMatch.groupValues[1]}"
            }

            return title
        }

        internal fun findRedirectUrl(document: Document): String? {
            for (script in document.select("script")) {
                val scriptContent = script.data().ifBlank { script.html() }
                val match = REDIRECT_REGEX.find(scriptContent)
                if (match != null) return match.groupValues[1].trim()
            }

            val metaRefresh = document.selectFirst("meta[http-equiv=refresh]")?.attr("content").orEmpty()
            if (metaRefresh.isNotBlank()) {
                val match = META_REFRESH_REGEX.find(metaRefresh)
                if (match != null) return match.groupValues[1].trim()
            }

            val rawElement = document.selectFirst("#zeist-raw-data")
            val rawHtml = when (rawElement?.tagName()?.lowercase()) {
                "textarea" -> rawElement.wholeText().ifBlank { rawElement.text() }
                null -> ""
                else -> rawElement.html().ifBlank { rawElement.text() }
            }
            if (rawHtml.isNotBlank()) {
                val match = REDIRECT_REGEX.find(rawHtml)
                if (match != null) return match.groupValues[1].trim()
            }

            return null
        }

        internal fun parseBloggerImages(document: Document, baseUrl: String): List<String> {
            val rawElement = document.selectFirst("#zeist-raw-data")
            val rawHtml = when (rawElement?.tagName()?.lowercase()) {
                "textarea" -> rawElement.wholeText().ifBlank { rawElement.text() }
                null -> ""
                else -> rawElement.html().ifBlank { rawElement.text() }
            }

            val contentDoc = if (rawHtml.isNotBlank()) {
                rawHtml.asJsoup(baseUrl)
            } else {
                document
            }

            val imgElements = contentDoc.select("img[src], img[data-src], img[data-lazy-src]")
            if (imgElements.isEmpty()) {
                return emptyList()
            }

            data class ImageCandidate(
                val url: String,
                val width: Int,
                val height: Int,
                val alt: String,
            )

            val candidates = imgElements.mapNotNull { img ->
                val rawSrc = img.attr("abs:src")
                    .ifBlank { img.attr("abs:data-src") }
                    .ifBlank { img.attr("abs:data-lazy-src") }
                    .ifBlank { img.attr("src") }
                    .trim()

                if (rawSrc.isBlank() || rawSrc.startsWith("data:")) return@mapNotNull null

                val url = when {
                    rawSrc.startsWith("//") -> "https:$rawSrc"
                    rawSrc.startsWith("/") -> baseUrl.removeSuffix("/") + "/" + rawSrc.removePrefix("/")
                    else -> rawSrc
                }

                val width = img.attr("data-original-width").toIntOrNull() ?: 0
                val height = img.attr("data-original-height").toIntOrNull() ?: 0
                val alt = img.attr("alt").trim()

                ImageCandidate(url, width, height, alt)
            }

            val seenUrls = mutableSetOf<String>()
            val resultUrls = mutableListOf<String>()

            for ((index, item) in candidates.withIndex()) {
                if (!seenUrls.add(item.url)) continue

                val filename = item.url.substringAfterLast('/').substringBefore('?').lowercase()
                val altLower = item.alt.lowercase()

                if (filename.contains("-red.") || filename.contains("-red-") ||
                    filename.contains("redirect") || filename.contains("placeholder") ||
                    altLower.contains("redirecionad")
                ) {
                    continue
                }

                if (index == 0 && candidates.size > 1) {
                    val isKnownBannerDimension = (item.width == 1125 && item.height == 1105) ||
                        (item.width == 1105 && item.height == 800)
                    val isBannerFilename = filename.startsWith("00.")

                    val isSquareOrLandscapeBanner = if (item.width > 0 && item.height > 0) {
                        val ratio = item.width.toFloat() / item.height.toFloat()
                        val nextItem = candidates.getOrNull(1)
                        val nextRatio = if (nextItem != null && nextItem.width > 0 && nextItem.height > 0) {
                            nextItem.width.toFloat() / nextItem.height.toFloat()
                        } else {
                            0f
                        }

                        ratio >= 0.95f && nextRatio in 0.01f..0.85f
                    } else {
                        false
                    }

                    if (isKnownBannerDimension || isBannerFilename || isSquareOrLandscapeBanner) {
                        continue
                    }
                }

                resultUrls.add(item.url)
            }

            return resultUrls
        }

        internal fun resolveRedirectUrls(client: OkHttpClient, headers: Headers, rawUrl: String): List<String> {
            val targetUrl = if (rawUrl.startsWith("//")) "https:$rawUrl" else rawUrl

            val cubariMatch = CUBARI_GIST_REGEX.find(targetUrl)
            if (cubariMatch != null) {
                val gistId = cubariMatch.groupValues[1]
                val chapterSlug = cubariMatch.groupValues[2]
                return resolveCubariUrls(client, headers, gistId, chapterSlug)
            }

            val mangadexMatch = MANGADEX_CHAPTER_REGEX.find(targetUrl)
            if (mangadexMatch != null) {
                val uuid = mangadexMatch.groupValues[1]
                return resolveMangadexUrls(client, headers, uuid)
            }

            return emptyList()
        }

        internal fun resolveCubariUrls(client: OkHttpClient, headers: Headers, gistId: String, chapterSlug: String): List<String> {
            return runCatching<List<String>> {
                val seriesUrl = "https://cubari.moe/read/api/gist/series/$gistId/"
                val request = Request.Builder().url(seriesUrl).headers(headers).build()
                val response = client.newCall(request).execute()
                val seriesJson = response.parseAs<JsonObject>(json)
                val chapters = seriesJson["chapters"]?.jsonObject ?: return@runCatching emptyList()

                val chapterObj = chapters[chapterSlug]?.jsonObject
                    ?: chapters.entries.firstOrNull {
                        it.key.trim().trimStart('0') == chapterSlug.trim().trimStart('0')
                    }?.value?.jsonObject
                    ?: return@runCatching emptyList()

                val groups = chapterObj["groups"]?.jsonObject ?: return@runCatching emptyList()
                for ((_, groupElement) in groups) {
                    if (groupElement is JsonArray) {
                        val urls = groupElement.mapNotNull { it.jsonPrimitive.contentOrNull }
                        if (urls.isNotEmpty()) {
                            return@runCatching urls
                        }
                    } else if (groupElement is JsonPrimitive && groupElement.isString) {
                        val path = groupElement.content
                        val mangadexUuid = UUID_REGEX.find(path)?.value
                        val urls: List<String> = if (mangadexUuid != null && path.contains("mangadex")) {
                            resolveMangadexUrls(client, headers, mangadexUuid)
                        } else {
                            val fullUrl = when {
                                path.startsWith("http") -> path
                                path.startsWith("/") -> "https://cubari.moe$path"
                                else -> "https://cubari.moe/$path"
                            }
                            val target = fullUrl.replace("/proxy/api/", "/read/api/").let {
                                if (it.endsWith("/")) it else "$it/"
                            }
                            val req = Request.Builder().url(target).headers(headers).build()
                            val resp = client.newCall(req).execute()
                            if (resp.isSuccessful) resp.parseAs<List<String>>(json) else emptyList()
                        }

                        if (urls.isNotEmpty()) {
                            return@runCatching urls
                        }
                    }
                }
                emptyList<String>()
            }.getOrDefault(emptyList())
        }

        internal fun resolveMangadexUrls(client: OkHttpClient, headers: Headers, uuid: String): List<String> = runCatching<List<String>> {
            val cubariProxyUrl = "https://cubari.moe/read/api/mangadex/chapter/$uuid/"
            val proxyReq = Request.Builder().url(cubariProxyUrl).headers(headers).build()
            val resp = client.newCall(proxyReq).execute()
            if (resp.isSuccessful) {
                resp.parseAs<List<String>>(json)
            } else {
                val atHomeUrl = "https://api.mangadex.org/at-home/server/$uuid"
                val atHomeReq = Request.Builder().url(atHomeUrl).headers(headers).build()
                val mdResp = client.newCall(atHomeReq).execute()
                val mdJson = mdResp.parseAs<JsonObject>(json)
                val baseUrl = mdJson["baseUrl"]!!.jsonPrimitive.content
                val chapter = mdJson["chapter"]!!.jsonObject
                val hash = chapter["hash"]!!.jsonPrimitive.content
                val data = chapter["data"]!!.jsonArray
                data.map { "$baseUrl/data/$hash/${it.jsonPrimitive.content}" }
            }
        }.getOrDefault(emptyList())
    }
}
