package eu.kanade.tachiyomi.extension.pt.lycantoons

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LycanToonsTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val baseUrl = "https://lycantoons.com"

    // =========================================================================
    // 1. URL Normalization Tests
    // =========================================================================

    @Test
    fun normalizeImageUrlHandlesAbsoluteUrls() {
        val absolute = "https://cdn.lycantoons.com/file/lycantoons/covers/cover-1792266400098.webp"
        assertEquals(absolute, normalizeImageUrl(absolute))
    }

    @Test
    fun normalizeImageUrlHandlesProtocolRelativeUrls() {
        val protoRelative = "//cdn.lycantoons.com/file/lycantoons/covers/cover-123.webp"
        assertEquals("https:$protoRelative", normalizeImageUrl(protoRelative))
    }

    @Test
    fun normalizeImageUrlHandlesFilePrefix() {
        val fileRel = "/file/lycantoons/covers/cover-123.webp"
        assertEquals("https://cdn.lycantoons.com$fileRel", normalizeImageUrl(fileRel))

        val noLeadingSlash = "file/lycantoons/covers/cover-123.webp"
        assertEquals("https://cdn.lycantoons.com/$noLeadingSlash", normalizeImageUrl(noLeadingSlash))
    }

    @Test
    fun normalizeImageUrlHandlesRelativePaths() {
        val relative = "/covers/sample.jpg"
        assertEquals("https://lycantoons.com/covers/sample.jpg", normalizeImageUrl(relative, baseUrl))
    }

    @Test
    fun normalizeImageUrlHandlesEscapedSlashes() {
        val escaped = """https:\/\/cdn.lycantoons.com\/file\/lycantoons\/covers\/cover-123.webp"""
        assertEquals("https://cdn.lycantoons.com/file/lycantoons/covers/cover-123.webp", normalizeImageUrl(escaped))
    }

    @Test
    fun normalizeImageUrlReturnsNullForEmptyOrBlank() {
        assertNull(normalizeImageUrl(null))
        assertNull(normalizeImageUrl(""))
        assertNull(normalizeImageUrl("   "))
    }

    // =========================================================================
    // 2. Headers & Image Request Tests
    // =========================================================================

    @Test
    fun buildLycanImageRequestStrikesOriginAndSetsImageAccept() {
        val baseHeaders = Headers.Builder()
            .set("Origin", "https://lycantoons.com")
            .set("Sec-Fetch-Dest", "document")
            .set("Sec-Fetch-Mode", "navigate")
            .set("Sec-Fetch-Site", "none")
            .build()

        val req = buildLycanImageRequest(
            imageUrl = "https://cdn.lycantoons.com/file/lycantoons/sample.webp",
            baseUrl = baseUrl,
            baseHeaders = baseHeaders,
        )

        assertNull("Image request must NEVER send Origin header (triggers 403 on CDN)", req.header("Origin"))
        assertEquals("image", req.header("Sec-Fetch-Dest"))
        assertEquals("no-cors", req.header("Sec-Fetch-Mode"))
        assertEquals("same-site", req.header("Sec-Fetch-Site"))
        assertEquals("https://lycantoons.com/", req.header("Referer"))
        assertTrue("Accept header must request image content types", req.header("Accept")?.startsWith("image/") == true)
        assertEquals("https://cdn.lycantoons.com/file/lycantoons/sample.webp", req.url.toString())
    }

    @Test
    fun buildLycanImageRequestNormalizesRelativeUrls() {
        val req = buildLycanImageRequest(
            imageUrl = "/file/lycantoons/sample2.jpg",
            baseUrl = baseUrl,
        )

        assertEquals("https://cdn.lycantoons.com/file/lycantoons/sample2.jpg", req.url.toString())
        assertNull(req.header("Origin"))
        assertEquals("image", req.header("Sec-Fetch-Dest"))
        assertEquals("no-cors", req.header("Sec-Fetch-Mode"))
        assertEquals("same-site", req.header("Sec-Fetch-Site"))
        assertEquals("https://lycantoons.com/", req.header("Referer"))
    }

    // =========================================================================
    // 3. Client Interceptor Tests
    // =========================================================================

    @Test
    fun clientInterceptorRemovesOriginFromCdnRequests() {
        var interceptedOrigin: String? = "SHOULD_BE_REMOVED"
        var interceptedAccept: String? = null
        var interceptedReferer: String? = null
        var interceptedUserAgent: String? = null
        var interceptedDest: String? = null
        var interceptedMode: String? = null
        var interceptedSite: String? = null

        val client = OkHttpClient.Builder()
            .addInterceptor(LycanToonsInterceptor(baseUrl))
            .addInterceptor { chain ->
                val intercepted = chain.request()
                interceptedOrigin = intercepted.header("Origin")
                interceptedAccept = intercepted.header("Accept")
                interceptedReferer = intercepted.header("Referer")
                interceptedUserAgent = intercepted.header("User-Agent")
                interceptedDest = intercepted.header("Sec-Fetch-Dest")
                interceptedMode = intercepted.header("Sec-Fetch-Mode")
                interceptedSite = intercepted.header("Sec-Fetch-Site")
                okhttp3.Response.Builder()
                    .request(intercepted)
                    .protocol(okhttp3.Protocol.HTTP_2)
                    .code(200)
                    .message("OK")
                    .body(ByteArray(0).toResponseBody(null))
                    .build()
            }
            .build()

        val req = Request.Builder()
            .url("https://cdn.lycantoons.com/file/lycantoons/covers/cover-1.webp")
            .header("Origin", "https://lycantoons.com")
            .header("Sec-Fetch-Dest", "image")
            .header("Sec-Fetch-Mode", "no-cors")
            .build()

        client.newCall(req).execute().close()

        assertNull("Origin must be stripped by interceptor for CDN requests", interceptedOrigin)
        assertEquals("image", interceptedDest)
        assertEquals("no-cors", interceptedMode)
        assertEquals("same-site", interceptedSite)
        assertEquals("image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8", interceptedAccept)
        assertEquals("https://lycantoons.com/", interceptedReferer)
        assertNotNull(interceptedUserAgent)
    }

    @Test
    fun clientInterceptorRemovesOriginFromMainDomainGetRequests() {
        var interceptedOrigin: String? = "SHOULD_BE_REMOVED"

        val client = OkHttpClient.Builder()
            .addInterceptor(LycanToonsInterceptor(baseUrl))
            .addInterceptor { chain ->
                val intercepted = chain.request()
                interceptedOrigin = intercepted.header("Origin")
                okhttp3.Response.Builder()
                    .request(intercepted)
                    .protocol(okhttp3.Protocol.HTTP_2)
                    .code(200)
                    .message("OK")
                    .body(ByteArray(0).toResponseBody(null))
                    .build()
            }
            .build()

        val req = Request.Builder()
            .url("https://lycantoons.com/api/chapters/123/view-pages")
            .header("Origin", "https://lycantoons.com")
            .build()

        client.newCall(req).execute().close()

        assertNull("Origin must be stripped for lycantoons.com GET requests", interceptedOrigin)
    }

    @Test
    fun clientInterceptorSetsOriginAndSecFetchOnPostRequests() {
        var interceptedOrigin: String? = null
        var interceptedSecFetchSite: String? = null
        var interceptedSecFetchMode: String? = null
        var interceptedSecFetchDest: String? = null

        val client = OkHttpClient.Builder()
            .addInterceptor(LycanToonsInterceptor(baseUrl))
            .addInterceptor { chain ->
                val intercepted = chain.request()
                interceptedOrigin = intercepted.header("Origin")
                interceptedSecFetchSite = intercepted.header("Sec-Fetch-Site")
                interceptedSecFetchMode = intercepted.header("Sec-Fetch-Mode")
                interceptedSecFetchDest = intercepted.header("Sec-Fetch-Dest")
                okhttp3.Response.Builder()
                    .request(intercepted)
                    .protocol(okhttp3.Protocol.HTTP_2)
                    .code(200)
                    .message("OK")
                    .body(ByteArray(0).toResponseBody(null))
                    .build()
            }
            .build()

        val req = Request.Builder()
            .url("https://lycantoons.com/api/series")
            .post(ByteArray(0).toRequestBody(null))
            .build()

        client.newCall(req).execute().close()

        assertEquals("https://lycantoons.com", interceptedOrigin)
        assertEquals("same-origin", interceptedSecFetchSite)
        assertEquals("cors", interceptedSecFetchMode)
        assertEquals("empty", interceptedSecFetchDest)
    }

    @Test
    fun finalRequestEmittedForCoverLoadingMatchesBrowserImageSpec() {
        // Simulates Coil loading covers using source.headers and source.client
        val simulatedSourceHeaders = Headers.Builder()
            .set("Referer", "$baseUrl/")
            .set("Origin", baseUrl) // KeiSource default
            .removeAll("Origin") // LycanToons.configureHeaders override
            .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .build()

        var finalRequest: Request? = null
        val client = OkHttpClient.Builder()
            .addInterceptor(LycanToonsInterceptor(baseUrl))
            .addInterceptor { chain ->
                finalRequest = chain.request()
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_2)
                    .code(200)
                    .message("OK")
                    .body(ByteArray(0).toResponseBody(null))
                    .build()
            }
            .build()

        val coverUrl = "https://cdn.lycantoons.com/file/lycantoons/covers/cover-1792266400098.webp"
        val req = Request.Builder()
            .url(coverUrl)
            .headers(simulatedSourceHeaders)
            .build()

        client.newCall(req).execute().close()

        assertNotNull(finalRequest)
        assertNull("Coil cover request must NEVER have Origin header", finalRequest?.header("Origin"))
        assertEquals("image", finalRequest?.header("Sec-Fetch-Dest"))
        assertEquals("no-cors", finalRequest?.header("Sec-Fetch-Mode"))
        assertEquals("same-site", finalRequest?.header("Sec-Fetch-Site"))
        assertEquals("https://lycantoons.com/", finalRequest?.header("Referer"))
        assertTrue("Accept must request images", finalRequest?.header("Accept")?.startsWith("image/") == true)
    }

    @Test
    fun finalRequestEmittedForReaderLoadingMatchesBrowserImageSpec() {
        // Simulates HttpPageLoader calling imageRequest(page) and executing via client
        val simulatedHeaders = Headers.Builder()
            .set("Referer", "$baseUrl/")
            .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .build()

        val pageUrl = "https://cdn.lycantoons.com/file/lycantoons/uma-flecha/18/page-0.jpg"
        val readerReq = buildLycanImageRequest(pageUrl, baseUrl, simulatedHeaders)

        var finalRequest: Request? = null
        val client = OkHttpClient.Builder()
            .addInterceptor(LycanToonsInterceptor(baseUrl))
            .addInterceptor { chain ->
                finalRequest = chain.request()
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_2)
                    .code(200)
                    .message("OK")
                    .body(ByteArray(0).toResponseBody(null))
                    .build()
            }
            .build()

        client.newCall(readerReq).execute().close()

        assertNotNull(finalRequest)
        assertNull("Reader page request must NEVER have Origin header", finalRequest?.header("Origin"))
        assertEquals("image", finalRequest?.header("Sec-Fetch-Dest"))
        assertEquals("no-cors", finalRequest?.header("Sec-Fetch-Mode"))
        assertEquals("same-site", finalRequest?.header("Sec-Fetch-Site"))
        assertEquals("https://lycantoons.com/", finalRequest?.header("Referer"))
        assertTrue("Accept must request images", finalRequest?.header("Accept")?.startsWith("image/") == true)
    }

    // =========================================================================
    // 4. DTO & Parser Compatibility Tests
    // =========================================================================

    @Test
    fun seriesDtoMapsToSMangaWithNormalizedCover() {
        val dto = SeriesDto(
            title = "Test Manga",
            slug = "test-manga",
            coverUrl = "/file/lycantoons/covers/c1.webp",
            author = "Author A",
            artist = "Artist B",
            synopsis = "A cool synopsis",
            status = "ongoing",
        )

        assertEquals("Test Manga", dto.title)
        assertEquals("/series/test-manga", dto.canonicalUrl)
        assertEquals("https://cdn.lycantoons.com/file/lycantoons/covers/c1.webp", dto.normalizedThumbnailUrl)
        assertEquals("Author A", dto.author)
        assertEquals("Artist B", dto.artist)
        assertEquals("A cool synopsis", dto.cleanedDescription)
    }

    @Test
    fun rankingRowDtoMapsToSMangaWithNormalizedCover() {
        val dto = RankingRowDto(
            title = "Ranking Manga",
            slug = "ranking-manga",
            coverUrl = "//cdn.lycantoons.com/file/lycantoons/covers/c2.webp",
        )

        assertEquals("Ranking Manga", dto.title)
        assertEquals("/series/ranking-manga", dto.canonicalUrl)
        assertEquals("https://cdn.lycantoons.com/file/lycantoons/covers/c2.webp", dto.normalizedThumbnailUrl)
    }

    @Test
    fun chapterDtoConstructsCanonicalUrls() {
        val chapter = ChapterDto(
            id = 12345,
            numero = JsonPrimitive(18),
            createdAt = "2026-10-17T19:46:41.735Z",
            pageCount = 10,
        )

        assertEquals("Capítulo 18", chapter.chapterName)
        assertEquals("/series/test-slug/18?capituloId=12345&pages=10", chapter.canonicalUrl("test-slug"))
        assertEquals(18f, chapter.chapterNumber, 0.001f)
    }

    @Test
    fun chapterPagesDtoDeserialization() {
        val rawJson = """{"pages":["https://cdn.lycantoons.com/file/lycantoons/ch1/0.jpg","https://cdn.lycantoons.com/file/lycantoons/ch1/1.jpg"]}"""
        val dto = json.decodeFromString<ChapterPagesDto>(rawJson)
        assertEquals(2, dto.pages.size)
        assertEquals("https://cdn.lycantoons.com/file/lycantoons/ch1/0.jpg", dto.pages[0])
    }

    @Test
    fun extractPageUrlsHandlesPreWrappedJson() {
        val preWrapped = """<html><body><pre>{"pages":["/file/lycantoons/ch1/0.jpg"]}</pre></body></html>"""
        val urls = extractPageUrls(preWrapped, baseUrl, json)

        assertEquals(1, urls.size)
        assertEquals("https://cdn.lycantoons.com/file/lycantoons/ch1/0.jpg", urls[0])
    }

    @Test
    fun extractPageUrlsHandlesRegexFallback() {
        val rawBody = """\"pages\":[\"https:\\/\\/cdn.lycantoons.com\\/file\\/lycantoons\\/p1.webp\",\"https:\\/\\/cdn.lycantoons.com\\/file\\/lycantoons\\/p2.webp\"]"""
        val urls = extractPageUrls(rawBody, baseUrl, json)

        assertEquals(2, urls.size)
        assertEquals("https://cdn.lycantoons.com/file/lycantoons/p1.webp", urls[0])
        assertEquals("https://cdn.lycantoons.com/file/lycantoons/p2.webp", urls[1])
    }

    @Test
    fun cleanSynopsisFiltersRscPointers() {
        assertEquals(null, cleanSynopsis("\$1"))
        assertEquals(null, cleanSynopsis("\$L24"))
        assertEquals("Real synopsis text", cleanSynopsis("Real synopsis text"))
        assertEquals(null, cleanSynopsis(""))
    }

    // =========================================================================
    // 5. Anti-Abuse Block Protection Tests
    // =========================================================================

    @Test
    fun isAntiAbuseBlockDetectsFullCloudflareBlockPage() {
        val blockHtml = """
            <!doctype html><html lang=pt-BR><meta charset=utf-8><title>Bloqueado — LycanToons</title>
            <main><h1>Acesso bloqueado temporariamente</h1>
            <p>Você foi <b>banido temporariamente</b> por navegação suspeita.</p>
            <p>Isso acontece quando a sua conexão acessa a LycanToons por <b>aplicativos ou extensões de terceiros</b>, que não são autorizados pelo site.</p>
            <small>LycanToons · proteção anti-abuso</small></main>
        """.trimIndent()

        assertTrue(isAntiAbuseBlock(blockHtml))
    }

    @Test
    fun isAntiAbuseBlockDetectsPartialKeywords() {
        assertTrue(isAntiAbuseBlock("Acesso bloqueado temporariamente"))
        assertTrue(isAntiAbuseBlock("Bloqueado — LycanToons"))
        assertTrue(isAntiAbuseBlock("banido temporariamente por navegação suspeita"))
        assertTrue(isAntiAbuseBlock("LycanToons · proteção anti-abuso"))
    }

    @Test
    fun isAntiAbuseBlockReturnsFalseForNormalHtml() {
        org.junit.Assert.assertFalse(isAntiAbuseBlock("<html><head><title>Lycan Toons</title></head><body><h1>Ranking</h1></body></html>"))
        org.junit.Assert.assertFalse(isAntiAbuseBlock("""{"series":[{"title":"Solo Leveling"}]}"""))
    }

    @Test(expected = LycanAntiAbuseException::class)
    fun interceptorThrowsLycanAntiAbuseExceptionOnBlockPage() {
        val blockHtml = """
            <!doctype html><html lang=pt-BR><meta charset=utf-8><title>Bloqueado — LycanToons</title>
            <main><h1>Acesso bloqueado temporariamente</h1>
            <p>Você foi <b>banido temporariamente</b> por navegação suspeita.</p>
            <small>LycanToons · proteção anti-abuso</small></main>
        """.trimIndent()

        val client = OkHttpClient.Builder()
            .addInterceptor(LycanToonsInterceptor(baseUrl))
            .addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_2)
                    .code(403)
                    .message("Forbidden")
                    .body(blockHtml.toResponseBody(null))
                    .build()
            }
            .build()

        val req = Request.Builder()
            .url("https://lycantoons.com/series?page=1")
            .build()

        client.newCall(req).execute()
    }

    @Test
    fun interceptorDoesNotThrowOnCdnImage403() {
        val client = OkHttpClient.Builder()
            .addInterceptor(LycanToonsInterceptor(baseUrl))
            .addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_2)
                    .code(403)
                    .message("Forbidden")
                    .body("Image forbidden".toResponseBody(null))
                    .build()
            }
            .build()

        val req = Request.Builder()
            .url("https://cdn.lycantoons.com/file/lycantoons/ch1/0.jpg")
            .build()

        val resp = client.newCall(req).execute()
        assertEquals(403, resp.code)
    }

    // =========================================================================
    // 6. Search API DTO Tests
    // =========================================================================

    @Test
    fun seriesSearchRequestSerialization() {
        val searchJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
        val req = SeriesSearchRequest(
            search = "solo",
            limit = 20,
            page = 2,
            seriesType = "MANHWA",
            status = "ONGOING",
            genre = listOf("action", "fantasy"),
        )
        val jsonStr = searchJson.encodeToString(SeriesSearchRequest.serializer(), req)
        assertTrue(jsonStr.contains(""""search":"solo""""))
        assertTrue(jsonStr.contains(""""limit":20"""))
        assertTrue(jsonStr.contains(""""page":2"""))
        assertTrue(jsonStr.contains(""""seriesType":"MANHWA""""))
        assertTrue(jsonStr.contains(""""status":"ONGOING""""))
        assertTrue(jsonStr.contains(""""genre":["action","fantasy"]"""))
    }

    @Test
    fun seriesSearchResponseDeserialization() {
        val raw = """
            {
                "series": [
                    {
                        "title": "Solo Leveling",
                        "slug": "solo-leveling",
                        "coverUrl": "/file/lycantoons/covers/solo.webp",
                        "status": "COMPLETED"
                    }
                ],
                "pagination": {
                    "currentPage": 1,
                    "totalPages": 5,
                    "totalItems": 100,
                    "itemsPerPage": 20,
                    "hasNextPage": true,
                    "hasPrevPage": false
                }
            }
        """.trimIndent()
        val resp = json.decodeFromString<SeriesSearchResponse>(raw)
        assertEquals(1, resp.series.size)
        assertEquals("Solo Leveling", resp.series[0].title)
        assertEquals("/series/solo-leveling", resp.series[0].canonicalUrl)
        assertEquals("https://cdn.lycantoons.com/file/lycantoons/covers/solo.webp", resp.series[0].normalizedThumbnailUrl)
        val pagination = resp.pagination
        assertNotNull(pagination)
        assertEquals(1, pagination?.currentPage)
        assertEquals(5, pagination?.totalPages)
        assertEquals(100, pagination?.totalItems)
        assertTrue(pagination?.hasNextPage == true)
    }
}
