package eu.kanade.tachiyomi.extension.pt.timelinecomics

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class BloggerFeedDto(
    val feed: FeedContentDto? = null,
)

@Serializable
class FeedContentDto(
    @SerialName("openSearch\$totalResults")
    val totalResults: TextHolderDto? = null,
    val entry: List<FeedEntryDto>? = null,
)

@Serializable
class FeedEntryDto(
    val title: TextHolderDto? = null,
    val content: TextHolderDto? = null,
    val summary: TextHolderDto? = null,
    val published: TextHolderDto? = null,
    val category: List<CategoryDto>? = null,
    val link: List<LinkDto>? = null,
)

@Serializable
class TextHolderDto(
    @SerialName("\$t")
    val t: String = "",
)

@Serializable
class CategoryDto(
    val term: String = "",
)

@Serializable
class LinkDto(
    val rel: String = "",
    val href: String = "",
)
