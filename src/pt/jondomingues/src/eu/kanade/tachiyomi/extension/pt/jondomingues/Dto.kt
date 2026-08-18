package eu.kanade.tachiyomi.extension.pt.jondomingues

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class WpPostDto(
    val id: Int = 0,
    val link: String = "",
    val title: RenderedDto = RenderedDto(),
    val content: RenderedDto = RenderedDto(),
    val excerpt: RenderedDto = RenderedDto(),
    @SerialName("_embedded")
    val embedded: WpEmbeddedDto? = null,
)

@Serializable
class RenderedDto(
    val rendered: String = "",
)

@Serializable
class WpEmbeddedDto(
    @SerialName("wp:featuredmedia")
    val featuredMedia: List<WpMediaDto>? = null,
    @SerialName("wp:term")
    val terms: List<List<WpTermDto>>? = null,
)

@Serializable
class WpMediaDto(
    @SerialName("source_url")
    val sourceUrl: String = "",
)

@Serializable
class WpTermDto(
    val id: Int = 0,
    val name: String = "",
    val slug: String = "",
    val taxonomy: String = "",
)
