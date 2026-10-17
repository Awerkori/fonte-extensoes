package eu.kanade.tachiyomi.extension.pt.kuromangas

import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64 as JavaBase64

class KuroMangasTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Test
    fun constantsMatchCurrentWebsite() {
        assertEquals("_vx", TOKEN_COOKIE)
        assertEquals("X-Request-Ctx", TOKEN_HEADER)
        assertEquals("8at7vo8l6sai74jyIHfE2oeshoforanuYTusF4jKdqEwhUEft9dsadcxzsaipnjm8", DEFAULT_ENC_KEY)
        assertEquals("kuromangas.com::v2", HOSTNAME_PART)
        assertEquals("x9_4v2_b", ANTIBOT)
    }

    @Test
    fun derivesPasswordWithExpectedMd5Suffix() {
        val decryptor = KuroMangasDecryptor("https://kuromangas.com", OkHttpClient()) { true }
        val date = "2026-10-17"
        val expectedToHash = "2026-10-17kuromangas.com::v2x9_4v2_b"
        val expectedMd5 = MessageDigest.getInstance("MD5")
            .digest(expectedToHash.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .substring(0, 8)

        val derived = decryptor.derivePassword(date)
        assertEquals(DEFAULT_ENC_KEY + expectedMd5, derived)
        assertEquals(DEFAULT_ENC_KEY.length + 8, derived.length)
    }

    @Test
    fun rabbitCipherEncryptDecryptSymmetry() {
        val rabbit1 = Rabbit()
        val rabbit2 = Rabbit()

        val key = ByteArray(16) { (it * 17).toByte() }
        val iv = ByteArray(8) { (it * 31).toByte() }

        rabbit1.setup(key, iv)
        rabbit2.setup(key, iv)

        val originalText = "{\"status\":\"ok\",\"mangas\":[{\"id\":1,\"title\":\"Test Manga\"}]}"
        val plaintext = originalText.toByteArray(Charsets.UTF_8)
        val ciphertext = plaintext.copyOf()

        rabbit1.crypt(ciphertext)
        // Ciphertext should differ from plaintext
        assertTrue(!ciphertext.contentEquals(plaintext))

        // Decrypt with fresh instance setup with same key/iv
        rabbit2.crypt(ciphertext)
        assertEquals(originalText, String(ciphertext, Charsets.UTF_8))
    }

    @Test
    fun evpBytesToKeyDerivesCorrectLengths() {
        val decryptor = KuroMangasDecryptor("https://kuromangas.com", OkHttpClient()) { true }
        val password = "test_password".toByteArray()
        val salt = ByteArray(8) { it.toByte() }

        val (key, iv) = decryptor.evpBytesToKey(password, salt, keyLen = 16, ivLen = 8)
        assertEquals(16, key.size)
        assertEquals(8, iv.size)
    }

    @Test
    fun fullDecryptionPipelineWithDataKeyUnwrapping() {
        val decryptor = KuroMangasDecryptor("https://kuromangas.com", OkHttpClient()) { true }
        val password = decryptor.derivePassword()

        // Construct standard OpenSSL/CryptoJS format: "Salted__" (8 bytes) + 8-byte salt + rabbit ciphertext
        val magic = "Salted__".toByteArray(Charsets.US_ASCII)
        val salt = ByteArray(8) { (it + 42).toByte() }

        val (key, iv) = decryptor.evpBytesToKey(password.toByteArray(), salt)
        val rabbit = Rabbit().apply { setup(key, iv) }

        val payloadJson = """{"manga_payload":{"id":999,"title":"Decrypted Manga"}}"""
        val ciphertext = payloadJson.toByteArray(Charsets.UTF_8).copyOf()
        rabbit.crypt(ciphertext)

        val fullEncrypted = magic + salt + ciphertext
        val vSecure = JavaBase64.getEncoder().encodeToString(fullEncrypted)

        val decrypted = decryptor.decrypt(vSecure, "manga_payload")
        assertNotNull(decrypted)
        assertTrue(decrypted!!.contains("\"id\":999"))
        assertTrue(decrypted.contains("\"title\":\"Decrypted Manga\""))
    }

    @Test
    fun decryptionHandlesPayloadWhenDataKeyNotInObject() {
        val decryptor = KuroMangasDecryptor("https://kuromangas.com", OkHttpClient()) { true }
        val password = decryptor.derivePassword()

        val magic = "Salted__".toByteArray(Charsets.US_ASCII)
        val salt = ByteArray(8) { (it + 13).toByte() }

        val (key, iv) = decryptor.evpBytesToKey(password.toByteArray(), salt)
        val rabbit = Rabbit().apply { setup(key, iv) }

        val payloadJson = """[{"id":1},{"id":2}]"""
        val ciphertext = payloadJson.toByteArray(Charsets.UTF_8).copyOf()
        rabbit.crypt(ciphertext)

        val fullEncrypted = magic + salt + ciphertext
        val vSecure = JavaBase64.getEncoder().encodeToString(fullEncrypted)

        // Even if dataKey "non_existent" is passed, it safely returns the array wrapper
        val decrypted = decryptor.decrypt(vSecure, "non_existent")
        assertNotNull(decrypted)
        assertEquals(payloadJson, decrypted)
    }

    @Test
    fun buildThumbnailUrlHandlesVariations() {
        val cdn = "https://cdn.kuromangas.com"

        assertEquals(
            "https://cdn.kuromangas.com/mangas/1/cover.webp",
            buildThumbnailUrl(cdn, "/uploads/mangas/1/cover.webp"),
        )
        assertEquals(
            "https://cdn.kuromangas.com/mangas/1/cover.webp",
            buildThumbnailUrl(cdn, "uploads/mangas/1/cover.webp"),
        )
        assertEquals(
            "https://cdn.kuromangas.com/mangas/1/cover.webp",
            buildThumbnailUrl(cdn, "/mangas/1/cover.webp"),
        )
        assertEquals(
            "https://cdn.kuromangas.com/mangas/1/cover.webp",
            buildThumbnailUrl(cdn, "mangas/1/cover.webp"),
        )
        assertEquals(
            "https://external.cdn.com/cover.jpg",
            buildThumbnailUrl(cdn, "https://external.cdn.com/cover.jpg"),
        )
    }

    @Test
    fun parsesMangaListResponse() {
        val sample = """
            {
                "data": [
                    {
                        "id": 101,
                        "title": "Solo Leveling",
                        "description": "Sinopse do manga",
                        "status": "completed",
                        "cover_image": "/uploads/covers/101.jpg",
                        "author": "Chugong",
                        "artist": "DUBU",
                        "genres": ["Ação", "Fantasia"],
                        "alternative_titles": ["I Alone Level Up"]
                    }
                ],
                "pagination": {
                    "page": 1,
                    "limit": 24,
                    "total": 1,
                    "totalPages": 1
                }
            }
        """.trimIndent()

        val parsed = json.decodeFromString<MangaListResponse>(sample)
        assertEquals(1, parsed.data.size)
        assertEquals(101, parsed.data[0].id)
        assertEquals("Solo Leveling", parsed.data[0].title)
        assertEquals("Chugong", parsed.data[0].author)
        assertEquals("DUBU", parsed.data[0].artist)
        assertEquals(listOf("Ação", "Fantasia"), parsed.data[0].genres)
        assertEquals(listOf("I Alone Level Up"), parsed.data[0].alternativeTitles)
        assertEquals("completed", parsed.data[0].status)
    }

    @Test
    fun parsesLatestUpdatesResponseWithoutLimit() {
        val sample = """
            {
                "data": [
                    {
                        "manga_id": 202,
                        "manga_title": "One Piece",
                        "manga_cover": "/covers/202.jpg",
                        "manga_genres": ["Aventura", "Shounen"]
                    }
                ],
                "pagination": {
                    "page": 1,
                    "totalPages": 5,
                    "hasNext": true
                }
            }
        """.trimIndent()

        val parsed = json.decodeFromString<LatestResponse>(sample)
        assertEquals(1, parsed.data.size)
        assertEquals(202, parsed.data[0].mangaId)
        assertEquals("One Piece", parsed.data[0].mangaTitle)
        assertEquals("/covers/202.jpg", parsed.data[0].mangaCover)
        assertEquals(listOf("Aventura", "Shounen"), parsed.data[0].mangaGenres)
        assertTrue(parsed.pagination.hasNextPage())
        assertEquals(0, parsed.pagination.limit)
    }

    @Test
    fun parsesChaptersResponse() {
        val sample = """
            {
                "chapters": [
                    {
                        "id": 501,
                        "title": "O Despertar",
                        "chapter_number": "1.5",
                        "upload_date": "2026-03-15T12:00:00Z"
                    },
                    {
                        "id": 500,
                        "title": null,
                        "chapter_number": "1",
                        "upload_date": "2026-03-14T12:00:00Z"
                    }
                ],
                "pagination": {
                    "page": 1,
                    "limit": 60,
                    "totalPages": 1
                }
            }
        """.trimIndent()

        val parsed = json.decodeFromString<ChaptersResponse>(sample)
        assertEquals(2, parsed.chapters.size)
        assertEquals(501, parsed.chapters[0].id)
        assertEquals("O Despertar", parsed.chapters[0].title)
        assertEquals("1.5", parsed.chapters[0].chapterNumber)
        assertEquals("2026-03-15T12:00:00Z", parsed.chapters[0].uploadDate)

        assertEquals(500, parsed.chapters[1].id)
        assertEquals(null, parsed.chapters[1].title)
        assertEquals("1", parsed.chapters[1].chapterNumber)
    }

    @Test
    fun parsesChapterPagesResponse() {
        val sample = """
            {
                "id": 500,
                "pages": [
                    "/uploads/chapters/500/01.jpg",
                    "/chapters/500/02.jpg",
                    "https://cdn.kuromangas.com/chapters/500/03.jpg"
                ]
            }
        """.trimIndent()

        val parsed = json.decodeFromString<ChapterPagesResponse>(sample)
        assertEquals(3, parsed.pages.size)
        assertEquals(500, parsed.id)
    }

    @Test
    fun parsesSimilarResponse() {
        val sample = """
            {
                "data": [
                    {
                        "id": 303,
                        "title": "Similar Manga",
                        "cover_image": "/covers/303.jpg"
                    }
                ]
            }
        """.trimIndent()

        val parsed = json.decodeFromString<SimilarResponse>(sample)
        assertEquals(1, parsed.data.size)
        assertEquals(303, parsed.data[0].id)
    }

    @Test
    fun filterOptionsProduceExpectedValues() {
        val filters = getFilters()
        assertEquals(3, filters.size)

        val sort = filters[0] as SortFilter
        sort.state = 0
        assertEquals("view_count", sort.selectedSort)
        assertEquals("DESC", sort.selectedOrder)

        sort.state = 1
        assertEquals("average_rating", sort.selectedSort)
        assertEquals("DESC", sort.selectedOrder)

        sort.state = 2
        assertEquals("created_at", sort.selectedSort)
        assertEquals("DESC", sort.selectedOrder)

        sort.state = 3
        assertEquals("created_at", sort.selectedSort)
        assertEquals("ASC", sort.selectedOrder)

        sort.state = 4
        assertEquals("title", sort.selectedSort)
        assertEquals("ASC", sort.selectedOrder)

        sort.state = 5
        assertEquals("title", sort.selectedSort)
        assertEquals("DESC", sort.selectedOrder)

        val status = filters[1] as StatusFilter
        status.state = 0
        assertEquals("", status.selected)
        status.state = 1
        assertEquals("ongoing", status.selected)
        status.state = 2
        assertEquals("completed", status.selected)
        status.state = 3
        assertEquals("hiatus", status.selected)
        status.state = 4
        assertEquals("cancelled", status.selected)

        val type = filters[2] as TypeFilter
        type.state = 0
        assertEquals("", type.selected)
        type.state = 1
        assertEquals("manga", type.selected)
        type.state = 2
        assertEquals("manhwa", type.selected)
        type.state = 3
        assertEquals("manhua", type.selected)
        type.state = 4
        assertEquals("novel", type.selected)
        type.state = 5
        assertEquals("colored_manga", type.selected)
    }

    @Test
    fun cookieJarExtractsCorrectCookies() {
        val baseUrl = "https://kuromangas.com"
        val httpUrl = baseUrl.toHttpUrl()

        val cookieJar = object : okhttp3.CookieJar {
            val list = mutableListOf<Cookie>()
            override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<Cookie>) {
                list.addAll(cookies)
            }
            override fun loadForRequest(url: okhttp3.HttpUrl): List<Cookie> = list
        }

        val client = OkHttpClient.Builder().cookieJar(cookieJar).build()

        assertNull(client.getCookie(baseUrl, TOKEN_COOKIE))

        val cookie1 = Cookie.Builder().domain("kuromangas.com").path("/").name("_vx").value("test_token_123").build()
        val cookie2 = Cookie.Builder().domain("kuromangas.com").path("/").name("kuro_session").value("session_abc").build()
        cookieJar.saveFromResponse(httpUrl, listOf(cookie1, cookie2))

        assertEquals("test_token_123", client.getCookie(baseUrl, TOKEN_COOKIE))
        assertEquals("session_abc", client.getCookie(baseUrl, "kuro_session"))
    }
}
