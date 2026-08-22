package eu.kanade.tachiyomi.extension.pt.blackoutcomics

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class SearchResponseDto(
    val items: List<SearchItemDto>? = null,
)

@Serializable
class SearchItemDto(
    @SerialName("PJT_ID")
    val id: Long,
    @SerialName("PJT_NAME")
    val name: String,
    @SerialName("PJT_IMG_PR_URL")
    val imgUrl: String? = null,
    @SerialName("PJT_IMG_PR")
    val imgPath: String? = null,
) {
    fun toSManga(baseUrl: String): SManga = SManga.create().apply {
        url = "/comics/$id"
        title = name
        thumbnail_url = imgUrl ?: imgPath?.let { "$baseUrl/$it" }
    }
}

@Serializable
class SearchPayloadDto(
    val src: String,
)
