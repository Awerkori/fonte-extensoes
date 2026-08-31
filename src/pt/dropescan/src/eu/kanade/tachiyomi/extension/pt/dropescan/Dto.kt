package eu.kanade.tachiyomi.extension.pt.dropescan

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
internal class WorkResponse(
    val data: WorkDto,
)

@Serializable
internal class WorkListResponse(
    val data: List<WorkDto> = emptyList(),
)

@Serializable
internal class RecentResponse(
    val data: RecentData,
)

@Serializable
internal class RecentData(
    val data: List<WorkDto> = emptyList(),
    val meta: PageMeta? = null,
)

@Serializable
internal class PageMeta(
    val Total: Int = 0,
    val NextPageUrl: String? = null,
)

@Serializable
internal class WorkDto(
    val ObraId: String,
    val Title: String,
    val Synopsis: String? = null,
    val Status: String? = null,
    val ContentTarger: String? = null,
    val ContentWarning: String? = null,
    val Banners: List<BannerDto> = emptyList(),
    val Genres: List<GenreDto> = emptyList(),
    val Authors: List<PersonDto> = emptyList(),
    val Artists: List<PersonDto> = emptyList(),
    val Chapters: List<ChapterDto> = emptyList(),
)

@Serializable
internal class BannerDto(
    val Source: String? = null,
    val isPrimary: Boolean = false,
)

@Serializable
internal class GenreDto(
    val GenreName: String? = null,
)

@Serializable
internal class PersonDto(
    val AuthorName: String? = null,
    val ArtistName: String? = null,
)

@Serializable
internal class ChaptersResponse(
    val data: ChaptersData,
)

@Serializable
internal class ChaptersData(
    val chapters: List<ChapterDto> = emptyList(),
)

@Serializable
internal class ChapterResponse(
    val data: ReaderChapterData,
)

@Serializable
internal class ReaderChapterData(
    val chapter: JsonObject,
)

@Serializable
internal class ChapterDto(
    val ChapterId: String,
    val ChapterObraId: String? = null,
    val ChapterNumber: String? = null,
    val ChapterName: String? = null,
    val ChapterPages: String? = null,
    val CreatedAt: String? = null,
    val ChapterContent: List<PageDto> = emptyList(),
)

@Serializable
internal class PageDto(
    val source: String,
    val pageNumber: JsonElement = JsonPrimitive(0),
)
