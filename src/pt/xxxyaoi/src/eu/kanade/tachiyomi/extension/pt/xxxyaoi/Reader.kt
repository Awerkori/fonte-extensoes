package eu.kanade.tachiyomi.extension.pt.xxxyaoi

import keiyoushi.utils.parseAs
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.IOException
import java.util.Base64

internal object Reader {
    private val literals = Regex(""""(?:\\.|[^"\\])*+"|'(?:\\.|[^'\\])*+'|`(?:\\.|[^`\\])*+`""")
    private val nonUrl = Regex("[\\*{}<>]")
    private val imagePath = Regex("""\.(?:avif|gif|jpe?g|png|webp)(?:$|[?#])""", RegexOption.IGNORE_CASE)
    private val noise = Regex("""(?:^|[/_.\s-])(?:logo|avatar|icon|favicon|placeholder|loading|spinner|spacer|blank|banner)(?:$|[/_.\s-])""", RegexOption.IGNORE_CASE)
    private val base64 = Regex("[A-Za-z0-9+/]+={0,2}")
    private val unicodeEscape = Regex("""\\u([0-9a-fA-F]{4})|\\x([0-9a-fA-F]{2})""")
    private val srcsetEntry = Regex("""(?:^|,\s*)(\S+)\s+[\d.]+[wx](?=\s*(?:,|$))""")
    private val comments = Regex(literals.pattern + "|/\\*[\\s\\S]*?\\*/|//[^\\r\\n]*")
    private val literalTokens = Regex(literals.pattern + "|([A-Za-z_$][A-Za-z0-9_$]*)(\\s*:)|,(\\s*[}\\]])")
    private val imageFields = setOf("pages", "images", "pagelist", "imagelist", "chapterimages", "pageurls")
    private val ignoredFields = Regex("(?i)^(?:ads?|advertisements?|banners?|covers?|thumbnails?|related|recommendations|preload|prefetch|excludes?|includes?|ignore|cache|routes?|menu|navigation)$")
    private val nonPagePath = Regex("(?i)/(?:wp-admin|wp-json|wp-includes|wp-content/(?:plugins|themes))/")
    private val fieldName = Regex("[\"'`]?([A-Za-z_$][A-Za-z0-9_$]*)[\"'`]?$")
    private val readerSelector = ".reading-content, .page-break, [id*=reader], [class*=reader], [id*=chapter-images], [class*=chapter-content], [itemprop=articleBody]"
    private val secureKeyAssignment = Regex("""(?s)(?:var|let|const)\s+[A-Za-z_$][A-Za-z0-9_$]*\s*=\s*((?:String\.fromCharCode\s*\(\s*\d+\s*-\s*\d+\s*\)\s*\+?\s*)+);""")
    private val secureKeyPart = Regex("""String\.fromCharCode\s*\(\s*(\d+)\s*-\s*(\d+)\s*\)""")

    private class Candidate(val urls: List<String>, val explicit: Boolean)
    private val candidateOrder = compareByDescending<Candidate> { it.explicit }.thenByDescending { it.urls.size }

    class PagesNotFound(category: String) : IllegalStateException("XXX Yaoi: não foi possível localizar as páginas do capítulo (reader mudou; $category).")

    // Only scripts explicitly declared by the chapter are fetched, and only after local extraction fails.
    suspend fun load(document: Document, madara: () -> List<String> = { emptyList() }, fetchScript: suspend (String) -> String?): List<String> {
        val failure = try {
            return extract(document, madara)
        } catch (error: PagesNotFound) {
            error
        }
        val scripts = mutableListOf<String>()
        var size = 0
        for (url in externalScripts(document)) {
            val script = try {
                fetchScript(url)
            } catch (error: CancellationException) {
                throw error
            } catch (_: IOException) {
                null
            } ?: continue
            size += script.length
            if (size > 2_000_000) break
            if (script.length > 512_000) continue
            scripts += script
            try {
                return extract(document, madara, scripts)
            } catch (_: PagesNotFound) {
                // Keys and payloads can be declared in different script files.
            }
        }
        throw failure
    }

    internal fun externalScripts(document: Document): List<String> = document.select("script[src]").mapNotNull {
        it.absUrl("src").toHttpUrlOrNull()?.takeIf { url -> url.username.isEmpty() && url.password.isEmpty() }?.toString()
    }.distinct().sortedByDescending { it.contains("reader", true) || it.contains("chapter", true) }.take(64)

