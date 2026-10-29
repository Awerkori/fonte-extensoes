package keiyoushi.utils

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.gzip
import okio.source
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

@Serializable
data class NetworkLegacyExtensionRepo(
    @SerialName("index_v2")
    val indexV2: String?,
    val meta: Meta,
) {
    @Serializable
    data class Meta(
        val name: String,
        val shortName: String?,
        val website: String,
        val signingKeyFingerprint: String,
    )
}

@Serializable
data class NetworkExtensionStore(
    @ProtoNumber(1) val name: String,
    @ProtoNumber(2) val badgeLabel: String,
    @ProtoNumber(3) val signingKey: String,
    @ProtoNumber(4) val contact: Contact,
    @ProtoNumber(101) val extensionList: ExtensionList?,
    @ProtoNumber(102) val extensionListUrl: String?,
) {
    @Serializable
    data class Contact(
        @ProtoNumber(1) val website: String,
        @ProtoNumber(2) val discord: String?,
    )

    @Serializable
    data class ExtensionList(@ProtoNumber(1) val extensions: List<Extension>)

    @Serializable
    data class Extension(
        @ProtoNumber(1) val name: String,
        @ProtoNumber(2) val packageName: String,
        @ProtoNumber(3) val resources: Resources,
        @ProtoNumber(4) val extensionLib: String,
        @ProtoNumber(5) val versionCode: Long,
        @ProtoNumber(6) val versionName: String,
        @ProtoNumber(7) val contentWarning: ContentWarning,
        @ProtoNumber(8) val sources: List<Source>,
    )

    @Serializable
    data class Resources(
        @ProtoNumber(1) val apkUrl: String,
        @ProtoNumber(2) val iconUrl: String,
    )

    @Serializable
    data class Source(
        @ProtoNumber(1) val id: Long,
        @ProtoNumber(2) val name: String,
        @ProtoNumber(3) val language: String,
        @ProtoNumber(4) val homeUrl: String = "",
        @ProtoNumber(5) val mirrorUrls: List<String> = emptyList(),
        @ProtoNumber(7) val message: String? = null,
    )

    enum class ContentWarning {
        @ProtoNumber(0)
        @JsonNames("CONTENT_WARNING_UNSPECIFIED")
        UNSPECIFIED,

        @ProtoNumber(1)
        @JsonNames("CONTENT_WARNING_SAFE")
        SAFE,

        @ProtoNumber(2)
        @JsonNames("CONTENT_WARNING_MIXED")
        MIXED,

        @ProtoNumber(3)
        @JsonNames("CONTENT_WARNING_NSFW")
        NSFW,
    }
}

class MihonCatalogParserTest {

    private val client = OkHttpClient.Builder().followRedirects(true).build()
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private val protoBuf = ProtoBuf

    private fun fetch(url: String): ByteArray {
        val request = Request.Builder().url(url).header("User-Agent", "Mihon/0.17.1 (Android)").build()
        val response = client.newCall(request).execute()
        assertTrue("HTTP error for $url: ${response.code}", response.isSuccessful)
        return response.body!!.bytes()
    }

    private fun decompressIfGzipped(bytes: ByteArray): ByteArray {
        val isGzip = bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
        return if (isGzip) {
            ByteArrayInputStream(bytes).source().gzip().buffer().readByteArray()
        } else {
            bytes
        }
    }

    @Test
    fun testKeiyoushiPipeline() {
        println("=== TEST KEIYOUSHI PIPELINE ===")
        val repoJsonBytes = fetch("https://raw.githubusercontent.com/keiyoushi/extensions/repo/repo.json")
        val legacyRepo = json.decodeFromString<NetworkLegacyExtensionRepo>(repoJsonBytes.decodeToString())
        println("Keiyoushi repo name: ${legacyRepo.meta.name}, index_v2: ${legacyRepo.indexV2}")
        assertNotNull(legacyRepo.indexV2)

        val pbBytes = fetch(legacyRepo.indexV2!!)
        val decompressed = decompressIfGzipped(pbBytes)
        println("Keiyoushi index.pb decompressed size: ${decompressed.size}")

        val store = protoBuf.decodeFromByteArray<NetworkExtensionStore>(decompressed)
        println("Keiyoushi store parsed: name=${store.name}, badge=${store.badgeLabel}")
        assertNotNull(store.extensionList)
        println("Keiyoushi extensions count: ${store.extensionList!!.extensions.size}")

        // Test mapping like Mihon does
        store.extensionList!!.extensions.forEach { ext ->
            val d = ext.extensionLib.toDouble()
            assertTrue(d > 0.0)
            assertTrue(ext.resources.apkUrl.isNotEmpty())
            assertTrue(ext.resources.iconUrl.isNotEmpty())
        }
        println("KEIYOUSHI_PIPELINE=PASS")
    }

