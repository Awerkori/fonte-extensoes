package eu.kanade.tachiyomi.extension.pt.xxxyaoi

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Base64

internal object VhashReader {

    fun decodeUrl(vhash: String): String? {
        val clean = vhash.trim()
        if (clean.length < 8 || clean.length % 2 != 0) return null
        val half = clean.length / 2
        val reordered = clean.substring(clean.length - half) + clean.substring(0, clean.length - half)
        val bytes = ByteArray(reordered.length / 2)
        for (i in reordered.indices step 2) {
            val high = Character.digit(reordered[i], 16)
            val low = Character.digit(reordered[i + 1], 16)
            if (high == -1 || low == -1) return null
            bytes[i / 2] = ((high shl 4) or low).toByte()
        }
        val b64 = runCatching { Base64.getDecoder().decode(bytes) }.getOrNull() ?: return null
        val decoded = runCatching { String(b64, Charsets.UTF_8).trim() }.getOrNull() ?: return null
        return decoded.takeIf(String::isNotBlank)
    }

    private fun candidateElements(document: Document): List<Element> {
        val imgs = document.select(".reading-content img[data-vhash], .page-break img[data-vhash], [id*=reader] img[data-vhash], [class*=reader] img[data-vhash], main img[data-vhash], article img[data-vhash], img[data-vhash]")
        if (imgs.isNotEmpty()) return imgs
        return document.select(".reading-content [data-vhash], .page-break [data-vhash], [id*=reader] [data-vhash], [class*=reader] [data-vhash], [data-vhash]")
    }

    fun present(document: Document): Boolean {
        val elements = candidateElements(document)
        if (elements.isEmpty()) return false
        return elements.any { el ->
            val url = decodeUrl(el.attr("data-vhash"))
            url != null && Reader.normalize(listOf(url), document).isNotEmpty()
        }
    }

    fun extract(document: Document): List<String> {
        val elements = candidateElements(document)
        val urls = elements.mapNotNull { el ->
            decodeUrl(el.attr("data-vhash"))
        }
        val normalized = Reader.normalize(urls, document)
        if (normalized.isEmpty()) throw Reader.PagesNotFound("vhash-pages-empty")
        return normalized
    }
}