    fun extract(document: Document, madara: () -> List<String> = { emptyList() }, externalScripts: List<String> = emptyList()): List<String> {
        secureReaderPages(document)?.let { return it }
        val scripts = (
            document.select("script").map { script ->
                script.attr("src").takeIf { it.startsWith("data:text/javascript;base64,") }
                    ?.substringAfter(',')?.let(::decode)?.toString(Charsets.UTF_8) ?: script.data()
            } + externalScripts
            ).map { script -> comments.replace(script) { if (it.value.startsWith("//") || it.value.startsWith("/*")) " " else it.value } }
        val strings = scripts.flatMap { literals.findAll(it).map { match -> unquote(match.value) }.toList() }
        val attributes = document.allElements.flatMap { element ->
            element.attributes().filter { it.key.startsWith("data-") }.map { it.value }
        }
        val candidates = (attributes + strings).distinct()
        val payloads = candidates.mapNotNull { value ->
            value.takeIf { it.length >= 8 }?.let(::decode)
        }
        // Discover literal keys by a validated result, independent of JS identifiers/attribute names.
        val literalKeys = (strings + attributes).filter { it.isNotEmpty() && it.length <= 256 && !it.startsWith("data-") }
            .distinct().map { it.toByteArray(Charsets.UTF_8) }
        val numericKeys = scripts.flatMap { fragments(it, false) }.mapNotNull { array ->
            val entries = array.removePrefix("[").removeSuffix("]").split(',').map(String::trim).filter(String::isNotEmpty)
            if (entries.isEmpty() || entries.size > 256) return@mapNotNull null
            val codes = entries.map { entry ->
                if (entry.startsWith("0x", true)) entry.drop(2).toIntOrNull(16) else entry.toIntOrNull()
            }
            if (codes.any { it == null || it !in 0..255 }) return@mapNotNull null
            codes.mapNotNull { it?.toByte() }.toByteArray()
        }
        val keys = numericKeys + literalKeys
        for (payload in payloads) {
            jsonPages(payload.toString(Charsets.UTF_8), document, true)?.let { return it.urls }
            for (key in keys) {
                val first = payload.firstOrNull()?.toInt()?.xor(key[0].toInt())?.toChar()
                if (first !in listOf('[', '{', '"', ' ', '\n', '\r', '\t')) continue
                val decoded = ByteArray(payload.size) { i -> (payload[i].toInt() xor key[i % key.size].toInt()).toByte() }
                // A JSON list/object is mandatory before accepting a guessed key.
                jsonPages(decoded.toString(Charsets.UTF_8), document, true)?.let { return it.urls }
            }
        }
        val direct = (attributes + strings + scripts).flatMap { candidate ->
            listOf(candidate) + fragments(candidate)
        }.mapNotNull { candidate -> jsonPages(candidate, document) }
        // Reader HTML beats unrelated image arrays from analytics/configuration scripts.
        val containers = document.select(readerSelector)
        normalize(containers.flatMap { images(it) }, document).takeIf(List<String>::isNotEmpty)?.let { return it }
        // A renamed wrapper still needs a chapter main/article with several plausible page images.
        for (container in document.select("main, article")) {
            val urls = normalize(images(container), document)
            if (urls.size >= 2) return urls
        }
        val ranked = direct.sortedWith(candidateOrder).distinctBy { it.urls }
        ranked.firstOrNull()?.let { best ->
            if (ranked.getOrNull(1)?.let { it.urls.size == best.urls.size && it.explicit == best.explicit } == true) throw PagesNotFound("ambiguous-image-candidates")
            return best.urls
        }
        runCatching { normalize(madara(), document) }.getOrNull()?.takeIf(List<String>::isNotEmpty)?.let { return it }
        val category = when {
            payloads.isNotEmpty() && keys.isEmpty() -> "key-not-found"
            payloads.isNotEmpty() -> "payload-invalid"
            else -> "no-image-candidates"
        }
        throw PagesNotFound(category)
    }

