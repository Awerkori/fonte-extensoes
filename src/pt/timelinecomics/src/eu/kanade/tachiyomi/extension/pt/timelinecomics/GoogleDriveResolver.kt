package eu.kanade.tachiyomi.extension.pt.timelinecomics

import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val resourceKey: String? = null,
)

object GoogleDriveResolver {

    private val fileIdRegex = Regex("""(?:file/d/|open\?id=|uc\?id=|download\?id=)([a-zA-Z0-9_-]+)""")
    private val folderIdRegex = Regex("""(?:drive/folders/|folderview\?id=)([a-zA-Z0-9_-]+)""")
    private val resourceKeyRegex = Regex("""[?&](?:resourcekey|rk)=([a-zA-Z0-9_-]+)""")
    private val ivdRegex = Regex("""window\['_DRIVE_ivd'\]\s*=\s*'([^']+)'""")
    private val hexEscapeRegex = Regex("""\\x([0-9a-fA-F]{2})""")

    fun extractFileId(url: String): String? {
        val httpUrl = url.toHttpUrlOrNull()
        if (httpUrl != null) {
            httpUrl.queryParameter("id")?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return fileIdRegex.find(url)?.groupValues?.get(1)
    }

    fun extractFolderId(url: String): String? = folderIdRegex.find(url)?.groupValues?.get(1)

    fun extractResourceKey(url: String): String? = resourceKeyRegex.find(url)?.groupValues?.get(1)

    fun getDownloadUrl(fileId: String, resourceKey: String? = null): String {
        val rkParam = if (!resourceKey.isNullOrBlank()) "&resourcekey=$resourceKey" else ""
        return "https://drive.usercontent.google.com/download?id=$fileId&export=download$rkParam"
    }

    fun parseFolder(
        client: OkHttpClient,
        folderId: String,
        headers: Headers,
        depth: Int = 0,
    ): List<DriveFile> {
        if (depth > 2) return emptyList()

        val folderUrl = "https://drive.google.com/drive/folders/$folderId"
        val request = Request.Builder()
            .url(folderUrl)
            .headers(headers)
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            return emptyList()
        }

        val html = response.body.string()
        val ivdMatch = ivdRegex.find(html) ?: return emptyList()
        val rawEscaped = ivdMatch.groupValues[1]

        val unescaped = hexEscapeRegex.replace(rawEscaped) { match ->
            match.groupValues[1].toInt(16).toChar().toString()
        }.replace("\\/", "/")

        val result = mutableListOf<DriveFile>()

        try {
            val rootArray = unescaped.parseAs<JsonArray>()
            val itemsArray = rootArray.firstOrNull()?.jsonArray ?: return emptyList()

            for (element in itemsArray) {
                if (element !is JsonArray) continue
                val item = element.jsonArray
                if (item.size < 4) continue

                val id = (item[0] as? JsonPrimitive)?.content ?: continue
                val name = (item[2] as? JsonPrimitive)?.content ?: continue
                val mimeType = (item[3] as? JsonPrimitive)?.content ?: ""

                if (mimeType.contains("folder", ignoreCase = true)) {
                    val subFiles = parseFolder(client, id, headers, depth + 1)
                    result.addAll(subFiles)
                } else if (mimeType.contains("pdf", ignoreCase = true) || name.endsWith(".pdf", ignoreCase = true)) {
                    result.add(DriveFile(id = id, name = name, mimeType = mimeType))
                }
            }
        } catch (_: Exception) {
            // Ignore corrupted or unrecognized JSON blocks
        }

        return result
    }
}
