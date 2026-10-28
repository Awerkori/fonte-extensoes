package eu.kanade.tachiyomi.extension.pt.lermangaonline

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

object LerMangaOnlineFilters {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    fun get(data: JsonElement?): FilterList {
        val genres = parseGenres(data)
        return FilterList(
            TypeFilter(),
            GenreFilter(genres),
        )
    }

    private fun parseGenres(data: JsonElement?): List<Pair<String, String>> {
        if (data != null) {
            try {
                val list = json.decodeFromJsonElement<List<LerMangaOnlineGenreDto>>(data)
                val parsed = list.mapNotNull {
                    val name = it.name.trim().ifEmpty { null }
                    val slug = it.slug.trim().ifEmpty { null }
                    if (name != null && slug != null) Pair(name, slug) else null
                }
                if (parsed.isNotEmpty()) {
                    return listOf(Pair("Todos os gêneros", "ALL")) + parsed
                }
            } catch (_: Exception) {}
        }
        return STATIC_GENRES
    }

    val STATIC_GENRES = listOf(
        Pair("Todos os gêneros", "ALL"),
        Pair("Romance", "romance"),
        Pair("Comédia", "comedia"),
        Pair("Comedy", "comedy"),
        Pair("Isekai", "isekai"),
        Pair("Fantasia", "fantasia"),
        Pair("Fantasy", "fantasy"),
        Pair("Drama", "drama"),
        Pair("Aventura", "aventura"),
        Pair("Adventure", "adventure"),
        Pair("Ação", "acao"),
        Pair("Action", "action"),
        Pair("Slice of Life", "slice-of-life"),
        Pair("Harem", "harem"),
        Pair("Magia", "magia"),
        Pair("Escolar", "school-life"),
        Pair("Shounen", "shounen"),
        Pair("Culinária", "cooking"),
        Pair("Adaptação", "adaptation"),
        Pair("Seinen", "seinen"),
        Pair("Shoujo", "shoujo"),
        Pair("Mistério", "misterio"),
        Pair("Sobrenatural", "sobrenatural"),
        Pair("Psicológico", "psicologico"),
        Pair("Ficção Científica", "ficcao-cientifica"),
        Pair("Artes Marciais", "artes-marciais"),
        Pair("Histórico", "historico"),
        Pair("Horror", "horror"),
        Pair("Mecha", "mecha"),
        Pair("Esportes", "esportes"),
        Pair("Vida Escolar", "vida-escolar"),
        Pair("Superpoderes", "superpoderes"),
        Pair("Webtoon", "webtoon"),
    )
}

open class SelectFilter(
    name: String,
    private val options: List<Pair<String, String>>,
    defaultIndex: Int = 0,
) : Filter.Select<String>(name, options.map { it.first }.toTypedArray(), defaultIndex) {
    val selected: String
        get() = options.getOrNull(state)?.second ?: ""
}

class TypeFilter :
    SelectFilter(
        "Tipo de obra",
        listOf(
            Pair("Todos os tipos", "ALL"),
            Pair("Mangá", "manga"),
            Pair("Manhwa", "manhwa"),
        ),
        0,
    )

class GenreFilter(options: List<Pair<String, String>>) :
    SelectFilter(
        "Gênero",
        options,
        0,
    )