    /** XXX Yaoi's current reader mounts pages from an RC4-encrypted text/template vault. */
    private fun secureReaderPages(document: Document): List<String>? {
        val container = document.selectFirst(".reading-content .xyaoi-secure-reader-container") ?: return null
        val script = document.select("script:not([src])").firstOrNull { element ->
            element.data().let { source ->
                source.contains("containerId") && source.contains("vaultId") &&
                    source.contains("_rc4") && source.contains("String.fromCharCode")
            }
        } ?: return null
        val source = script.data()
        val expression = secureKeyAssignment.find(source)?.groupValues?.get(1) ?: return null
        val codes = secureKeyPart.findAll(expression).map { match ->
            match.groupValues[1].toInt() - match.groupValues[2].toInt()
        }.toList()
        if (codes.size < 8 || codes.any { it !in 0..255 }) return null
        val key = codes.map(Int::toChar).joinToString("")
        val vaultId = Regex("""\b(?:var|let|const)\s+\w*vault\w*\s*=\s*[\"'](v_[A-Za-z0-9_-]+)[\"']""", RegexOption.IGNORE_CASE)
            .find(source)?.groupValues?.get(1)
        val vault = vaultId?.let(document::getElementById)
            ?: document.select("script[type=text/template][id^=v_]").firstOrNull()
            ?: return null
        if (container.id().isBlank() || vault.id().isBlank()) return null
        return runCatching {
            val encrypted = decode(vault.data().replace("~", "")) ?: return@runCatching null
            val pages = rc4(key, encrypted).parseAs<List<String>>()
            normalize(pages, document).takeIf(List<String>::isNotEmpty)
        }.getOrNull()
    }

    private fun rc4(key: String, input: ByteArray): String {
        val state = IntArray(256) { it }
        var j = 0
        for (i in state.indices) {
            j = (j + state[i] + key[i % key.length].code) and 0xFF
            state[i] = state[j].also { state[j] = state[i] }
        }
        var i = 0
        j = 0
        val output = ByteArray(input.size)
        input.forEachIndexed { index, byte ->
            i = (i + 1) and 0xFF
            j = (j + state[i]) and 0xFF
            state[i] = state[j].also { state[j] = state[i] }
            output[index] = ((byte.toInt() and 0xFF) xor state[(state[i] + state[j]) and 0xFF]).toByte()
        }
        return output.toString(Charsets.UTF_8)
    }

    private fun decode(value: String): ByteArray? {
        val compact = value.filterNot(Char::isWhitespace)
        if (!base64.matches(compact) || compact.length % 4 == 1) return null
        return runCatching { Base64.getDecoder().decode(compact) }.getOrNull()
    }

    private fun jsonPages(value: String, document: Document, trusted: Boolean = false): Candidate? {
        if (value.trimStart().firstOrNull() !in listOf('[', '{', '"')) return null
        val json = runCatching { value.parseAs<JsonElement>() }.getOrNull()
            ?: runCatching { literalJson(value).parseAs<JsonElement>() }.getOrNull() ?: return null
        return jsonLists(json, trusted = trusted).mapNotNull { (list, explicit) ->
            normalize(list, document).takeIf(List<String>::isNotEmpty)?.let { Candidate(it, explicit) }
        }.sortedWith(candidateOrder).firstOrNull()
    }

    private fun jsonLists(value: JsonElement, depth: Int = 0, trusted: Boolean = false): List<Pair<List<String>, Boolean>> {
        if (depth > 12) return emptyList()
        return when (value) {
            is JsonArray -> {
                if (value.isNotEmpty() && value.all { it is JsonPrimitive && it.isString }) {
                    val values = value.map { (it as JsonPrimitive).content }
                    return if (values.all { it.isBlank() || noise.containsMatchIn(it) || (looksLikeImage(it) && (trusted || imagePath.containsMatchIn(it))) }) listOf(values to trusted) else emptyList()
                }
                val urls = value.mapNotNull { item ->
                    when (item) {
                        is JsonPrimitive -> item.content.takeIf { item.isString && looksLikeImage(it) }
                        is JsonObject -> listOf("image", "imageUrl", "url", "src").firstNotNullOfOrNull { field ->
                            (item[field] as? JsonPrimitive)?.content?.takeIf { looksLikeImage(it) && (trusted || field != "url" || imagePath.containsMatchIn(it)) }
                        }
                        else -> null
                    }
                }
                if (urls.size == value.size && urls.isNotEmpty()) listOf(urls to trusted) else value.filterNot { it is JsonPrimitive }.flatMap { jsonLists(it, depth + 1, trusted) }
            }
            is JsonObject -> value.entries.filterNot { ignoredFields.matches(it.key) }.flatMap { (key, child) ->
                jsonLists(child, depth + 1, trusted || key.lowercase().replace("_", "") in imageFields)
            }
            is JsonPrimitive -> if (value.isString && value.content.trimStart().startsWith('[')) {
                runCatching { value.content.parseAs<JsonElement>() }.getOrNull()?.let { jsonLists(it, depth + 1, trusted) }.orEmpty()
            } else {
                emptyList()
            }
        }
    }

