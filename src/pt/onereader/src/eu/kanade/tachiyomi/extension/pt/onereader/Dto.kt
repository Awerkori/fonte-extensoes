package eu.kanade.tachiyomi.extension.pt.onereader

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.jsonInstance
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Serializable
internal class PaginationDto(
    val page: Int = 1,
    val totalPages: Int = 1,
)

@Serializable
internal class CatalogDto(
    private val works: List<WorkDto> = emptyList(),
    private val pagination: PaginationDto = PaginationDto(),
) {
    fun toMangasPage() = MangasPage(works.map { it.toSManga() }, pagination.page < pagination.totalPages)
}

@Serializable
internal class UpdatesDto(
    private val items: List<WorkDto> = emptyList(),
    private val pagination: PaginationDto = PaginationDto(),
) {
    fun toMangasPage() = MangasPage(items.map { it.toSManga() }, pagination.page < pagination.totalPages)
}

@Serializable
internal class HomeDto(
    private val popular: List<WorkDto> = emptyList(),
) {
    fun toPopularPage() = MangasPage(popular.map { it.toSManga() }, false)
}

@Serializable
internal class MetaDto(
    val genres: Map<String, List<GenreDto>> = emptyMap(),
)

@Serializable
internal class GenreDto(val name: String)

@Serializable
internal class WorkDetailsDto(
    val work: WorkDto,
    val chapters: List<ChapterDto> = emptyList(),
)

@Serializable
internal class WorkDto(
    val id: String,
    val slug: String? = null,
    val title: String,
    val originalName: String? = null,
    val nativeTitle: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val synopsis: String? = null,
    val status: String? = null,
    val contentType: String? = null,
    val type: String? = null,
    val originCountry: String? = null,
    val releaseYear: Int? = null,
    val genres: List<String> = emptyList(),
    val coverUrl: String? = null,
    @Serializable(with = PublisherSerializer::class)
    val publisher: String? = null,
    val totalChapters: Int? = null,
) {
    fun toSManga(details: Boolean = false): SManga = SManga.create().apply {
        url = id
        title = this@WorkDto.title
        thumbnail_url = coverUrl
        author = this@WorkDto.author?.takeIf(String::isNotBlank)
        artist = this@WorkDto.artist?.takeIf(String::isNotBlank)
        genre = genres.takeIf(List<String>::isNotEmpty)?.joinToString()
        status = this@WorkDto.status.orEmpty().toStatus()
        description = buildString {
            synopsis?.takeIf(String::isNotBlank)?.let(::append)
            val metadata = listOfNotNull(
                originalName?.takeIf { it.isNotBlank() }?.let { "Título original: $it" },
                nativeTitle?.takeIf { it.isNotBlank() }?.let { "Título nativo: $it" },
                (contentType ?: type)?.takeIf { it.isNotBlank() }?.let { "Tipo: $it" },
                originCountry?.takeIf { it.isNotBlank() }?.let { "País: $it" },
                releaseYear?.let { "Ano: $it" },
                publisher?.takeIf { it.isNotBlank() }?.let { "Editora: $it" },
            )
            if (isNotEmpty() && metadata.isNotEmpty()) append("\n\n")
            append(metadata.joinToString("\n"))
        }.ifBlank { null }
        initialized = details
    }
}

internal object PublisherSerializer : JsonTransformingSerializer<String?>(String.serializer().nullable) {
    override fun transformDeserialize(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> element["name"]?.takeIf { it is JsonPrimitive && it.isString } ?: JsonNull
        is JsonPrimitive -> element.takeIf { it is JsonNull || it.isString } ?: JsonNull
        else -> JsonNull
    }
}

@Serializable
internal class HomeMangaDto(
    @SerialName("manga_key")
    private val mangaKey: String,
    @SerialName("display_name")
    private val displayName: String,
    private val status: String,
    @SerialName("cover_image")
    private val coverImage: String,
) {
    fun toSManga(): SManga = SManga.create().apply {
        url = mangaKey
        title = displayName
        thumbnail_url = coverImage
        status = this@HomeMangaDto.status.toStatus()
    }
}

