package eu.kanade.tachiyomi.extension.pt.xxxyaoi

import keiyoushi.utils.parseAs
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import java.io.IOException
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import java.util.Base64

class ReaderTest {
    private val urls = listOf("https://cdn.example/001.webp?token=test", "https://cdn.example/002.webp")
    private val json = urls.joinToString(",", "[", "]") { "\"$it\"" }

    // Minimal fixture of the public upstream's documented mechanism; not captured live HTML.
    private fun encoded(quote: String = "'", declaration: String = "let", attr: String = "data-pages", space: String = " ", key: String = "reader-key") : String {
        val bytes = json.toByteArray()
        val keyBytes = key.toByteArray()
        val payload = Base64.getEncoder().encodeToString(ByteArray(bytes.size) { (bytes[it].toInt() xor keyBytes[it % keyBytes.size].toInt()).toByte() })
        return """<section $attr="$payload"></section><script>$declaration secret${space}=${space}$quote$key$quote;const attribute=$quote$attr$quote; /* page-break atob charCodeAt */</script>"""
    }

    private fun read(html: String, fallback: () -> List<String> = { emptyList() }) = Reader.extract(Jsoup.parse(html, "https://3xyaoi.com/bl/work/chapter/"), fallback)

    @Test fun sharedJsonAvailable() { assertEquals(urls, json.parseAs<List<String>>()) }
    @Test fun upstreamMechanism() = assertEquals(urls, read(encoded()))
    @Test fun doubleQuotes() = assertEquals(urls, read(encoded(quote = "\"")))
    @Test fun backticks() = assertEquals(urls, read(encoded(quote = "`")))
    @Test fun constDeclaration() = assertEquals(urls, read(encoded(declaration = "const")))
    @Test fun varDeclaration() = assertEquals(urls, read(encoded(declaration = "var")))
    @Test fun minified() = assertEquals(urls, read(encoded(space = "")))
    @Test fun whitespace() = assertEquals(urls, read(encoded(space = "\n\t")))
    @Test fun renamedAttributeAndVariables() = assertEquals(urls, read(encoded(attr = "data-new-blob").replace("secret", "renamed").replace("attribute", "other")))
    @Test fun unicodeKey() = assertEquals(urls, read(encoded(key = "chavê-新")))
    @Test fun changedWrapperAndLazyLoad() = assertEquals(urls, read("<main><section class=new-layout>${urls.joinToString("") { "<img data-src='$it' src='/placeholder.png'>" }}</section></main>"))
    @Test fun htmlFallback() = assertEquals(urls, read("<div class=reading-content>${urls.joinToString("") { "<div><img src='$it'></div>" }}</div>"))
    @Test fun duplicatesKeepOrder() = assertEquals(urls.reversed(), read("<script>const pages=['${urls[1]}','${urls[0]}','${urls[1]}'];</script>"))
    @Test fun invalidPayloadFallsThrough() = assertEquals(urls, read("<div data-blob='%%%invalid%%%'></div><script>let key='';</script><div id=reader>${urls.joinToString("") { "<img data-original='$it'>" }}</div>"))
    @Test fun invalidPayloadHasDiagnostic() {
        val error = assertThrows(IllegalStateException::class.java) { read("<i data-blob='SGVsbG8gd29ybGQ='></i><script>const x='abc';</script>") }
        assertTrue(error.message.orEmpty().contains("payload-invalid"))
    }
    @Test fun missingPagesHasDiagnostic() {
        val error = assertThrows(IllegalStateException::class.java) { read("<html><header><img src='/logo.png'></header></html>") }
        assertTrue(error.message.orEmpty().contains("XXX Yaoi: não foi possível localizar as páginas"))
        assertFalse(error.message.orEmpty().contains("<html>"))
    }
    @Test fun nestedJson() = assertEquals(urls, read("<script type=application/json>{\"state\":{\"renamed\":$json}}</script>"))
    @Test fun inlineJsObjects() = assertEquals(urls, read("<script>window.state={pages:[{\"src\":\"${urls[0]}\"},{\"url\":\"${urls[1]}\"}]};</script>"))
    @Test fun plainBase64() = assertEquals(urls, read("<div data-any='${Base64.getEncoder().encodeToString(json.toByteArray())}'></div>"))
    @Test fun encodedScriptLocation() {
        val fixture = Jsoup.parse(encoded())
        val script = fixture.selectFirst("script")?.data().orEmpty()
        fixture.select("script").remove()
        assertEquals(urls, read(fixture.html() + "<script src='data:text/javascript;base64,${Base64.getEncoder().encodeToString(script.toByteArray())}'></script>"))
    }
    @Test fun normalizationAndNoise() {
        val html = """<div id=reader><img src='/logo.png'><img data-src='/001.jpg?a=1&amp;b=2'><img data-lazy-src='//cdn.example/002.png'><img data-original='../003.webp'><img srcset='/small.jpg 400w, /004.jpg 1200w'><img data-renamed='/005.jpg'><img src='javascript:alert(1)'><img src='data:image/gif;base64,abc'><img src='/avatar.png'></div>"""
        assertEquals(listOf("https://3xyaoi.com/001.jpg?a=1&b=2", "https://cdn.example/002.png", "https://3xyaoi.com/bl/work/003.webp", "https://3xyaoi.com/004.jpg", "https://3xyaoi.com/005.jpg"), read(html))
    }
    @Test fun blankAndPlaceholderInJson() = assertEquals(urls, read("<script>[\"\",\"/placeholder.png\",\"${urls[0]}\",\"${urls[1]}\"]</script>"))
    @Test fun extensionlessCdn() = assertEquals(listOf("https://cdn.example/image/123?format=webp"), read("<script>{\"pages\":[\"https://cdn.example/image/123?format=webp\"]}</script>"))
    @Test fun madaraFallback() = assertEquals(urls, read("<div></div>") { urls })
    @Test fun brokenMadaraFallback() {
        val error = assertThrows(IllegalStateException::class.java) { read("<div></div>") { error("broken protector") } }
        assertTrue(error.message.orEmpty().contains("reader mudou"))
    }
    @Test fun primaryWinsOverUnrelatedImages() = assertEquals(urls, read(encoded() + "<main><img src='/cover.jpg'><img src='/other-cover.jpg'></main>"))