    private fun looksLikeImage(value: String): Boolean = !nonUrl.containsMatchIn(value) && (
        imagePath.containsMatchIn(value) ||
            (
                (value.startsWith("https://") || value.startsWith("http://") || value.startsWith("//") || value.startsWith('/')) &&
                    value.substringBefore('?').substringAfterLast('/').let { it.isNotEmpty() && !it.contains('.') }
                )
        )

    private fun images(container: Element): List<String> = container.select("img").filter { img ->
        img.parents().none {
            it.tagName() in listOf("header", "footer", "nav", "aside") || it.className().contains("related", true) ||
                (it.tagName() == "a" && it.hasAttr("href") && !imagePath.containsMatchIn(it.attr("href")))
        } &&
            !noise.containsMatchIn(img.className() + " " + img.id() + " " + img.attr("alt")) &&
            listOf("width", "height").none { img.attr(it).toIntOrNull()?.let { size -> size in 1..64 } == true }
    }.flatMap { img ->
        val attrs = listOf("data-src", "data-lazy-src", "data-original", "data-srcset", "srcset", "src") +
            img.attributes().filter { it.key.startsWith("data-") }.map { it.key }
        // Keep alternatives together; normalization chooses one usable URL per image.
        listOfNotNull(
            attrs.firstNotNullOfOrNull { attr ->
                val raw = img.attr(attr).trim()
                val url = if (attr.endsWith("srcset")) {
                    srcsetEntry.findAll(raw).lastOrNull()?.groupValues?.get(1) ?: raw.substringBefore(' ').trimEnd(',')
                } else {
                    raw
                }
                normalize(listOf(url), img.ownerDocument() ?: return@firstNotNullOfOrNull null).firstOrNull()
            },
        )
    }

    internal fun normalize(values: List<String>, document: Document): List<String> {
        val base = document.baseUri().toHttpUrlOrNull() ?: return emptyList()
        return values.mapNotNull { raw ->
            val value = Parser.unescapeEntities(raw, false).trim().replace("\\/", "/")
            if (!looksLikeImage(value) || value.startsWith("data:") || value.startsWith('#') || value.contains("\${")) return@mapNotNull null
            val url = base.resolve(value)?.takeIf { it.scheme == "http" || it.scheme == "https" } ?: return@mapNotNull null
            if (url.username.isNotEmpty() || url.password.isNotEmpty() || nonPagePath.containsMatchIn(url.encodedPath) || noise.containsMatchIn(url.encodedPath)) return@mapNotNull null
            url.toString()
        }.distinct()
    }

    private fun unquote(value: String): String = unicodeEscape.replace(value.substring(1, value.lastIndex)) { match ->
        (match.groups[1]?.value ?: match.groups[2]?.value).orEmpty().toInt(16).toChar().toString()
    }.replace("\\/", "/").replace("\\'", "'").replace("\\\"", "\"").replace("\\`", "`").replace("\\\\", "\\")

    private fun literalJson(value: String): String = literalTokens.replace(value) { match ->
        when {
            match.value.firstOrNull() in listOf('\'', '"', '`') -> JsonPrimitive(unquote(match.value)).toString()
            match.groups[1] != null -> JsonPrimitive(match.groupValues[1]).toString() + match.groupValues[2]
            else -> match.groupValues[3]
        }
    }

    private fun fragments(script: String, annotate: Boolean = true): List<String> {
        val starts = ArrayDeque<Triple<Int, Boolean, String>>()
        val result = mutableListOf<String>()
        var quote: Char? = null
        var escaped = false
        script.forEachIndexed { index, char ->
            if (quote != null) {
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == quote) {
                    quote = null
                }
            } else {
                when (char) {
                    '\'', '"', '`' -> quote = char
                    '[', '{' -> {
                        val prefix = script.substring((index - 100).coerceAtLeast(0), index).trimEnd().trimEnd(':', '=').trimEnd()
                        val field = fieldName.find(prefix)?.groupValues?.get(1).orEmpty()
                        starts.addLast(Triple(index, starts.lastOrNull()?.second == true || ignoredFields.matches(field), field))
                    }
                    ']', '}' -> if (starts.isNotEmpty()) {
                        val (start, ignored, field) = starts.removeLast()
                        if (!ignored) {
                            val fragment = script.substring(start, index + 1)
                            result += if (annotate && field.lowercase().replace("_", "") in imageFields) "{\"pages\":$fragment}" else fragment
                        }
                    }
                }
            }
        }
        return result
    }
}
