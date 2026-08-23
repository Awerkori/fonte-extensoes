package eu.kanade.tachiyomi.extension.pt.tomato

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

class Category(name: String) : Filter.CheckBox(name)

class CategoryFilter(categories: List<String>) :
    Filter.Group<Filter.CheckBox>(
        "Categorias",
        categories.map(::Category),
    ) {
    val selectedNames get() = state.filter { it.state }.map { it.name }
}

fun getFilters(categories: List<String>) = FilterList(CategoryFilter(categories))
