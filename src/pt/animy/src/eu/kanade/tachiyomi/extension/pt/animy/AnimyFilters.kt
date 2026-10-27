package eu.kanade.tachiyomi.extension.pt.animy

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import keiyoushi.utils.firstInstanceOrNull

open class ChoiceFilter(
    name: String,
    private val entries: Array<Pair<String, String>>,
    defaultIndex: Int = 0,
) : Filter.Select<String>(
    name,
    entries.map { it.second }.toTypedArray(),
    defaultIndex,
) {
    fun getValue(): String = entries[state].first
}

class SortFilter :
    ChoiceFilter(
        "Ordenar por",
        arrayOf(
            "popular" to "Mais Populares",
            "newest" to "Mais Recentes",
            "followed" to "Mais Seguidos / Em Alta",
            "rating" to "Mais Bem Avaliados",
        ),
    )

class TypeFilter :
    ChoiceFilter(
        "Tipo",
        arrayOf(
            "" to "Todos",
            "manga" to "Mangá",
            "manhwa" to "Manhwa",
            "manhua" to "Manhua",
        ),
    )

class StatusFilter :
    ChoiceFilter(
        "Status",
        arrayOf(
            "" to "Todos",
            "ongoing" to "Em Lançamento",
            "completed" to "Completo",
            "hiatus" to "Em Hiato",
            "cancelled" to "Cancelado",
        ),
    )

class DemographicFilter :
    ChoiceFilter(
        "Demografia",
        arrayOf(
            "" to "Todos",
            "shounen" to "Shounen",
            "seinen" to "Seinen",
            "shoujo" to "Shoujo",
            "josei" to "Josei",
        ),
    )

class ContentRatingFilter :
    ChoiceFilter(
        "Classificação",
        arrayOf(
            "" to "Todos",
            "safe" to "Seguro",
            "suggestive" to "Sugestivo",
            "erotica" to "Erótico",
            "pornographic" to "Pornográfico",
        ),
    )

class GenreCheckBox(
    name: String,
    val value: String = name,
) : Filter.CheckBox(name)

class GenreFilter(genres: List<String>) :
    Filter.Group<GenreCheckBox>(
        "Gêneros",
        genres.map { GenreCheckBox(it) },
    )

inline fun <reified T : ChoiceFilter> FilterList.valueOrEmpty(): String = firstInstanceOrNull<T>()?.getValue().orEmpty()

fun FilterList.selectedGenres(): List<String> = firstInstanceOrNull<GenreFilter>()?.state
    ?.filter { it.state }
    ?.map { it.value }
    .orEmpty()

val GENRES_LIST = listOf(
    "Ação",
    "Adulto",
    "Artes Marciais",
    "Aventura",
    "Comédia",
    "Cotidiano",
    "Cultivo",
    "Drama",
    "Ecchi",
    "Escolar",
    "Fantasia",
    "Ficção Científica",
    "Harém",
    "Histórico",
    "Horror",
    "Isekai",
    "Magia",
    "Mecha",
    "Mistério",
    "Monstros",
    "Psicológico",
    "Reencarnação",
    "Romance",
    "Sci-fi",
    "Seinen",
    "Shounen",
    "Shoujo",
    "Slice of Life",
    "Sobrenatural",
    "Terror",
    "Tragédia",
    "Transmigração",
    "Xianxia",
    "Yuri",
)

object AnimyFilters {
    fun get(): FilterList = FilterList(
        SortFilter(),
        TypeFilter(),
        StatusFilter(),
        DemographicFilter(),
        ContentRatingFilter(),
        GenreFilter(GENRES_LIST),
    )
}
