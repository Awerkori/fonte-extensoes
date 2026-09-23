package eu.kanade.tachiyomi.extension.pt.onereader

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkDetailsDtoTest {
    @Test
    fun publisherStringParses() = assertPublisher("\"Editora antiga\"", "Editora antiga")

    @Test
    fun publisherObjectParsesAndKeepsChapters() {
        val details = parse("{\"id\":\"go\",\"name\":\"Naver\",\"websiteUrl\":\"https://example.org\"}")

        assertEquals("A Garota do Go", details.work.title)
        assertEquals("Naver", details.work.publisher)
        assertEquals(listOf(44.0, 45.0), details.chapters.map { it.number })
    }

    @Test
    fun publisherNullParses() = assertPublisher("null", null)

    @Test
    fun publisherAbsentParses() {
        val details = json.decodeFromString<WorkDetailsDto>(payload(null))

        assertEquals("A Garota do Go", details.work.title)
        assertEquals(2, details.chapters.size)
    }

    private fun assertPublisher(publisher: String, expected: String?) {
        val details = parse(publisher)
        assertEquals(expected, details.work.publisher)
        assertEquals(2, details.chapters.size)
    }

    private fun parse(publisher: String): WorkDetailsDto = json.decodeFromString(payload(publisher))

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }

    private fun payload(publisher: String?): String = """
        {
          "work": {
            "id": "go",
            "title": "A Garota do Go"${publisher?.let { ",\n            \"publisher\": $it" }.orEmpty()}
          },
          "chapters": [
            {"id": "chapter-44", "number": 44},
            {"id": "chapter-45", "number": 45}
          ]
        }
    """.trimIndent()
}
