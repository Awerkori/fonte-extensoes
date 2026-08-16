package eu.kanade.tachiyomi.extension.pt.cerisescans

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

internal fun getFilters(): FilterList = FilterList(
    SortFilter(),
    GenreFilter(),
    StatusFilter(),
    ScanFilter(),
)

internal class SortFilter :
    UriSelectFilter(
        "Ordenar por",
        listOf(
            "Mais vistas" to "views",
            "Recentes" to "recent",
        ),
    )

internal class GenreFilter :
    UriSelectFilter(
        "Genero",
        listOf(
            "Todos" to null,
            "Ação" to "Ação",
            "Adulto" to "Adulto",
            "Apocalíptico" to "Apocalíptico",
            "Artes Marciais" to "Artes Marciais",
            "Aventura" to "Aventura",
            "BL" to "BL",
            "Comédia" to "Comédia",
            "Drama" to "Drama",
            "Escolar" to "Escolar",
            "Fantasia" to "Fantasia",
            "Ficção Científica" to "Ficção Científica",
            "Gore" to "Gore",
            "Harem" to "Harem",
            "Harém" to "Harém",
            "Histórico" to "Histórico",
            "Horror" to "Horror",
            "Isekai" to "Isekai",
            "Josei" to "Josei",
            "Magia" to "Magia",
            "Mistério" to "Mistério",
            "Psicológico" to "Psicológico",
            "Reencarnação" to "Reencarnação",
            "Regressão" to "Regressão",
            "Romance" to "Romance",
            "Sci-fi" to "Sci-fi",
            "Seinen" to "Seinen",
            "Shoujo" to "Shoujo",
            "Shounen" to "Shounen",
            "Slice of Life" to "Slice of Life",
            "Smut" to "Smut",
            "Sobrenatural" to "Sobrenatural",
            "Suspense" to "Suspense",
            "Tragédia" to "Tragédia",
            "Vida Escolar" to "Vida Escolar",
            "Vingança" to "Vingança",
            "Webtoon" to "Webtoon",
            "Yaoi" to "Yaoi",
            "Yuri" to "Yuri",
        ),
    )

internal class StatusFilter :
    UriSelectFilter(
        "Status",
        listOf(
            "Todos" to null,
            "Em andamento" to "ongoing",
            "Completo" to "completed",
            "Hiato" to "hiatus",
            "Cancelado" to "cancelled",
            "Dropado" to "dropped",
            "Inativo" to "inactive",
        ),
    )

internal class ScanFilter :
    UriSelectFilter(
        "Scan",
        listOf(
            "Todas" to null,
            "AllyScan" to "1780396433802",
            "Kissu Scan" to "1780309996685",
            "O-Inari Sama Scan" to "1780310023999",
            "Sweet Strawberry Scan" to "1780307854644",
            "Yoru Scanlator" to "1780308169790",
            "Yukai Scan" to "1780308927112",
        ),
    )

internal open class UriSelectFilter(
    name: String,
    private val options: List<Pair<String, String?>>,
) : Filter.Select<String>(
    name,
    options.map { it.first }.toTypedArray(),
) {
    val selected: String?
        get() = options[state].second
}