    @Test fun longInlinePayload() {
        val pages = (1..2000).map { "https://cdn.example/chapter/$it.webp" }
        val body = pages.joinToString(",", "[", "]") { "\"$it\"" }
        val payload = Base64.getEncoder().encodeToString(body.toByteArray())
        assertEquals(pages, read("<script>const renamed='$payload';</script>"))
    }
    @Test fun unrelatedLinkedCoversAreNotPages() {
        assertThrows(IllegalStateException::class.java) { read("<main><a href='/bl/other/'><img src='/a.jpg'></a><a href='/bl/another/'><img src='/b.jpg'></a></main>") }
    }

    @Test fun srcsetPreservesCommaInCdnQuery() {
        assertEquals(listOf("https://cdn.example/002.webp?auto=format,compress"), read("<div id=reader><img srcset='https://cdn.example/001.webp?auto=format,compress 1x, https://cdn.example/002.webp?auto=format,compress 2x'></div>"))
    }

    @Test fun cacheWildcardListIsNotPages() {
        val cache = "<script>const excludes=[\"/wp-admin/*\",\"/wp-content/uploads/*\",\"/*/\"];</script>"
        assertThrows(IllegalStateException::class.java) { read(cache) }
        assertEquals(urls, read(cache + "<div id=reader>" + urls.joinToString("") { "<img src='$it'>" } + "</div>"))
    }

    private fun matrix(renamed: Boolean = false): String {
        val key = "08e8e80782c7b2b9"
        val keyBytes = key.toByteArray()
        val bytes = json.toByteArray()
        val payload = Base64.getEncoder().encodeToString(ByteArray(bytes.size) { (bytes[it].toInt() xor keyBytes[it % keyBytes.size].toInt()).toByte() })
        val codes = keyBytes.joinToString(",") { if (renamed) "0x" + it.toString(16) else it.toString() }
        val declaration = if (renamed) "const renamed=[$codes];" else "var rawCodes = [$codes];"
        return "<script>const excludes=['/wp-admin/*','/wp-content/*'];$declaration</script><div data-renamed='$payload'></div>"
    }
    @Test fun liveByteMatrixMechanism() = assertEquals(urls, read(matrix()))
    @Test fun renamedMinifiedHexByteMatrix() = assertEquals(urls, read(matrix(true)))

