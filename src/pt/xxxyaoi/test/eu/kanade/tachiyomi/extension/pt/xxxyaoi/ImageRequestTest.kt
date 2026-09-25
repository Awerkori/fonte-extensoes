package eu.kanade.tachiyomi.extension.pt.xxxyaoi

import eu.kanade.tachiyomi.source.model.Page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class ImageRequestTest {
    private val chapterUrl = "https://3xyaoi.com/bl/work/capitulo-1/"

    @Test fun imageRequestUsesOnlyImageHeaders() {
        val request = buildImageRequest(Page(0, chapterUrl, "https://3xyaoi.com/wp-content/uploads/001.jpg"), "UA")

        assertEquals("UA", request.header("User-Agent"))
        assertEquals("image/*", request.header("Accept"))
        assertEquals(chapterUrl, request.header("Referer"))
        assertFalse(request.headers.names().any { it.equals("Origin", true) })
        assertFalse(request.headers.names().any { it.startsWith("Sec-Fetch-", true) })
        assertEquals(null, request.header("Upgrade-Insecure-Requests"))
        assertEquals("GET", request.method)
        assertEquals(setOf("User-Agent", "Accept", "Referer"), request.headers.names())
    }

    @Test fun rejectsInjectedHeaderMetadata() {
        assertThrows(IllegalArgumentException::class.java) {
            buildImageRequest(Page(0, "$chapterUrl\nOrigin=malicious", "https://3xyaoi.com/wp-content/uploads/001.jpg"), "UA")
        }
    }

    @Test fun rejectsCredentialsInChapterUrl() {
        assertThrows(IllegalArgumentException::class.java) {
            buildImageRequest(Page(0, "https://user:password@3xyaoi.com/chapter/", "https://3xyaoi.com/001.jpg"), "UA")
        }
    }
}
