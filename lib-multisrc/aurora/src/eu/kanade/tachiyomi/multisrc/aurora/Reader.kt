package eu.kanade.tachiyomi.multisrc.aurora

import keiyoushi.utils.extractNextJs
import keiyoushi.utils.extractNextJsRsc
import keiyoushi.utils.parseAs
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

internal class ReaderPayload(val urls: List<String>, val gated: Boolean, val format: String)

internal fun extractReader(body: String, contentType: String, chapterUrl: String): ReaderPayload {
    val format = if ("text/x-component" in contentType) "rsc" else "html"
    val predicate = { element: kotlinx.serialization.json.JsonElement ->
        element is JsonObject && (element["pages"] as? JsonArray)?.isNotEmpty() == true
    }
    val dto = try {
        when {
            format == "rsc" -> body.extractNextJsRsc<PagesDto>(predicate)
            "text/html" in contentType -> Jsoup.parse(body, chapterUrl).extractNextJs<PagesDto>(predicate)
            else -> null
        }
    } catch (_: SerializationException) {
        null
    }
    return ReaderPayload(dto?.pages?.map { it.url }.orEmpty(), "ReadingGateScreen" in body, format)
}

private val unrelatedImage = Regex("^(?:placeholder|loading|logo)(?:[._-]|$)", RegexOption.IGNORE_CASE)

internal fun normalizePageUrl(raw: String, chapterUrl: String): String? {
    var value = raw.trim()
    if (value.startsWith('"') || '\\' in value) {
        value = try {
            (if (value.startsWith('"')) value else "\"$value\"").parseAs<String>()
        } catch (_: SerializationException) {
            return null
        }
    }
    value = Parser.unescapeEntities(value, false).trim()
    if (value.isEmpty() || value.startsWith("data:", true) || value.any { it.isISOControl() }) return null
    val url = chapterUrl.toHttpUrl().resolve(value) ?: return null
    if ("cover" in url.pathSegments || unrelatedImage.containsMatchIn(url.pathSegments.last())) return null
    return url.toString()
}

internal fun readerFailure(source: String, chapterUrl: String, status: Int, counts: Map<String, Int>): Nothing {
    val safeUrl = chapterUrl.toHttpUrl().newBuilder().query(null).fragment(null).build()
    error("Aurora reader: $source $safeUrl status=$status ${counts.entries.joinToString { "${it.key}=${it.value}" }}")
}