    @Test fun routeListsAreNotPages() {
        assertThrows(IllegalStateException::class.java) { read("<script>const paths=['/login','/about','https://example.org/route'];</script>") }
    }
    @Test fun configurationImagesAreNotPages() {
        val config = "<script>const cache={preload:['/wp-content/themes/site/a.jpg','/wp-content/plugins/foo/b.png'],ads:['https://ads.example/a.jpg','https://ads.example/b.jpg']};</script>"
        assertThrows(IllegalStateException::class.java) { read(config) }
        assertEquals(urls, read(config + "<script>const gallery=$json;</script>"))
    }
    @Test fun mixedRoutesAndImagesRejected() {
        assertThrows(IllegalStateException::class.java) { read("<script>['/login','/wp-admin','https://cdn.example/001.jpg']</script>") }
    }
    @Test fun commentsCannotProvideFakePagesOrBreakMatrix() {
        assertEquals(urls, read("<script>/* const pages=['https://bad.example/1.jpg','https://bad.example/2.jpg']; */</script>" + matrix().replace("48,", "48,/* byte */")))
    }
    @Test fun jsObjectsWithBareKeysAndTrailingComma() {
        assertEquals(urls, read("<script>const state={pages:[{src:'${urls[0]}',},{imageUrl:`${urls[1]}`,},],};</script>"))
    }
    @Test fun externalScriptAndSplitDeclarations() = runBlocking {
        val fixture = Jsoup.parse(encoded(), "https://3xyaoi.com/bl/work/chapter/")
        val script = fixture.select("script").first()?.data().orEmpty()
        fixture.select("script").remove()
        fixture.append("<script src='/assets/settings.js'></script><script defer src='//cdn.example/renamed.js?ver=2'></script>")
        val requested = mutableListOf<String>()
        val result = Reader.load(fixture) { url -> requested += url; if (url.contains("settings")) "const n=1;" else script }
        assertEquals(urls, result)
        assertEquals(listOf("https://3xyaoi.com/assets/settings.js", "https://cdn.example/renamed.js?ver=2"), requested)
    }
    @Test fun workingInlineReaderDoesNotFetchScripts() = runBlocking {
        assertEquals(urls, Reader.load(Jsoup.parse(encoded() + "<script src='/script.js'></script>", "https://3xyaoi.com/")) { error("Unexpected request") })
    }
    @Test fun failedExternalScriptDoesNotStopNext() = runBlocking {
        val fixture = Jsoup.parse("<script src='/one.js'></script><script src='/two.js'></script>", "https://3xyaoi.com/")
        assertEquals(urls, Reader.load(fixture) { if (it.endsWith("one.js")) throw IOException("unavailable") else "const pages=$json;" })
    }
    @Test fun externalScriptCancellationPropagates() {
        assertThrows(CancellationException::class.java) {
            runBlocking { Reader.load(Jsoup.parse("<script src='/one.js'></script>", "https://3xyaoi.com/")) { throw CancellationException() } }
        }
    }
    @Test fun externalScriptsAreBoundedAndExplicit() {
        val fixture = Jsoup.parse("<script src='javascript:bad'></script><a href='/unrelated.js'>x</a>" + (1..80).joinToString("") { "<script src='/$it.js'></script>" }, "https://3xyaoi.com/")
        assertEquals(64, Reader.externalScripts(fixture).size)
    }
    @Test fun htmlReaderWinsOverUnrelatedGenericImageArray() {
        assertEquals(urls, read("<script>const prefetch=['https://ads.example/a.jpg','https://ads.example/b.jpg','https://ads.example/c.jpg'];</script><div id=reader>" + urls.joinToString("") { "<img src='$it'>" } + "</div>"))
    }

    @Test fun competingImageListsFailDescriptively() {
        val html = "<script>const a=['https://a.example/1.jpg','https://a.example/2.jpg'];const b=['https://b.example/1.jpg','https://b.example/2.jpg'];</script>"
        assertTrue(assertThrows(Reader.PagesNotFound::class.java) { read(html) }.message.orEmpty().contains("ambiguous-image-candidates"))
    }
    @Test fun arbitraryUrlObjectsAreNotPages() {
        assertThrows(Reader.PagesNotFound::class.java) { read("<script>const links=[{url:'/login'},{url:'/register'}];</script>") }
    }
    @Test fun missingExternalScriptsStayDiagnostic() = runBlocking {
        val doc = Jsoup.parse("<script src='/missing.js'></script>", "https://3xyaoi.com/")
        try {
            Reader.load(doc) { null }
            error("Expected PagesNotFound")
        } catch (error: Reader.PagesNotFound) {
            assertTrue(error.message.orEmpty().contains("reader mudou"))
        }
    }

    @Test fun legitimateCoverPageAndMixedCdnsArePreserved() {
        val pages = listOf("https://a.example/cover.jpg", "https://b.example/01.webp")
        assertEquals(pages, read("<script>{pages:['${pages[0]}','${pages[1]}']}</script>"))
    }

    @Test fun explicitPagesWinOverLongerUnrelatedList() {
        val unrelated = (1..9).joinToString(",") { "'https://other.example/$it.jpg'" }
        assertEquals(urls, read("<script>const suggestions=[$unrelated];const pages=$json;</script>"))
    }

    @Test fun relocatedScriptAfterManyUnrelatedAssets() = runBlocking {
        val fixture = Jsoup.parse((1..25).joinToString("") { "<script src='/$it.js'></script>" }, "https://3xyaoi.com/")
        assertEquals(urls, Reader.load(fixture) { if (it.endsWith("25.js")) "const pages=$json;" else "const x=1;" })
    }

    companion object {
        @BeforeClass @JvmStatic fun setup() { Injekt.addSingleton<Json>(Json) }
    }
}
