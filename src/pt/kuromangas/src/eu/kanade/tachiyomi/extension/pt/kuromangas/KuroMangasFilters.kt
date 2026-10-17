package eu.kanade.tachiyomi.extension.pt.kuromangas

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

fun getFilters() = FilterList(
    SortFilter("Ordenar por", getSortOptions()),
    StatusFilter("Status", getStatusOptions()),
    TypeFilter("Tipo", getTypeOptions()),
)

class SortFilter(displayName: String, private val sortOptions: List<Pair<String, String>>) : Filter.Select<String>(displayName, sortOptions.map { it.first }.toTypedArray()) {
    val selectedSort: String
        get() = sortOptions.getOrNull(state)?.second ?: "view_count"
    val selectedOrder: String
        get() = when (state) {
            3, 4 -> "ASC"
            else -> "DESC"
        }
}

class StatusFilter(displayName: String, private val options: List<Pair<String, String>>) : Filter.Select<String>(displayName, options.map { it.first }.toTypedArray()) {
    val selected: String
        get() = options.getOrNull(state)?.second.orEmpty()
}

class TypeFilter(displayName: String, private val options: List<Pair<String, String>>) : Filter.Select<String>(displayName, options.map { it.first }.toTypedArray()) {
    val selected: String
        get() = options.getOrNull(state)?.second.orEmpty()
}

private fun getSortOptions() = listOf(
    Pair("Mais Populares", "view_count"),
    Pair("Melhor Avaliados", "average_rating"),
    Pair("Mais Recentes", "created_at"),
    Pair("Mais Antigos", "created_at"),
    Pair("Título (A-Z)", "title"),
    Pair("Título (Z-A)", "title"),
)

private fun getStatusOptions() = listOf(
    Pair("Todos", ""),
    Pair("Em andamento", "ongoing"),
    Pair("Completo", "completed"),
    Pair("Em hiato", "hiatus"),
    Pair("Cancelado", "cancelled"),
)

private fun getTypeOptions() = listOf(
    Pair("Todos", ""),
    Pair("Mangá", "manga"),
    Pair("Manhwa", "manhwa"),
    Pair("Manhua", "manhua"),
    Pair("Novel", "novel"),
    Pair("Colored Mangá", "colored_manga"),
)
