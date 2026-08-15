package eu.kanade.tachiyomi.extension.pt.mangasbrasuka

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import keiyoushi.utils.tryParseDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.format.DateTimeFormatter
import java.util.Locale

@Serializable
class ComicSeriesDto(
    @SerialName("@type")
    val type: String? = null,
    val name: String? = null,
    val description: String? = null,
    val author: PersonDto? = null,
    val genre: List<String>? = null,
    val image: String? = null,
)

@Serializable
class PersonDto(
    val name: String? = null,
)

@Serializable
class GateTokenRequest(
    private val returnTo: String,
)

@Serializable
class GateTokenResponseDto(
    val data: GateTokenDataDto,
)

@Serializable
class GateTokenDataDto(
    val token: String,
    val gateUrl: String? = null,
    val minWaitSeconds: Long? = null,
)

@Serializable
class PageListDto(
    val pages: List<PageDto>,
)

@Serializable
class PageDto(
    val url: String,
)

@Serializable
class CatalogoDto(
    val series: List<SeriesItemDto>,
)

@Serializable
class SeriesItemDto(
    private val slug: String,
    private val title: String,
    private val coverUrl: String,
    private val type: String? = null,
) {
    fun toSManga(): SManga = SManga.create().apply {
        val t = type?.lowercase() ?: "manga"
        url = "/$t/$slug"
        title = this@SeriesItemDto.title
        thumbnail_url = coverUrl
    }
}

@Serializable
class UpdatesDto(
    val updates: List<UpdateItemDto>,
)

@Serializable
class UpdateItemDto(
    private val slug: String,
    private val title: String,
    private val coverUrl: String,
    private val type: String? = null,
) {
    fun toSManga(): SManga = SManga.create().apply {
        val t = type?.lowercase() ?: "manga"
        url = "/$t/$slug"
        title = this@UpdateItemDto.title
        thumbnail_url = coverUrl
    }
}

@Serializable
class ChapterDataDto(
    val chapters: List<ChapterItemDto>,
)

@Serializable
class ChapterItemDto(
    private val number: String,
    private val title: String? = null,
    private val releaseDate: String? = null,
) {
    fun toSChapter(mangaUrl: String): SChapter = SChapter.create().apply {
        url = "$mangaUrl/$number"
        name = if (!title.isNullOrBlank() && title != "\$undefined" && title != "${number}_pages") {
            "Capítulo $number - $title"
        } else {
            "Capítulo $number"
        }
        chapter_number = number.toFloatOrNull() ?: -1f
        date_upload = parseDate(releaseDate)
    }
}

private val DATE_FORMATTER by lazy {
    DateTimeFormatter.ofPattern(
        "[d 'de' MMM. 'de' yyyy][d 'de' MMM 'de' yyyy][d 'de' MMMM 'de' yyyy][d 'de' MMMM][d 'de' MMM.]",
        Locale.forLanguageTag("pt-BR"),
    )
}

internal fun parseDate(dateStr: String?): Long {
    val trimmed = dateStr?.trim() ?: return 0L
    return when {
        trimmed.contains("T") && trimmed.endsWith("Z") -> {
            kotlin.time.Instant.tryParse(trimmed)
        }
        trimmed.equals("hoje", ignoreCase = true) -> {
            System.currentTimeMillis()
        }
        trimmed.equals("ontem", ignoreCase = true) -> {
            System.currentTimeMillis() - 86_400_000L
        }
        else -> {
            DATE_FORMATTER.tryParseDate(trimmed)
        }
    }
}