@Serializable
internal class MangaDto(
    @SerialName("manga_key")
    private val mangaKey: String,
    @SerialName("display_name")
    private val displayName: String,
    private val name: String,
    @SerialName("japanese_name")
    private val japaneseName: String?,
    private val author: String? = null,
    private val artist: String? = null,
    private val synopsis: String?,
    private val status: String,
    private val type: String,
    @SerialName("origin_country")
    private val originCountry: String,
    private val year: Int,
    @Serializable(with = TagsSerializer::class)
    private val tags: List<String>,
    @SerialName("cover_image")
    private val coverImage: String,
    @SerialName("publisher_name")
    private val publisherName: String? = null,
) {
    fun toSManga(details: Boolean = false): SManga = SManga.create().apply {
        url = mangaKey
        title = displayName
        thumbnail_url = coverImage
        author = this@MangaDto.author?.takeIf(String::isNotBlank)
        artist = this@MangaDto.artist?.takeIf(String::isNotBlank)
        genre = tags.takeIf(List<String>::isNotEmpty)?.joinToString()
        status = this@MangaDto.status.toStatus()
        description = buildDescription()
        initialized = details
    }

    private fun buildDescription(): String? {
        val metadata = buildList {
            name.takeIf { it.isNotBlank() && !it.equals(displayName, ignoreCase = true) }
                ?.let { add("Título original: $it") }
            japaneseName?.takeIf {
                it.isNotBlank() &&
                    !it.equals(displayName, ignoreCase = true) &&
                    !it.equals(name, ignoreCase = true)
            }?.let { add("Título nativo: $it") }
            type.takeIf(String::isNotBlank)?.let { add("Tipo: $it") }
            originCountry.takeIf(String::isNotBlank)?.let { add("País: $it") }
            add("Ano: $year")
            publisherName?.takeIf(String::isNotBlank)?.let { add("Editora: $it") }
        }

        return buildString {
            synopsis?.takeIf(String::isNotBlank)?.let(::append)
            if (isNotEmpty() && metadata.isNotEmpty()) append("\n\n")
            append(metadata.joinToString("\n"))
        }.ifBlank { null }
    }
}

@Serializable
internal class ChapterDto(
    val id: String = "",
    val number: Double,
    val title: String? = null,
    val postedAt: String? = null,
) {
    fun toSChapter(mangaKey: String): SChapter = SChapter.create().apply {
        val numberString = number.toString().removeSuffix(".0")
        url = "$mangaKey/$numberString"
        memo = buildJsonObject {
            put("id", mangaKey)
            put("number", numberString)
        }
        name = title?.takeIf(String::isNotBlank) ?: "Capítulo $numberString"
        chapter_number = number.toFloat()
        date_upload = postedAt?.let(chapterDateFormat::tryParse) ?: 0L
    }
}

@Serializable
internal class PagesDto(
    private val chapter: ChapterPagesDto? = null,
) {
    fun toPages(apiBaseUrl: HttpUrl): List<Page> = (chapter?.pages ?: emptyList()).mapIndexed { index, path ->
        Page(
            index = index,
            imageUrl = requireNotNull(apiBaseUrl.resolve(path)).toString(),
        )
    }
}

@Serializable
internal class ChapterPagesDto(val pages: List<String> = emptyList())

@Serializable
internal class MediaGrantDto(
    val ok: Boolean = false,
    val url: String = "",
    val key: String = "",
    val mode: String = "",
    val contentType: String = "image/webp",
    val keyWrap: MediaKeyWrapDto? = null,
)

@Serializable
internal class MediaKeyWrapDto(
    val mode: String = "",
    val serverKey: String = "",
    val iv: String = "",
    val payload: String = "",
    val context: String = "",
)

private object TagsSerializer : JsonTransformingSerializer<List<String>>(
    ListSerializer(String.serializer()),
) {
    override fun transformDeserialize(element: JsonElement): JsonElement = when (element) {
        JsonNull -> JsonArray(emptyList())
        is JsonPrimitive ->
            element.contentOrNull
                ?.let(jsonInstance::parseToJsonElement)
                ?: element
        else -> element
    }
}

private fun String.toStatus(): Int = when (trim().lowercase(Locale.ROOT)) {
    "em lançamento", "lançando" -> SManga.ONGOING
    "completo" -> SManga.COMPLETED
    "hiatus", "hiato" -> SManga.ON_HIATUS
    "cancelado" -> SManga.CANCELLED
    else -> SManga.UNKNOWN
}

private val chapterDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}
