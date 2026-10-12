package eu.kanade.tachiyomi.extension.pt.supermanga

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Locale

@Serializable
internal data class CatalogResponseDto(
    val items: List<CatalogMangaDto> = emptyList(),
    val total: Int = 0,
)

@Serializable
internal data class CatalogMangaDto(
    val id: Long,
    val t: String,
    val y: Int? = null,
    val g: List<String> = emptyList(),
    val age: Int? = null,
    val cover: String? = null,
    val desc: String? = null,
    val status: String? = null,
    val au: String? = null,
    val language: String? = null,
    val type: String? = null,
    val contentRating: String? = null,
) {
    fun toSManga(baseUrl: String): SManga = SManga.create().apply {
        val slug = slugify(t)
        url = "/manga/$slug/?id=$id"
        title = t
        thumbnail_url = cover?.let { if (it.startsWith("/")) "$baseUrl$it" else it }
    }
}

@Serializable
internal data class MangaDetailsDto(
    val id: Long,
    val t: String,
    val y: Int? = null,
    val g: List<String> = emptyList(),
    val age: Int? = null,
    val cover: String? = null,
    val desc: String? = null,
    val status: String? = null,
    val au: String? = null,
    val language: String? = null,
    val type: String? = null,
    val contentRating: String? = null,
    val altTitles: List<AltTitleDto> = emptyList(),
    val chapters: List<ChapterDto> = emptyList(),
) {
    fun toSManga(baseUrl: String): SManga = SManga.create().apply {
        val slug = slugify(t)
        url = "/manga/$slug/?id=$id"
        title = t
        thumbnail_url = cover?.let { if (it.startsWith("/")) "$baseUrl$it" else it }
        author = au?.trim()
        artist = au?.trim()
        status = when (this@MangaDetailsDto.status?.lowercase(Locale.ROOT)) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "cancelled" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
        genre = g.filter(String::isNotBlank).joinToString(", ")
        description = buildString {
            desc?.takeIf(String::isNotBlank)?.let {
                append(it.trim())
            }
            val alts = altTitles.mapNotNull { it.title?.trim() }
                .filter { it.isNotBlank() && !it.equals(t, ignoreCase = true) }
                .distinct()
            if (alts.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("Títulos alternativos:\n")
                alts.forEach { append("• ").append(it).append("\n") }
            }
        }.trim()
        initialized = true
    }
}

@Serializable
internal data class AltTitleDto(
    val lang: String? = null,
    val title: String? = null,
)

@Serializable
internal data class ChapterDto(
    val id: Long = 0L,
    @Serializable(with = DoubleSerializer::class) val number: Double = 0.0,
    val title: String? = null,
    val date: String? = null,
    val language: String? = null,
    val views: Int? = null,
) {
    fun toSChapter(slug: String, mangaId: Long): SChapter = SChapter.create().apply {
        val numberStr = formatChapterNumber(number)
        url = "/manga/$slug/capitulo/$numberStr/?mangaId=$mangaId"
        name = formatChapterName(number, title)
        chapter_number = number.toFloat()
        date_upload = date?.let { parseDate(it) } ?: 0L
    }
}

@Serializable
internal data class ChapterPagesDto(
    val id: Long? = null,
    @Serializable(with = DoubleSerializer::class) val number: Double? = null,
    val title: String? = null,
    val pages: List<PageDto> = emptyList(),
)

@Serializable
internal data class PageDto(
    val number: Int = 0,
    val imageUrl: String = "",
)

@Serializable
internal data class GenresResponseDto(
    val genres: List<String> = emptyList(),
)

@Serializable
internal data class FilterData(
    val genres: List<String> = emptyList(),
)

internal object DoubleSerializer : KSerializer<Double> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Double", PrimitiveKind.DOUBLE)
    override fun serialize(encoder: Encoder, value: Double) = encoder.encodeDouble(value)
    override fun deserialize(decoder: Decoder): Double {
        val jsonInput = decoder as? JsonDecoder ?: return decoder.decodeDouble()
        val element = jsonInput.decodeJsonElement()
        return when {
            element is JsonPrimitive && element.isString -> element.content.toDoubleOrNull() ?: 0.0
            element is JsonPrimitive -> element.doubleOrNull ?: element.content.toDoubleOrNull() ?: 0.0
            else -> 0.0
        }
    }
}

internal fun slugify(text: String): String {
    val normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
    return normalized
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
}

internal fun formatChapterNumber(number: Double): String = if (number % 1.0 == 0.0) number.toLong().toString() else number.toString()

internal fun formatChapterName(number: Double, title: String?): String {
    val numberStr = formatChapterNumber(number)
    val cleanTitle = title?.trim()?.trim('-', '—', '–', ' ', '・')?.trim()
    return if (!cleanTitle.isNullOrBlank()) {
        if (cleanTitle.startsWith("Capítulo", ignoreCase = true) || cleanTitle.startsWith("Cap.", ignoreCase = true)) {
            cleanTitle
        } else {
            "Capítulo $numberStr — $cleanTitle"
        }
    } else {
        "Capítulo $numberStr"
    }
}

private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)

internal fun parseDate(dateStr: String): Long = runCatching {
    dateFormat.parse(dateStr)?.time ?: 0L
}.getOrDefault(0L)
