package eu.kanade.tachiyomi.extension.pt.egotoons

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EgoToonsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Test
    fun parsesLegacyChapterWithPaginasList() {
        val payload = """
            {
                "capitulo": {
                    "obra_id": 12598,
                    "numero": "1",
                    "numero_key": "1",
                    "titulo": "Capítulo 1",
                    "total_paginas": 2,
                    "paginas": [
                        "/cdn/page1.webp",
                        "/cdn/page2.webp"
                    ]
                }
            }
        """.trimIndent()

        val details = json.decodeFromString<ChapterDetailsDto>(payload)
        val pages = details.chapter.toPageList("https://egotoons.com", "https://egotoons.com/obra/12598/capitulo/1")

        assertEquals(2, pages.size)
        assertEquals(0, pages[0].index)
        assertEquals("https://egotoons.com/cdn/page1.webp", pages[0].imageUrl)
        assertEquals("https://egotoons.com/obra/12598/capitulo/1", pages[0].url)
        assertEquals(1, pages[1].index)
        assertEquals("https://egotoons.com/cdn/page2.webp", pages[1].imageUrl)
    }

    @Test
    fun parsesLegacyChapterWithPageUrlTemplate() {
        val payload = """
            {
                "capitulo": {
                    "obra_id": 12598,
                    "numero": "2",
                    "numero_key": "2",
                    "titulo": "Capítulo 2",
                    "total_paginas": 3,
                    "page_url_template": "/cdn/ch2/page_{index}.webp",
                    "paginas": []
                }
            }
        """.trimIndent()

        val details = json.decodeFromString<ChapterDetailsDto>(payload)
        val pages = details.chapter.toPageList("https://egotoons.com", "https://egotoons.com/obra/12598/capitulo/2")

        assertEquals(3, pages.size)
        assertEquals("https://egotoons.com/cdn/ch2/page_0.webp", pages[0].imageUrl)
        assertEquals("https://egotoons.com/cdn/ch2/page_1.webp", pages[1].imageUrl)
        assertEquals("https://egotoons.com/cdn/ch2/page_2.webp", pages[2].imageUrl)
    }

    @Test
    fun parsesCurrentChapterManifestWithRelativeUrls() {
        val payload = """
            {
                "sucesso": true,
                "capituloId": 1246827314,
                "obraId": 12598,
                "source": "directadmin",
                "totalPages": 3,
                "offset": 0,
                "limit": 24,
                "nextOffset": null,
                "pages": [
                    {
                        "index": 0,
                        "url": "/cdn/capitulos/1246827314/p/0?tst=token0",
                        "contentType": "image/webp"
                    },
                    {
                        "index": 1,
                        "url": "/cdn/capitulos/1246827314/p/1?tst=token1",
                        "contentType": "image/webp"
                    },
                    {
                        "index": 2,
                        "url": "/cdn/capitulos/1246827314/p/2?tst=token2",
                        "contentType": "image/webp"
                    }
                ]
            }
        """.trimIndent()

        val manifest = json.decodeFromString<ChapterManifestDto>(payload)
        val pages = manifest.toPageList("https://api.egotoons.com", "https://egotoons.com/obra/12598/capitulo/113")

        assertEquals(3, pages.size)
        assertEquals(0, pages[0].index)
        assertEquals("https://api.egotoons.com/cdn/capitulos/1246827314/p/0?tst=token0", pages[0].imageUrl)
        assertEquals("https://egotoons.com/obra/12598/capitulo/113", pages[0].url)
        assertEquals("https://api.egotoons.com/cdn/capitulos/1246827314/p/1?tst=token1", pages[1].imageUrl)
        assertEquals("https://api.egotoons.com/cdn/capitulos/1246827314/p/2?tst=token2", pages[2].imageUrl)
    }

    @Test
    fun parsesCurrentChapterManifestWithAbsoluteUrls() {
        val payload = """
            {
                "sucesso": true,
                "totalPages": 2,
                "offset": 0,
                "limit": 24,
                "nextOffset": null,
                "pages": [
                    {
                        "index": 0,
                        "url": "https://api.egotoons.com/cdn/p0.webp",
                        "contentType": "image/webp"
                    },
                    {
                        "index": 1,
                        "url": "https://api.egotoons.com/cdn/p1.webp",
                        "contentType": "image/webp"
                    }
                ]
            }
        """.trimIndent()

        val manifest = json.decodeFromString<ChapterManifestDto>(payload)
        val pages = manifest.toPageList("https://api.egotoons.com", "https://egotoons.com/obra/10/capitulo/1")

        assertEquals(2, pages.size)
        assertEquals("https://api.egotoons.com/cdn/p0.webp", pages[0].imageUrl)
        assertEquals("https://api.egotoons.com/cdn/p1.webp", pages[1].imageUrl)
    }

    @Test
    fun parsesDecimalChapterDto() {
        val payload = """
            {
                "obra_id": 10642,
                "numero": "97.5",
                "numero_key": "97.5",
                "titulo": "Capítulo 97.5",
                "nome": "Capítulo 97.5",
                "id": 1246819005
            }
        """.trimIndent()

        val chapter = json.decodeFromString<ChapterDto>(payload)

        assertEquals(10642, chapter.mangaId)
        assertEquals("97.5", chapter.number)
        assertEquals("97.5", chapter.numberKey)
        assertEquals("Capítulo 97.5", chapter.title)
        assertEquals(1246819005L, chapter.id)
    }
}
