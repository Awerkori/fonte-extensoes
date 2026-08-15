package eu.kanade.tachiyomi.extension.pt.corujatoon

import eu.kanade.tachiyomi.source.model.Filter

internal open class ApiSelectFilter(
    name: String,
    private val options: List<Pair<String, String>>,
) : Filter.Select<String>(name, options.map { it.first }.toTypedArray()) {
    val selectedValue get() = options[state].second
}

internal class GenreFilter :
    ApiSelectFilter(
        "Gênero",
        listOf("Todos" to "", "Ação" to "acao", "Aventura" to "aventura", "Comédia" to "comedia", "Drama" to "drama", "Fantasia" to "fantasia", "Histórico" to "historico", "Isekai" to "isekai", "Manhwa" to "manhwa", "Romance" to "romance"),
    )

internal class TypeFilter :
    ApiSelectFilter(
        "Tipo",
        listOf("Todos" to "", "Manhwa" to "MANHWA", "Manhua" to "MANHUA", "Mangá" to "MANGA", "Webtoon" to "WEBTOON"),
    )

internal class StatusFilter :
    ApiSelectFilter(
        "Status",
        listOf("Todos" to "", "Em andamento" to "ONGOING", "Completo" to "COMPLETED", "Hiato" to "HIATUS"),
    )
