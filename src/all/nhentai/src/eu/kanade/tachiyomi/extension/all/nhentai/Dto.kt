package eu.kanade.tachiyomi.extension.all.nhentai

import kotlinx.serialization.Serializable

@Serializable
class GalleryListDto(
    val result: List<GallerySummary> = emptyList(),
    val num_pages: Int = 1,
)

@Serializable
class GallerySummary(
    val id: Long,
    val media_id: String,
    val english_title: String? = null,
    val japanese_title: String? = null,
    val thumbnail: String? = null,
)

@Serializable
class GalleryDto(
    val id: Long,
    val media_id: String,
    val title: GalleryTitle,
    val thumbnail: GalleryImage,
    val pages: List<GalleryPage> = emptyList(),
    val tags: List<GalleryTag> = emptyList(),
    val upload_date: Long = 0,
    val num_favorites: Long = 0,
)

@Serializable
class GalleryTitle(
    val english: String? = null,
    val japanese: String? = null,
    val pretty: String? = null,
)

@Serializable
class GalleryImage(
    val path: String,
)

@Serializable
class GalleryPage(
    val path: String,
)

@Serializable
class GalleryTag(
    val type: String,
    val name: String,
)
