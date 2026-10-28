package eu.kanade.tachiyomi.extension.pt.manhq

import kotlinx.serialization.Serializable

@Serializable
class ManHQCatalogDto(
    val items: List<ManHQSeriesDto> = emptyList(),
    val nextCursor: String? = null,
)

@Serializable
class ManHQSearchResponseDto(
    val items: List<ManHQSeriesDto> = emptyList(),
    val pagination: ManHQSearchPaginationDto? = null,
)

@Serializable
class ManHQSearchPaginationDto(
    val page: Int = 1,
    val limit: Int = 24,
    val total: Int = 0,
    val totalPages: Int = 0,
)

@Serializable
class ManHQSeriesDto(
    val id: String,
    val title: String,
    val description: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val status: String? = null,
    val tags: String? = null,
    val coverUrl: String? = null,
    val workType: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val chaptersCount: Int? = null,
    val totalReads: Int? = null,
    val uniqueReaders: Int? = null,
    val medias: List<ManHQMediaDto> = emptyList(),
)

@Serializable
class ManHQMediaDto(
    val id: String,
    val title: String? = null,
    val number: Float? = null,
    val volume: Int? = null,
    val isOneShot: Boolean? = null,
    val isReady: Boolean? = null,
    val pageCount: Int? = null,
)

@Serializable
class ManHQChapterInfoDto(
    val id: String,
    val title: String? = null,
    val pageCount: Int = 0,
    val type: String? = null,
)
