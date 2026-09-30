package eu.kanade.tachiyomi.extension.pt.portalyaoi

import eu.kanade.tachiyomi.multisrc.madara.MadaraNoAjax
import eu.kanade.tachiyomi.source.model.Page
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import okhttp3.Dns
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.Inet4Address
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Source
abstract class PortalYaoi : MadaraNoAjax() {
    override val chapterDateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.forLanguageTag("pt-BR"))

    override suspend fun getPopularManga(page: Int) = archivePage(page, "views", "/?post_type=wp-manga")

    override suspend fun getLatestUpdates(page: Int) = archivePage(page, "latest", "/?post_type=wp-manga")

    override fun OkHttpClient.Builder.configureClient() = rateLimit(1, 2.seconds) {
        !it.encodedPath.startsWith("/wp-content/uploads/")
    }.dns { hostname ->
        val addresses = Dns.SYSTEM.lookup(hostname)
        addresses.sortedBy { it !is Inet4Address }
    }

    override fun Headers.Builder.configureHeaders() = apply {
        set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
        set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        set("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
    }

    public override fun parsePages(document: Document): List<Page> = parsePagesFromDocument(document)

    open fun pageListParse(document: Document): List<Page> = parsePagesFromDocument(document)

    override fun imageFromElement(element: Element): String? {
        val url = when {
            element.hasAttr("data-py-vnode") -> {
                val key = extractVnodeKey(element.ownerDocument())
                decodePyVnode(element.attr("data-py-vnode"), key)
            }
            element.hasAttr("data-content-node") -> {
                val key = extractKey(element.ownerDocument())
                decodeContentNode(element.attr("data-content-node"), key)
            }
            element.hasAttr("data-obf") -> decodePageUrl(element.attr("data-obf"))
            else -> {
                val dataSrc = resolveImageUrl(element.attr("data-src"), element.attr("abs:data-src"))
                val dataLazy = resolveImageUrl(element.attr("data-lazy-src"), element.attr("abs:data-lazy-src"))
                dataSrc ?: dataLazy ?: super.imageFromElement(element)
            }
        }
        return url?.takeUnless { it.isBlank() || it.startsWith("data:") }
    }

    data class ParsedMangaDetails(
        val title: String,
        val author: String?,
        val artist: String?,
        val description: String?,
        val genre: String?,
        val thumbnail_url: String?,
    )

    companion object {
        const val DEFAULT_NODE_KEY = "8a4346524681d2d9322f02ebb94cf4bd7f4f57f1811d326936efc760cf140a88"
        const val DEFAULT_VNODE_KEY = "ec348a190c6be32a50747ae7be0dcbdda3da8e0bd3a0b76bf1a4bfa3b0dc3903"
        private val VNODE_KEY_REGEX = """(?:var|let|const)\s+__k\s*=\s*["']([^"']+)["']""".toRegex()
        private val KEY_REGEX = """(?:var|let|const)\s+_+k\s*=\s*["']([^"']+)["']""".toRegex()

        fun extractKey(document: Document?): String {
            if (document == null) return DEFAULT_NODE_KEY
            val scripts = document.select("script#lbl-shield-decrypt, script:containsData(_k)")
            for (element in scripts) {
                val match = KEY_REGEX.find(element.data())
                if (match != null) {
                    val found = match.groupValues[1].trim()
                    if (found.isNotEmpty()) return found
                }
            }
            return DEFAULT_NODE_KEY
        }

        fun extractVnodeKey(document: Document?): String {
            if (document == null) return DEFAULT_VNODE_KEY
            val scripts = document.select("script:containsData(__k), script:containsData(_k)")
            for (element in scripts) {
                val match = VNODE_KEY_REGEX.find(element.data()) ?: KEY_REGEX.find(element.data())
                if (match != null) {
                    val found = match.groupValues[1].trim()
                    if (found.isNotEmpty()) return found
                }
            }
            return DEFAULT_VNODE_KEY
        }

        fun decodePyVnode(value: String, key: String = DEFAULT_VNODE_KEY): String {
            if (value.isBlank()) return ""
            return runCatching {
                val s = value.reversed().trim()
                val klen = key.length
                val raw = ByteArray(s.length / 2) { i ->
                    val byteVal = s.substring(i * 2, i * 2 + 2).toInt(16)
                    val keyChar = key[i % klen].code
                    val xored = byteVal xor keyChar
                    val orig = (xored - 17 + 256) % 256
                    orig.toByte()
                }
                String(raw, Charsets.UTF_8).trim()
            }.getOrDefault("")
        }

        fun decodeContentNode(value: String, key: String = DEFAULT_NODE_KEY): String {
            if (value.isBlank()) return ""
            return runCatching {
                val raw = try {
                    java.util.Base64.getDecoder().decode(value.reversed().trim())
                } catch (_: Throwable) {
                    android.util.Base64.decode(value.reversed().trim(), android.util.Base64.DEFAULT)
                }
                val keyBytes = key.toByteArray(Charsets.UTF_8)
                val out = ByteArray(raw.size) { i ->
                    (raw[i].toInt() and 0xFF xor (keyBytes[i % keyBytes.size].toInt() and 0xFF)).toByte()
                }
                String(out, Charsets.UTF_8).trim()
            }.getOrDefault("")
        }

        fun decodePageUrl(value: String): String {
            if (value.isBlank()) return ""
            return runCatching {
                val raw = try {
                    java.util.Base64.getDecoder().decode(value.reversed().trim())
                } catch (_: Throwable) {
                    android.util.Base64.decode(value.reversed().trim(), android.util.Base64.DEFAULT)
                }
                String(raw, Charsets.UTF_8).trim()
            }.getOrDefault("")
        }

        fun resolveImageUrl(raw: String?, abs: String?): String? {
            if (raw == null) return null
            val trimmed = raw.trim()
            if (trimmed.isBlank() || trimmed.startsWith("data:")) return null
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
            return abs?.trim()?.takeIf(String::isNotBlank)
        }

        fun parsePagesFromDocument(document: Document): List<Page> {
            val vnodeImgs = document.select(".wp-manga-chapter-img[data-py-vnode]")
            if (vnodeImgs.isNotEmpty()) {
                val key = extractVnodeKey(document)
                return vnodeImgs.mapIndexedNotNull { index, element ->
                    val obfuscatedUrl = element.attr("data-py-vnode")
                    val realUrl = decodePyVnode(obfuscatedUrl, key)
                    realUrl.takeUnless { it.isBlank() || it.startsWith("data:") }?.let {
                        Page(index, document.location(), it)
                    }
                }
            }

            val contentNodeImgs = document.select(".wp-manga-chapter-img[data-content-node]")
            if (contentNodeImgs.isNotEmpty()) {
                val key = extractKey(document)
                return contentNodeImgs.mapIndexedNotNull { index, element ->
                    val obfuscatedUrl = element.attr("data-content-node")
                    val realUrl = decodeContentNode(obfuscatedUrl, key)
                    realUrl.takeUnless { it.isBlank() || it.startsWith("data:") }?.let {
                        Page(index, document.location(), it)
                    }
                }
            }

            val containerElements = document.select("div.page-break, li.blocks-gallery-item, .reading-content .text-left:not(:has(.blocks-gallery-item))")
            val imgElements = if (containerElements.isNotEmpty()) {
                containerElements.mapNotNull { it.selectFirst("img") }
            } else {
                document.select(".wp-manga-chapter-img, .reading-content img")
            }

            return imgElements.mapIndexedNotNull { index, img ->
                val url = when {
                    img.hasAttr("data-py-vnode") -> decodePyVnode(img.attr("data-py-vnode"), extractVnodeKey(img.ownerDocument()))
                    img.hasAttr("data-content-node") -> decodeContentNode(img.attr("data-content-node"), extractKey(img.ownerDocument()))
                    img.hasAttr("data-obf") -> decodePageUrl(img.attr("data-obf"))
                    else -> {
                        val dataSrc = resolveImageUrl(img.attr("data-src"), img.attr("abs:data-src"))
                        val dataLazy = resolveImageUrl(img.attr("data-lazy-src"), img.attr("abs:data-lazy-src"))
                        val src = resolveImageUrl(img.attr("src"), img.attr("abs:src"))
                        dataSrc ?: dataLazy ?: src
                    }
                }
                url?.takeUnless { it.isBlank() || it.startsWith("data:") }?.let {
                    Page(index, document.location(), it)
                }
            }
        }

        fun parseDetailsFromDocument(document: Document): ParsedMangaDetails = ParsedMangaDetails(
            title = document.selectFirst("div.post-title h3, div.post-title h1, #manga-title > h1")?.ownText()
                ?: document.selectFirst("div.post-title h1")?.text().orEmpty(),
            author = document.select("div.author-content > a, div.manga-authors > a").eachText().joinToString().ifBlank { null },
            artist = document.select("div.artist-content > a").eachText().joinToString().ifBlank { null },
            description = document.selectFirst("div.description-summary div.summary__content, div.summary_content div.post-content_item > h5 + div, div.summary_content div.manga-excerpt")?.text(),
            thumbnail_url = document.selectFirst("div.summary_image img")?.attr("abs:src"),
            genre = document.select("div.genres-content a").eachText().joinToString().ifBlank { null },
        )

        fun parseChapterListFromDocument(document: Document): List<Pair<String, String>> = document.select("li.wp-manga-chapter").mapNotNull { element ->
            val link = element.selectFirst("a") ?: return@mapNotNull null
            val href = link.attr("abs:href").trimEnd('/')
            val slug = href.substringAfterLast('/')
            val name = link.text().trim()
            slug to name
        }
    }
}
