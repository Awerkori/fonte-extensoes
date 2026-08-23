package eu.kanade.tachiyomi.extension.pt.tomato

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

@Serializable
class FeedResponseDto(
    val data: List<FeedSectionDto>,
)

@Serializable
class FeedSectionDto(
    val title: String,
    val type: Int,
    val data: List<FeedMangaDto>,
    val meta: FeedMetaDto? = null,
)

@Serializable
class FeedMetaDto(
    val type: String? = null,
)

@Serializable
class FeedMangaDto(
    val id: Long,
    val name: String,
    val thumbnail: String? = null,
)

@Serializable
class SearchResponseDto(
    val result: List<SearchMangaDto> = emptyList(),
)

@Serializable
class SearchMangaDto(
    val id: Long,
    val type: String,
    val name: String,
    val author: String? = null,
    val image: String? = null,
)

@Serializable
class SearchRequestDto(
    val search: String,
    @SerialName("content_type")
    val contentType: String = "manga",
    val page: Int,
    val tags: List<String>? = null,
    val token: String,
)

@Serializable
class CategoriesResponseDto(
    val categories: List<CategoryDto> = emptyList(),
)

@Serializable
class CategoryDto(
    val name: String,
    private val cape: String? = null,
    private val color: String? = null,
)

@Serializable
class MangaQueryRequestDto(
    val id: Long,
    val token: String,
)

@Serializable
class MangaQueryResponseDto(
    val details: MangaQueryDetailsDto,
)

@Serializable
class MangaQueryDetailsDto(
    val source: Int,
    val id: Long,
    val name: String,
    @Serializable(with = StringOrNumberSerializer::class)
    val url: String,
    @SerialName("source_url")
    val sourceUrl: String,
    val cover: String? = null,
    val author: String? = null,
)

@Serializable
class MangaMetadataResponseDto(
    val details: MangaMetadataDto,
)

@Serializable
class MangaMetadataDto(
    val description: String? = null,
    val genre: String? = null,
    private val favorited: Boolean = false,
    private val notify: Boolean = false,
    @SerialName("notify_capable")
    private val notifyCapable: Boolean = false,
    private val nsfw: Boolean = false,
    @SerialName("comments_count")
    private val commentsCount: Int = 0,
)

@Serializable
class ChaptersResponseDto(
    val data: List<ChapterDto> = emptyList(),
)

@Serializable
class ChapterDto(
    val id: Long,
    val name: String,
    val number: Float,
    @SerialName("source_url")
    val sourceUrl: String,
    val source: Int,
)

@Serializable
class PagesResponseDto(
    val data: List<PageDto> = emptyList(),
)

@Serializable
class PageDto(
    @SerialName("page_url")
    val pageUrl: String,
)

@Serializable
class MangaDexFeedResponseDto(
    val data: List<MangaDexChapterDto> = emptyList(),
    val total: Int = 0,
)

@Serializable
class MangaDexChapterDto(
    val id: String,
    val attributes: MangaDexChapterAttributesDto,
    val relationships: List<MangaDexRelationshipDto> = emptyList(),
)

@Serializable
class MangaDexChapterAttributesDto(
    val chapter: String? = null,
    val title: String? = null,
    val volume: String? = null,
    val publishAt: String? = null,
    val readableAt: String? = null,
)

@Serializable
class MangaDexRelationshipDto(
    val type: String,
    val attributes: MangaDexGroupAttributesDto? = null,
)

@Serializable
class MangaDexGroupAttributesDto(
    val name: String? = null,
)

@Serializable
class MangaDexAtHomeDto(
    val baseUrl: String,
    val chapter: MangaDexAtHomeChapterDto,
)

@Serializable
class MangaDexAtHomeChapterDto(
    val hash: String,
    val data: List<String> = emptyList(),
)

@Serializable
class MangaDexMangaResponseDto(
    val data: MangaDexMangaDto,
)

@Serializable
class MangaDexMangaDto(
    val id: String,
    val attributes: MangaDexMangaAttributesDto,
    val relationships: List<MangaDexMangaRelationshipDto> = emptyList(),
)

@Serializable
class MangaDexMangaAttributesDto(
    val title: Map<String, String> = emptyMap(),
    val description: Map<String, String> = emptyMap(),
    val status: String? = null,
    val tags: List<MangaDexTagDto> = emptyList(),
)

@Serializable
class MangaDexTagDto(
    val attributes: MangaDexTagAttributesDto,
)

@Serializable
class MangaDexTagAttributesDto(
    val name: Map<String, String> = emptyMap(),
)

@Serializable
class MangaDexMangaRelationshipDto(
    val type: String,
    val attributes: MangaDexMangaRelationshipAttributesDto? = null,
)

@Serializable
class MangaDexMangaRelationshipAttributesDto(
    val name: String? = null,
    val fileName: String? = null,
)

@Serializable
class MangaLivreChaptersDto(
    val chapters: List<MangaLivreChapterDto> = emptyList(),
)

@Serializable
class MangaLivreChapterDto(
    @SerialName("id_chapter")
    val id: Long,
    val name: String,
    val number: String,
    val releases: Map<String, JsonElement> = emptyMap(),
)

@Serializable
class MangaLivreReleaseDto(
    val link: String,
)

private object StringOrNumberSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("StringOrNumber", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        if (decoder !is JsonDecoder) return decoder.decodeString()
        val value = decoder.decodeJsonElement().jsonPrimitive
        if (value.isString || value.doubleOrNull != null) return value.content
        throw SerializationException("Expected string or number")
    }

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}
