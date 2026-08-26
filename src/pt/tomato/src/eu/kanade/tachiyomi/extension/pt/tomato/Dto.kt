package eu.kanade.tachiyomi.extension.pt.tomato

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable class FeedResponseDto(val data: List<FeedSectionDto> = emptyList())

@Serializable class FeedSectionDto(val type: Int, val data: List<FeedMangaDto> = emptyList(), val meta: FeedMetaDto? = null)

@Serializable class FeedMetaDto(val type: String? = null)

@Serializable class FeedMangaDto(val id: Long, val name: String, val thumbnail: String? = null)

@Serializable class SearchRequestDto(val token: String, val search: String, val page: Int, val tags: List<String>? = null) {
    @SerialName("content_type")
    val contentType = "manga"
}

@Serializable class SearchResponseDto(val result: List<SearchMangaDto> = emptyList())

@Serializable class SearchMangaDto(val id: Long, val name: String, val type: String, val image: String? = null)

@Serializable class MangaIdRequestDto(val id: Long, val token: String)

@Serializable class MangaDetailsResponseDto(val details: MangaDetailsDto)

@Serializable class MangaDetailsDto(
    val id: Long,
    val name: String,
    val cover: String? = null,
    val description: String? = null,
    val genre: String? = null,
    val author: String? = null,
    val status: String? = null,
)

@Serializable class ChaptersResponseDto(val data: List<ChapterDto> = emptyList())

@Serializable class ChapterDto(
    val id: Long,
    val name: String,
    val number: Float,
    @SerialName("source_url") val sourceUrl: String? = null,
    @SerialName("date_upload") val dateUpload: String? = null,
    val scanlator: String? = null,
) {
    fun toSChapter() = SChapter.create().apply {
        url = "/chapter/${sourceUrl?.takeIf { it.isNotEmpty() } ?: id}"
        name = this@ChapterDto.name
        chapter_number = number
        date_upload = dateUpload?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrDefault(0L) } ?: 0L
        this.scanlator = this@ChapterDto.scanlator
    }
}

@Serializable class PagesResponseDto(val data: List<PageDto> = emptyList())

@Serializable class PageDto(@SerialName("page_url") val pageUrl: String)

@Serializable class CategoriesResponseDto(val categories: List<CategoryDto> = emptyList())

@Serializable class CategoryDto(val name: String)

@Serializable class LoginRequestDto(val email: String, val password: String, val verification: String, val fingerprint: String)

@Serializable class CheckUpdateRequestDto(@SerialName("app_version") val appVersion: String)

@Serializable class CheckUpdateResponseDto(@SerialName("status_code") val statusCode: Int? = null, @SerialName("server_version") val serverVersion: String? = null, @SerialName("require_captcha") val requireCaptcha: Boolean? = null)

@Serializable class RegisterRequestDto(val username: String, val email: String, val password: String, val verification: String, val fingerprint: String)

@Serializable class AuthResponseDto(@SerialName("status_code") val statusCode: Int? = null, val token: String? = null, @SerialName("user_name") val userName: String? = null, val message: String? = null)

@Serializable class TokenLoginRequestDto(val token: String, val fingerprint: String)

@Serializable class TokenLoginResponseDto(@SerialName("status_code") val statusCode: Int? = null, @SerialName("user_name") val userName: String? = null)

fun FeedMangaDto.toSManga() = SManga.create().apply {
    url = "/v2/manga/$id"
    title = name
    thumbnail_url = thumbnail
}
fun SearchMangaDto.toSManga() = SManga.create().apply {
    url = "/v2/manga/$id"
    title = name
    thumbnail_url = image
}
