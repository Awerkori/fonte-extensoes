package eu.kanade.tachiyomi.extension.pt.tomato

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

class Category(name: String) : Filter.CheckBox(name)
class CategoryFilter(categories: List<String>) : Filter.Group<Filter.CheckBox>("Categorias", categories.map(::Category)) {
    val selectedNames get() = state.filter { it.state }.map { it.name }
}
fun getFilters(data: JsonElement?) = FilterList(
    CategoryFilter(
        data?.let { element ->
            element.toString().let { raw -> raw.removePrefix("[").removeSuffix("]").split(',').map(String::trim).map { it.trim('"') }.filter(String::isNotEmpty) }
        }.orEmpty(),
    ),
)
fun categoriesJson(names: List<String>) = buildJsonArray { names.forEach { add(JsonPrimitive(it)) } }
