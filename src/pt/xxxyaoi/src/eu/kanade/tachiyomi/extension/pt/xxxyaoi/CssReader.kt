package eu.kanade.tachiyomi.extension.pt.xxxyaoi

import keiyoushi.utils.parseAs
import org.jsoup.nodes.Document
import org.jsoup.select.Selector.SelectorParseException
import java.util.Base64

internal object CssReader {
    private val containerId = Regex("""\bgetElementById\s*\(\s*["']([^"']+)["']\s*\)""")
    private val payloadSelector = Regex("""\bquerySelector\s*\(\s*["']([^"']+)["']\s*\)""")
    private val payloadAttribute = Regex("""\bgetAttribute\s*\(\s*["'](data-[\w-]+)["']\s*\)""")
    private val propertyName = Regex("""\bgetPropertyValue\s*\(\s*["'](--[\w-]+)["']\s*\)""")
    private val gap = """(?:\s|/\*[\s\S]*?\*/)*"""
    private val arguments = Regex("""=\s*[\w$]+\s*\($gap[\w$]+$gap,$gap(0[xX][\da-fA-F]+|\d+)$gap,$gap(0[xX][\da-fA-F]+|\d+)$gap,$gap[\w$]+$gap\)""")

    // Escape BOTH braces: Android's ICU rejects the unmatched closing brace accepted by the JDK.
    private val rootRule = Regex("""(?:^|(?<=\}))\s*(?::root|html)(?:\s*,\s*(?::root|html))*\s*\{([^{}]*)\}""")
    private val cssComment = Regex("""/\*[\s\S]*?\*/""")
    private val important = Regex("""\s*!important\s*$""", RegexOption.IGNORE_CASE)
    private val whitespace = Regex("""\s+""")
    private const val MAX_PAYLOAD_CHARS = 4_000_000

    fun present(document: Document) = script(document) != null

    private fun scripts(document: Document) = document.select("script:not([src])").map { it.data() }.filter {
        it.contains("getComputedStyle") && propertyName.containsMatchIn(it) && payloadAttribute.containsMatchIn(it)
    }.filter { source ->
        // Shared scripts may also appear on chapters using a different reader.
        containerId.findAll(source).any { document.getElementById(it.groupValues[1]) != null }
    }

    private fun script(document: Document) = scripts(document).firstOrNull()

    private fun unique(pattern: Regex, source: String, category: String): String = pattern.findAll(source).map { it.groupValues[1] }.distinct().toList().singleOrNull()
        ?: throw Reader.PagesNotFound(category)

    fun extract(document: Document): List<String> {
        val source = scripts(document).distinct().singleOrNull() ?: throw Reader.PagesNotFound("css-reader-missing-or-ambiguous")
        val id = unique(containerId, source, "css-container-missing-or-ambiguous")
        val selector = unique(payloadSelector, source, "css-selector-missing-or-ambiguous")
        val attribute = unique(payloadAttribute, source, "css-attribute-missing-or-ambiguous")
        val property = unique(propertyName, source, "css-property-missing-or-ambiguous")
        val args = arguments.findAll(source).map { it.groupValues.drop(1) }.distinct().toList().singleOrNull()
            ?: throw Reader.PagesNotFound("css-parameters-missing-or-ambiguous")
        fun number(raw: String) = (if (raw.startsWith("0x", true)) raw.drop(2).toLongOrNull(16) else raw.toLongOrNull())
            ?.takeIf { it in 0..Int.MAX_VALUE }?.toInt() ?: throw Reader.PagesNotFound("css-parameter-invalid")
        val seed = number(args[0])
        val shift = number(args[1])
        val declaration = Regex("""(?:^|;)\s*${Regex.escape(property)}\s*:\s*([^;}]+)""")
        val keys = document.select("style").flatMap { style ->
            rootRule.findAll(cssComment.replace(style.data(), " ")).flatMap { rule ->
                declaration.findAll(rule.groupValues[1]).map { it.groupValues[1].let { value -> important.replace(value, "") }.trim().trim('"', '\'') }
            }.toList()
        }.distinct()
        if (keys.size != 1 || keys.single().isEmpty()) throw Reader.PagesNotFound("css-key-missing-or-ambiguous")
        val key = keys.single()
        val container = document.getElementById(id) ?: throw Reader.PagesNotFound("css-container-missing")
        val element = try {
            container.select(selector).singleOrNull()
        } catch (_: SelectorParseException) {
            throw Reader.PagesNotFound("css-selector-invalid")
        }
        val payload = element?.attr(attribute)
            ?.takeIf(String::isNotBlank) ?: throw Reader.PagesNotFound("css-payload-missing")
        if (payload.length > MAX_PAYLOAD_CHARS) throw Reader.PagesNotFound("css-payload-too-large")
        val urls = try {
            val bytes = Base64.getDecoder().decode(whitespace.replace(payload, ""))
            val json = buildString(bytes.size) {
                bytes.forEachIndexed { index, byte ->
                    val value = (((byte.toInt() and 255) - seed - index * shift) and 255) xor key[index % key.length].code
                    append(value.toChar())
                }
            }
            json.parseAs<List<String>>()
        } catch (_: Exception) {
            throw Reader.PagesNotFound("css-payload-invalid")
        }
        // Never silently return a truncated chapter when only some entries remain valid.
        val normalized = urls.map { url ->
            Reader.normalize(listOf(url), document).singleOrNull() ?: throw Reader.PagesNotFound("css-page-invalid")
        }.distinct()
        return normalized.ifEmpty { throw Reader.PagesNotFound("css-pages-empty") }
    }
}
