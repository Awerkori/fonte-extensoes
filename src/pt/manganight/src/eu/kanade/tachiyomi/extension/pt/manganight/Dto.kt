package eu.kanade.tachiyomi.extension.pt.manganight

import kotlinx.serialization.Serializable

@Serializable
class MangaDexAtHomeDto(
    val baseUrl: String = "",
    val chapter: MangaDexChapterDto = MangaDexChapterDto(),
)

@Serializable
class MangaDexChapterDto(
    val hash: String = "",
    val data: List<String> = emptyList(),
    val dataSaver: List<String> = emptyList(),
)
