package eu.kanade.tachiyomi.extension.pt.lermangaonline

import kotlinx.serialization.Serializable

@Serializable
class LerMangaOnlineReaderDto(
    val pages: List<String> = emptyList(),
    val originals: List<String> = emptyList(),
    val title: String? = null,
    val number: String? = null,
)

@Serializable
class LerMangaOnlineGenreDto(
    val name: String,
    val slug: String,
)

@Serializable
class LerMangaOnlineChapterConfigDto(
    val ajaxUrl: String? = null,
    val mangaId: String? = null,
    val page: String? = null,
    val perPage: String? = null,
    val total: String? = null,
)

@Serializable
class LerMangaOnlineChapterBatchDto(
    val success: Boolean = false,
    val data: LerMangaOnlineChapterBatchDataDto? = null,
)

@Serializable
class LerMangaOnlineChapterBatchDataDto(
    val page: Int? = null,
    val hasMore: Boolean = false,
    val html: String = "",
)
