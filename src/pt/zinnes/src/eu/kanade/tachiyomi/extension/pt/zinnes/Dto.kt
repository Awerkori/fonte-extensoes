package eu.kanade.tachiyomi.extension.pt.zinnes

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Serializable
internal class SectionResponseDto(val data: SectionDataDto)

@Serializable
internal class SectionDataDto(
    val dominaram: List<ProjectDto>? = null,
    @SerialName("latest_releases") val latestReleases: List<ProjectDto>? = null,
)

@Serializable
internal class SearchResponseDto(val data: SearchPageDto)

@Serializable
internal class SearchPageDto(
    val data: List<ProjectDto>,
    @SerialName("current_page") val currentPage: Int,
    @SerialName("last_page") val lastPage: Int,
)

@Serializable
internal class DetailsResponseDto(val data: DetailsDataDto)

@Serializable
internal class DetailsDataDto(
    val project: ProjectDto,
    val titles: List<TitleDto> = emptyList(),
)

@Serializable
internal class TitleViewResponseDto(val data: TitleViewDto)

@Serializable
internal class TitleViewDto(@SerialName("pages_data") val pages: List<PageDto> = emptyList())

@Serializable
internal class PageDto(
    val page: Int,
    val image: String,
    val active: Int = 1,
)

@Serializable
internal class FilterOptionDto(
    val id: Int,
    val name: String,
)

@Serializable
internal class FilterData(
    val genres: List<FilterOptionDto>,
    val languages: List<FilterOptionDto>,
    val types: List<FilterOptionDto>,
)

@Serializable
internal class ProjectDto(
    val id: Int,
    val name: String,
    val slug: String? = null,
    val description: String? = null,
    @SerialName("project_thumb") val thumbnail: String? = null,
    val author: AuthorDto? = null,
    val gender: FilterOptionDto? = null,
    val completed: Int? = null,
    @SerialName("project_status") val projectStatus: Int? = null,
    val ageRating: AgeRatingDto? = null,
) {
    fun toSManga() = SManga.create().apply {
        url = id.toString()
        title = name.trim()
        thumbnail_url = thumbnail
        description = description
        author = this@ProjectDto.author?.name
        genre = gender?.name
        status = when {
            completed == 1 -> SManga.COMPLETED
            projectStatus == 1 -> SManga.CANCELLED
            else -> SManga.ONGOING
        }
    }
}

@Serializable
internal class AuthorDto(val name: String? = null, val nickname: String? = null)

@Serializable
internal class AgeRatingDto(val age: Int? = null, val rating: String? = null)

@Serializable
internal class TitleDto(
    val id: Int,
    val name: String,
    @SerialName("release_date") val releaseDate: String? = null,
) {
    val chapterNumber: Float
        get() = numberPattern.find(name)?.groupValues?.get(1)?.replace(',', '.')?.toFloatOrNull() ?: -1F

    fun toSChapter() = SChapter.create().apply {
        url = id.toString()
        name = this@TitleDto.name.trim()
        chapter_number = chapterNumber
        date_upload = releaseDate?.let(::parseDate) ?: 0L
        memo = buildJsonObject { put("id", id) }
    }
}

private val numberPattern = Regex("(?:#|cap(?:ítulo|itulo)?|volume|vol\\.?|parte|pt\\.?)\\s*(\\d+(?:[.,]\\d+)?)", RegexOption.IGNORE_CASE)

private fun parseDate(value: String): Long? = runCatching {
    SimpleDateFormat("dd/MM/yyyy", Locale.ROOT).parse(value)?.time
        ?: SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.parse(value)?.time
}.getOrNull()
