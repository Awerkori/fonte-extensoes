package eu.kanade.tachiyomi.extension.pt.shiraiscans

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class Dto(
    val index: Int? = null,
    val page: Int? = null,
    @SerialName("img_id") val imgId: String? = null,
    @SerialName("wrapper_id") val wrapperId: String? = null,
    val url: String,
)
