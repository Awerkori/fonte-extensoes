package eu.kanade.tachiyomi.extension.pt.sukimangas

import kotlinx.serialization.Serializable

@Serializable
class SukiMangasCatalogDto(
    val mangas: List<SukiMangasMangaDto> = emptyList(),
)

@Serializable
class SukiMangasDetailsDto(
    val manga: SukiMangasMangaDto? = null,
)

@Serializable
class SukiMangasMangaDto(
    val id: String,
    val title: String,
    val titleOriginal: String? = null,
    val cover: String? = null,
    val coverThumb: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val group: String? = null,
    val status: String? = null,
    val synopsis: String? = null,
    val genres: List<String> = emptyList(),
    val type: String? = null,
    val views: Long = 0L,
    val viewsWeek: Long = 0L,
    val rating: Double? = null,
    val year: Int? = null,
    val chapters: List<SukiMangasChapterDto> = emptyList(),
)

@Serializable
class SukiMangasChapterDto(
    val id: String,
    val number: Double? = null,
    val title: String? = null,
    val releaseDate: String? = null,
    val createdAt: String? = null,
    val hasContent: Boolean = false,
    val pages: List<String> = emptyList(),
)
