package eu.kanade.tachiyomi.extension.pt.zinnes

import eu.kanade.tachiyomi.source.model.Filter

internal open class ApiSelectFilter(
    name: String,
    private val options: List<Pair<String, String>>,
) : Filter.Select<String>(name, options.map { it.first }.toTypedArray()) {
    val selectedValue get() = options[state].second.takeIf(String::isNotEmpty)
}

internal class GenreFilter(options: List<FilterOptionDto>) :
    ApiSelectFilter(
        "Gênero",
        listOf("Todos" to "") + options.map { it.name to it.id.toString() },
    )

internal class LanguageFilter(options: List<FilterOptionDto>) :
    ApiSelectFilter(
        "Idioma",
        listOf("Todos" to "") + options.map { it.name to it.id.toString() },
    )

internal class TypeFilter(options: List<FilterOptionDto>) :
    ApiSelectFilter(
        "Tipo",
        listOf("Todos" to "") + options.map { it.name to it.id.toString() },
    )