    @Test
    fun testNoxPipeline() {
        println("=== TEST NOX PIPELINE ===")
        val repoJsonBytes = fetch("https://raw.githubusercontent.com/Awerkori/manga-extensoes/repo/repo.json")
        val legacyRepo = json.decodeFromString<NetworkLegacyExtensionRepo>(repoJsonBytes.decodeToString())
        println("Nox repo name: ${legacyRepo.meta.name}, index_v2: ${legacyRepo.indexV2}")
        assertNotNull(legacyRepo.indexV2)

        val pbBytes = fetch(legacyRepo.indexV2!!)
        val decompressed = decompressIfGzipped(pbBytes)
        println("Nox index.pb decompressed size: ${decompressed.size}")

        val store = protoBuf.decodeFromByteArray<NetworkExtensionStore>(decompressed)
        println("Nox store parsed: name=${store.name}, badge=${store.badgeLabel}")
        assertNotNull(store.extensionList)
        println("Nox extensions count: ${store.extensionList!!.extensions.size}")

        store.extensionList!!.extensions.forEachIndexed { i, ext ->
            try {
                val d = ext.extensionLib.toDouble()
                assertTrue(d > 0.0)
                assertTrue(ext.resources.apkUrl.isNotEmpty())
                assertTrue(ext.resources.iconUrl.isNotEmpty())
            } catch (e: Exception) {
                println("Failed on extension $i: ${ext.packageName}: ${e.message}")
            }
        }
    }

    private fun fetchStore(indexUrl: String): NetworkExtensionStore {
        var updatedIndexUrl = indexUrl
        val request = Request.Builder().url(updatedIndexUrl).header("User-Agent", "Mihon/0.17.1 (Android)").build()
        val response = client.newCall(request).execute()
        assertTrue("HTTP error for $updatedIndexUrl: ${response.code}", response.isSuccessful)

        val rawBytes = response.body!!.bytes()
        val decompressed = decompressIfGzipped(rawBytes)
        val firstByte = decompressed[0]

        val networkStore = when (firstByte) {
            // "[..."
            0x5B.toByte() -> {
                if (!indexUrl.endsWith("/index.min.json")) {
                    throw IllegalArgumentException("Provided legacy store url is not valid: $indexUrl")
                }
                updatedIndexUrl = indexUrl.replace("/index.min.json", "/repo.json")
                val req2 = Request.Builder().url(updatedIndexUrl).header("User-Agent", "Mihon/0.17.1 (Android)").build()
                val resp2 = client.newCall(req2).execute()
                assertTrue("HTTP error for $updatedIndexUrl: ${resp2.code}", resp2.isSuccessful)
                json.decodeFromString<NetworkLegacyExtensionRepo>(resp2.body!!.string())
            }
            // "{..."
            0x7B.toByte() -> {
                try {
                    json.decodeFromString<NetworkLegacyExtensionRepo>(decompressed.decodeToString())
                } catch (e: Exception) {
                    println("JSON decode as NetworkLegacyExtensionRepo failed: ${e.message}, falling back to NetworkExtensionStore")
                    json.decodeFromString<NetworkExtensionStore>(decompressed.decodeToString())
                }
            }
            else -> {
                protoBuf.decodeFromByteArray<NetworkExtensionStore>(decompressed)
            }
        }

        if (networkStore is NetworkLegacyExtensionRepo && networkStore.indexV2 != null) {
            println("Following index_v2: ${networkStore.indexV2}")
            return fetchStore(networkStore.indexV2!!)
        }

        return networkStore as NetworkExtensionStore
    }

    @Test
    fun testAddRepoViaGithubRawPb() {
        println("=== TEST ADD REPO VIA github.com/.../raw/repo/index.pb ===")
        val store = fetchStore("https://github.com/Awerkori/manga-extensoes/raw/repo/index.pb")
        println("Fetched store: name=${store.name}, signingKey=${store.signingKey}, exts=${store.extensionList?.extensions?.size}")
        assertNotNull(store.extensionList)
    }

    @Test
    fun testAddRepoViaRawGithubPb() {
        println("=== TEST ADD REPO VIA raw.githubusercontent.com/.../index.pb ===")
        val store = fetchStore("https://raw.githubusercontent.com/Awerkori/manga-extensoes/repo/index.pb")
        println("Fetched store: name=${store.name}, signingKey=${store.signingKey}, exts=${store.extensionList?.extensions?.size}")
        assertNotNull(store.extensionList)
    }

    @Test
    fun testAddRepoViaRepoJson() {
        println("=== TEST ADD REPO VIA repo.json ===")
        val store = fetchStore("https://raw.githubusercontent.com/Awerkori/manga-extensoes/repo/repo.json")
        println("Fetched store: name=${store.name}, signingKey=${store.signingKey}, exts=${store.extensionList?.extensions?.size}")
        assertNotNull(store.extensionList)
    }

    @Test
    fun testAddRepoViaIndexMinJson() {
        println("=== TEST ADD REPO VIA index.min.json ===")
        val store = fetchStore("https://raw.githubusercontent.com/Awerkori/manga-extensoes/repo/index.min.json")
        println("Fetched store: name=${store.name}, signingKey=${store.signingKey}, exts=${store.extensionList?.extensions?.size}")
        assertNotNull(store.extensionList)
    }
}
