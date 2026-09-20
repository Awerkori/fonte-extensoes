package eu.kanade.tachiyomi.extension.pt.karikari

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
internal data class MangaDto(
    val id: String = "",
    val slug: String = "",
    val titulo: String = "",
    @SerialName("capa_vertical_path") val coverUrl: String? = null,
    val descricao: String? = null,
    val status: String? = null,
    @SerialName("conteudo_adulto") val adult: Boolean = false,
    @SerialName("classificacao_etaria") val ageRating: String? = null,
    val publico: Boolean = false,
    val ocultada: Boolean = false,
    val autoral: Boolean? = null,
    @SerialName("criador_id") val creatorId: String? = null,
    @SerialName("genero_principal_id") val primaryGenreId: String? = null,
    @SerialName("genero_secundario_id") val secondaryGenreId: String? = null,
)

@Serializable
internal data class GenreOption(
    val id: String = "",
    val nome: String = "",
)

@Serializable
internal data class AuthorDto(
    val nome: String? = null,
)

@Serializable
internal data class ChapterDto(
    val id: String = "",
    val titulo: String = "",
    val slug: String = "",
    val ordem: Int? = null,
    val status: String? = null,
    val publico: Boolean = false,
    val ocultada: Boolean = false,
    val excluido: Boolean = false,
    @SerialName("nao_listado") val unlisted: Boolean = false,
    val visibilidade: String? = null,
    @SerialName("publicacao_agendada") val scheduled: Boolean = false,
    @SerialName("apenas_membros") val membersOnly: Boolean = false,
    @SerialName("exclusivo_apoia_campanha_id") val campaignId: String? = null,
    @SerialName("data_de_lancamento") val releaseDate: String? = null,
    @SerialName("publicado_em") val publishedAt: String? = null,
)

@Serializable
internal data class RecentChapterDto(
    @SerialName("obra_id") val workId: String = "",
)

@Serializable
internal data class RatingDto(
    @SerialName("obra_id") val workId: String = "",
    @SerialName("media_avaliacao") val average: Double = 0.0,
    @SerialName("total_avaliacoes") val total: Int = 0,
)

@Serializable
internal data class AdultWorkDto(
    @SerialName("conteudo_adulto") val adult: Boolean = false,
)

@Serializable
internal data class ChapterAccessDto(
    val status: String? = null,
    val publico: Boolean = false,
    val ocultada: Boolean = false,
    val excluido: Boolean = false,
    val visibilidade: String? = null,
    @SerialName("publicacao_agendada") val scheduled: Boolean = false,
    @SerialName("apenas_membros") val membersOnly: Boolean = false,
    @SerialName("exclusivo_apoia_campanha_id") val campaignId: String? = null,
    val obras: AdultWorkDto? = null,
)

@Serializable
internal data class PageDto(
    val ordem: Int = 0,
    @SerialName("imagem_path") val imageUrl: String = "",
)

@Serializable
internal data class ChapterPagesDto(
    @SerialName("capitulos_paginas") val pages: List<PageDto> = emptyList(),
)

@Serializable
internal data class ChapterPageIdsDto(
    @SerialName("capitulos_paginas") val pages: List<PageIdDto> = emptyList(),
)

@Serializable
internal data class PageIdDto(
    val id: String = "",
)

@Serializable
internal data class FilterData(
    val genres: List<GenreOption> = emptyList(),
)

internal fun MangaDto.toSManga(genreNames: List<String> = emptyList(), authorName: String? = null): SManga {
    val mangaStatus = status.toMihonStatus()
    return SManga.create().apply {
        url = "/obra/$slug"
        title = titulo
        thumbnail_url = coverUrl
        author = authorName
        genre = genreNames.joinToString()
        description = buildString {
            descricao?.takeIf(String::isNotBlank)?.let(::append)
            ageRating?.takeIf(String::isNotBlank)?.let {
                if (isNotEmpty()) append("\n\n")
                append("Classificação: ").append(it)
            }
        }.takeIf(String::isNotBlank)
        status = mangaStatus
        initialized = true
    }
}

internal fun String?.toMihonStatus(): Int = when (this) {
    "Em produção" -> SManga.ONGOING
    "Finalizado" -> SManga.COMPLETED
    "Hiato" -> SManga.ON_HIATUS
    "Cancelado" -> SManga.CANCELLED
    else -> SManga.UNKNOWN
}

internal fun ChapterDto.toSChapter(): SChapter = SChapter.create().apply {
    url = "/ler/$slug"
    name = titulo
    chapter_number = parseChapterNumber(titulo, ordem)
    date_upload = Instant.tryParse(publishedAt ?: releaseDate)
    scanlator = "KariKari"
}

internal fun parseChapterNumber(title: String, order: Int?): Float = Regex("(?<!\\d)\\d+(?:[.,]\\d+)?")
    .find(title)
    ?.value
    ?.replace(',', '.')
    ?.toFloatOrNull()
    ?: order?.toFloat()
    ?: -1f

internal fun ChapterAccessDto.isAccessible(allowAdult: Boolean = false): Boolean = publico &&
    !ocultada &&
    !excluido &&
    status == "Aprovado" &&
    visibilidade == "Disponível para todos" &&
    !scheduled &&
    !membersOnly &&
    campaignId == null &&
    (allowAdult || obras?.adult != true)

internal fun List<PageDto>.orderedImageUrls(): List<String> = asSequence()
    .sortedBy(PageDto::ordem)
    .map(PageDto::imageUrl)
    .filter(String::isNotBlank)
    .distinct()
    .toList()

internal fun List<RecentChapterDto>.distinctWorkIds(): List<String> = asSequence().map(RecentChapterDto::workId).filter(String::isNotBlank).distinct().toList()
