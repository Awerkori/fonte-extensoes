package eu.kanade.tachiyomi.extension.pt.geasscomics

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

fun getFilters(data: FilterDataDto?, showNsfw: Boolean): FilterList {
    val genres = data?.genres.orEmpty().filter { showNsfw || !it.isNsfw }
    val tags = data?.tags.orEmpty().filter { showNsfw || !it.isNsfw }

    return FilterList(
        SortFilter(),
        TypeFilter(),
        Filter.Separator(),
        Filter.Header(
            if (showNsfw) {
                "Conteúdo +18 habilitado nas preferências"
            } else {
                "Conteúdo +18 oculto nas preferências"
            },
        ),
        Filter.Separator(),
        if (genres.isEmpty()) {
            Filter.Header("Toque em 'Redefinir' para carregar os gêneros")
        } else {
            GenreFilter(genres)
        },
        if (tags.isEmpty()) {
            Filter.Header("Toque em 'Redefinir' para carregar as tags")
        } else {
            TagFilter(tags)
        },
    )
}

class SortFilter :
    Filter.Select<String>(
        "Ordenar por",
        SORT_OPTIONS.map { it.first }.toTypedArray(),
    ) {
    val sortBy get() = SORT_OPTIONS[state].second.first
    val sortDir get() = SORT_OPTIONS[state].second.second

    companion object {
        private val SORT_OPTIONS = listOf(
            "Mais recentes" to ("recent" to "desc"),
            "Melhor avaliados" to ("rating" to "desc"),
            "Título (A-Z)" to ("title" to "asc"),
            "Título (Z-A)" to ("title" to "desc"),
        )
    }
}

class TypeFilter :
    Filter.Group<TypeCheckBox>(
        "Tipo",
        listOf(
            TypeCheckBox("Manhwa", "manhwa"),
            TypeCheckBox("Manhua", "manhua"),
            TypeCheckBox("Mangá", "manga"),
        ),
    ) {
    val selectedValues get() = state.filter { it.state }.map { it.value }
}

class TypeCheckBox(name: String, val value: String) : Filter.CheckBox(name)

class GenreFilter(genres: List<FilterOptionDto>) :
    Filter.Group<GenreCheckBox>(
        "Gêneros",
        genres.map { GenreCheckBox(it.label, it.slug) },
    ) {
    val selectedValues get() = state.filter { it.state }.map { it.value }
}

class GenreCheckBox(name: String, val value: String) : Filter.CheckBox(name)

class TagFilter(tags: List<FilterOptionDto>) :
    Filter.Group<TagCheckBox>(
        "Tags",
        tags.map { TagCheckBox(it.label, it.slug) },
    ) {
    val selectedValues get() = state.filter { it.state }.map { it.value }
}

class TagCheckBox(name: String, val value: String) : Filter.CheckBox(name)
