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
    @Test fun reversedLazyImagesDoNotFetchScripts() = runBlocking {
        val expected = listOf("https://3xyaoi.com/wp-content/uploads/chapter/01.jpg", "https://3xyaoi.com/wp-content/uploads/chapter/02.jpg?key=abc")
        val html = "<div class='reading-content'>" + expected.joinToString("") {
            "<div class='page-break'><img class='wp-manga-chapter-img xyaoi-lazy-sec' data-xsec='${it.reversed()}' src='data:image/svg+xml;utf8,placeholder'></div>"
        } + "</div><script src='/theme.js'></script>"
        assertEquals(expected, Reader.load(Jsoup.parse(html, "https://3xyaoi.com/bl/love-jinx/capitulo-bonus-04/")) { error("Reader must not fetch theme scripts") })
    }

    @Test fun reversedLazyImagesPreserveMixedPagesWithoutSiteClasses() {
        val path = "/chapter/02.jpg?a=1&b=2"
        assertEquals(
            listOf("https://3xyaoi.com/chapter/01.jpg", "https://3xyaoi.com$path", "https://cdn.example/03.webp"),
            read("<main><img src='/chapter/01.jpg'><img data-xsec='${path.reversed().replace("&", "&amp;")}' src='data:image/svg+xml,placeholder'><img data-src='https://cdn.example/03.webp'></main>"),
        )
    }

    @Test fun reversedLazyImagesRejectNonImageSchemesAndUseValidFallback() {
        assertEquals(
            listOf("https://3xyaoi.com/chapter/01.jpg"),
            read("<div class='reading-content'><img data-xsec='${"javascript:alert(1)".reversed()}' data-src='/chapter/01.jpg'><img data-xsec='${"data:image/svg+xml,placeholder".reversed()}'></div>"),
        )
    }

    private val urls = listOf("https://cdn.example/001.webp?token=test", "https://cdn.example/002.webp")
    private val json = urls.joinToString(",", "[", "]") { "\"$it\"" }
    private val secureKey = "3b57af5690964954d52a18d"

    private fun cssFixture(pages: List<String> = urls, key: String = "abc123", seed: Int = 632, shift: Int = 6): String {
        val text = pages.joinToString(",", "[", "]") { "\"$it\"" }
        val encrypted = ByteArray(text.length) { i -> ((text[i].code xor key[i % key.length].code) + seed + i * shift).toByte() }
        return """
            <style>:root { --x-thm-test: "$key"; }</style>
            <div id="r_test"><span class="cl_test" data-d="${Base64.getEncoder().encodeToString(encrypted)}"></span></div>
            <script>
            function _rdec(key, seed, shift, b64) {}
            function mountRealChapterImages() {
                var container = document.getElementById("r_test");
                var salt = getComputedStyle(document.documentElement).getPropertyValue("--x-thm-test");
                var payload = container.querySelector(".cl_test").getAttribute("data-d");
                var decrypted = _rdec(salt, $seed, $shift, payload);
            }
            </script>
        """
    }

    @Test fun unusedCssScriptDoesNotOverrideReversedImages() = runBlocking {
        val document = Jsoup.parse(cssFixture(), "https://3xyaoi.com/bl/work/chapter/")
        document.select("#r_test").remove()
        document.body().append("<div class='reading-content'>" + urls.joinToString("") { "<img data-xsec='${it.reversed()}' src='data:image/svg+xml,placeholder'>" } + "</div><script src='/theme.js'></script>")
        assertEquals(urls, Reader.load(document) { error("Unexpected external script request") })
    }

    @Test fun unusedCssScriptDoesNotMakeRealCssReaderAmbiguous() {
        val unused = Jsoup.parse(cssFixture().replace("r_test", "absent_container"))
            .select("script").outerHtml()
        assertEquals(urls, read(cssFixture() + unused))
    }

    @Test fun cssPayloadTakesPriorityOverRc4WarningDecoy() {
        val html = cssFixture() + currentSecureReaderFixture(listOf("https://3xyaoi.com/wp-content/uploads/warning_app.jpg"))
        assertEquals(urls, Reader.extract(Jsoup.parse(html, "https://3xyaoi.com/bl/test/chapter/")))
    }

    @Test fun cssReaderUsesFreshChapterParametersAndPreservesOrder() {
        for (seed in listOf(632, 917)) {
            val pages = urls.reversed() + urls.last()
            assertEquals(urls.reversed(), read(cssFixture(pages, "chapter$seed", seed, 9)))
        }
    }

    @Test fun brokenCssPayloadDoesNotFallBackToDecoyOrFetchScripts() = runBlocking {
        val html = cssFixture().replace("data-d=", "missing=") + currentSecureReaderFixture()
        var fetched = false
        try {
            Reader.load(Jsoup.parse(html)) { fetched = true; null }
            throw AssertionError("Expected invalid payload")
        } catch (_: Reader.PagesNotFound) {
            assertFalse(fetched)
        }
    }

    @Test fun cssReaderStillRejectsActualWarningPayload() {
        assertThrows(Reader.BrowserRestricted::class.java) {
            read(cssFixture(listOf("https://3xyaoi.com/wp-content/uploads/warning_app.jpg?x=1")))
        }
    }

    @Test fun cssReaderAcceptsRenamedVariablesFunctionsAndAttributes() {
        val html = cssFixture().replace("_rdec", "decodeCurrent").replace("mountRealChapterImages", "renderPages")
            .replace("container", "box").replace("salt", "themeKey").replace("payload", "encryptedPages")
            .replace("data-d", "data-images").replace("r_test", "chapter_7").replace("cl_test", "pages_8")
            .replace("--x-thm-test", "--reader-v2")
        assertEquals(urls, read(html))
    }

    @Test fun cssReaderAcceptsSpacingQuotesAndHexParameters() {
        val html = cssFixture().replace('"', '\'').replace("(\"", "( \"")
            .replace("getElementById(", "getElementById (\n ")
            .replace("querySelector(", "querySelector ( ")
            .replace("getPropertyValue(", "getPropertyValue ( ")
            .replace("getAttribute(", "getAttribute ( ")
            .replace("_rdec(salt, 632, 6, payload)", "_rdec( salt, /* seed */ 0x278,\n0x06, payload )")
        assertEquals(urls, read(html))
    }

    @Test fun cssReaderAcceptsHtmlRootAndImportant() {
        val html = cssFixture().replace(":root", "html, :root").replace("\"abc123\";", "'abc123' !important;")
            .replace("<style>", "<style>/* theme */ .other { color: red; }")
        assertEquals(urls, read(html))
    }

    @Test fun cssReaderRejectsConflictingStylesInsteadOfChoosingAKey() {
        val html = cssFixture().replace("</style>", ":root { --x-thm-test: wrong; }</style>")
        assertThrows(Reader.PagesNotFound::class.java) { read(html) }
    }

    @Test fun cssReaderAcceptsRepeatedIdenticalDeclarations() {
        assertEquals(urls, read(cssFixture().replace("</style>", ":root { --x-thm-test: abc123; }</style>")))
    }

    @Test fun cssReaderRejectsMissingKeyCorruptionAndAmbiguousPayload() {
        val html = cssFixture()
        for (broken in listOf(
            html.replace("--x-thm-test:", "--unrelated:"),
            html.replace("data-d=\"", "data-d=\"!"),
            html.replace("</div>", "<span class='cl_test' data-d='unknown'></span></div>"),
            html.replace("\".cl_test\"", "\"[\""),
        )) {
            assertThrows(Reader.PagesNotFound::class.java) { read(broken) }
        }
    }

    @Test fun cssReaderNeverSilentlyDropsBrokenPages() {
        for (bad in listOf("not-an-image", "javascript:alert(1)", "https://user:pass@cdn.example/001.jpg", "https://cdn.example/001-350x476.png")) {
            assertThrows(Reader.PagesNotFound::class.java) { read(cssFixture(urls + bad)) }
        }
    }

    @Test fun cssReaderDetectsWarningAnywhereInSequence() {
        for (warning in listOf("/wp-content/uploads/warning_app.jpg", "//3xyaoi.com/wp-content/uploads/warning_app.jpg?x=1", "warning_app.jpg")) {
            assertThrows(Reader.BrowserRestricted::class.java) { read(cssFixture(urls + warning)) }
        }
    }

    @Test fun cssReaderPreservesLongChapterAndSignedImageQueries() {
        val pages = (1..500).map { "https://cdn.example/page-$it.webp?signature=abc%2Fdef&expires=123" }
        assertEquals(pages, read(cssFixture(pages)))
    }

    @Test fun cssReaderDoesNotMixConcurrentChapters() {
        val executor = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val tasks = (1..20).map { chapter ->
                java.util.concurrent.Callable {
                    val pages = listOf("https://cdn.example/chapter-$chapter/001.jpg")
                    assertEquals(pages, read(cssFixture(pages, "key$chapter", chapter * 19, chapter)))
                }
            }
            executor.invokeAll(tasks).forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun cssReaderDoesNotFetchExternalScriptsOnSuccess() = runBlocking {
        var fetched = false
        val actual = Reader.load(Jsoup.parse(cssFixture(), "https://3xyaoi.com/chapter/")) { fetched = true; null }
        assertEquals(urls, actual)
        assertFalse(fetched)
    }

    private fun rc4(input: ByteArray, key: String): ByteArray {
        val state = IntArray(256) { it }
        var j = 0
        for (i in state.indices) {
            j = (j + state[i] + key[i % key.length].code) % 256
            state[i] = state[j].also { state[j] = state[i] }
        }
        var i = 0
        j = 0
        return ByteArray(input.size) { index ->
            i = (i + 1) % 256
            j = (j + state[i]) % 256
            state[i] = state[j].also { state[j] = state[i] }
            ((input[index].toInt() and 0xFF) xor state[(state[i] + state[j]) % 256]).toByte()
        }
    }

    private fun currentSecureReaderFixture(pages: List<String> = urls): String {
        val payload = Base64.getEncoder().encodeToString(rc4(pages.joinToString(",", "[", "]") { "\"$it\"" }.toByteArray(), secureKey)).chunked(19).joinToString("~")
        val keyExpression = secureKey.map { "String.fromCharCode(${it.code + 30} - 30)" }.joinToString("+")
        return """
            <div class="reading-content">
              <div id="r_fixture" class="xyaoi-secure-reader-container" data-c_gate='{"pages":[],"error":"App access disabled"}'></div>
              <script type="text/template" id="v_fixture">$payload</script>
              <script>var containerId="r_fixture";var vaultId="v_fixture";function _rc4(){};var secKey=$keyExpression;</script>
            </div>
        """.trimIndent()
    }

    // Minimal fixture of the public upstream's documented mechanism; not captured live HTML.
    private fun encoded(quote: String = "'", declaration: String = "let", attr: String = "data-pages", space: String = " ", key: String = "reader-key") : String {
        val bytes = json.toByteArray()
        val keyBytes = key.toByteArray()
        val payload = Base64.getEncoder().encodeToString(ByteArray(bytes.size) { (bytes[it].toInt() xor keyBytes[it % keyBytes.size].toInt()).toByte() })
        return """<section $attr="$payload"></section><script>$declaration secret${space}=${space}$quote$key$quote;const attribute=$quote$attr$quote; /* page-break atob charCodeAt */</script>"""
    }

    private fun read(html: String, fallback: () -> List<String> = { emptyList() }) = Reader.extract(Jsoup.parse(html, "https://3xyaoi.com/bl/work/chapter/"), fallback)

    private fun assertBrowserRestricted(html: String) {
        val error = assertThrows(Reader.BrowserRestricted::class.java) { read(html) }
        assertEquals(
            "O 3xYaoi bloqueou a leitura deste capítulo em clientes Mihon/Tachiyomi. Abra este capítulo pelo navegador oficial do site.",
            error.message,
        )
    }

    @Test fun sharedJsonAvailable() { assertEquals(urls, json.parseAs<List<String>>()) }
    @Test fun upstreamMechanism() = assertEquals(urls, read(encoded()))
    @Test fun currentSecureReaderPayload() = assertEquals(urls, read(currentSecureReaderFixture()))
    @Test fun secureReaderWarningIsBlockedBeforePageCreation() = assertBrowserRestricted(currentSecureReaderFixture(listOf("warning_app.jpg")))
    @Test fun secureReaderDoesNotProbeUnrelatedScripts() = runBlocking {
        val document = Jsoup.parse(currentSecureReaderFixture() + "<script src='/unrelated.js'></script>", "https://3xyaoi.com/bl/work/chapter/")
        assertEquals(urls, Reader.load(document) { error("secure reader must not probe scripts") })
    }
    @Test fun secureReaderWithoutWrapperOrVPrefixedId() {
        val html = currentSecureReaderFixture()
            .replace(Regex("(?s)<div class=\"reading-content\">.*?</div>\\s*"), "")
            .replace("v_fixture", "vault_fixture")
        assertEquals(urls, read(html))
    }
    @Test fun malformedSecureReaderFailsImmediately() = runBlocking {
        val document = Jsoup.parse(currentSecureReaderFixture(), "https://3xyaoi.com/bl/work/chapter/")
        document.selectFirst("script[type=text/template]")!!.text("%%%~invalid")
        val error = assertThrows(Reader.PagesNotFound::class.java) {
            runBlocking { Reader.load(document) { error("secure reader must not probe scripts") } }
        }
        assertTrue(error.message.orEmpty().contains("secure-payload-invalid"))
    }
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
    @Test fun htmlFallbackRejectsWordPressThumbnail() {
        val html = "<div class=reading-content><img src='/wp-content/uploads/2022/07/11-1-350x476.png'><img src='${urls[0]}'></div>"
        assertEquals(listOf(urls[0]), read(html))
    }
    @Test fun warningAppRelativeWithQueryIsBlocked() = assertBrowserRestricted("<script type=application/json>{\"pages\":[\"warning_app.jpg?chapter=92\"]}</script>")
    @Test fun warningAppAbsoluteWithQueryIsBlocked() = assertBrowserRestricted("<script type=application/json>{\"pages\":[\"https://3xyaoi.com/wp-content/uploads/warning_app.jpg?chapter=92\"]}</script>")
    @Test fun warningAppDoesNotReplaceNormalPages() = assertEquals(urls, read("<script>const pages=$json;</script>"))
    @Test fun warningAppThumbnailIsNotRestriction() {
        val html = "<div class=reading-content><img src='/wp-content/uploads/warning_app-350x476.jpg'><img src='${urls[0]}'></div>"
        assertEquals(listOf(urls[0]), read(html))
    }
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
    @Test fun madaraCancellationDoesNotStartExternalRequests() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                Reader.load(Jsoup.parse("<script src='/one.js'></script>", "https://3xyaoi.com/"), { throw CancellationException() }) {
                    error("Cancelled reader must not request scripts")
                }
            }
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
