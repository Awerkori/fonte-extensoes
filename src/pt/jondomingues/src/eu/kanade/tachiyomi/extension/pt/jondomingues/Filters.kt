package eu.kanade.tachiyomi.extension.pt.jondomingues

import eu.kanade.tachiyomi.source.model.Filter

class CategoryFilter(categories: Array<Pair<String, String>>) : Filter.Select<String>("Categoria / Editora", categories.map { it.first }.toTypedArray()) {
    val selectedId: String
        get() = CATEGORIES[state].second

    companion object {
        val CATEGORIES = arrayOf(
            Pair("Todas", ""),
            Pair("DC Comics", "22"),
            Pair("Marvel Comics", "21"),
            Pair("Dynamite Entertainment", "23"),
            Pair("Image Comics", "59"),
            Pair("IDW Publishing", "206"),
            Pair("The Boys", "48"),
            Pair("Absolute Batman", "94"),
            Pair("Batman", "78"),
            Pair("Batman: Padrões Sombrios", "154"),
            Pair("Justiceiro", "143"),
            Pair("Miles Morales: Homem-Aranha", "47"),
            Pair("Wolverine", "180"),
            Pair("Liga da Justiça", "120"),
            Pair("Savage Dragon", "142"),
            Pair("Lanterna Verde", "233"),
            Pair("Deadpool", "181"),
            Pair("Invencível", "60"),
            Pair("Flash", "99"),
            Pair("Caverna do Dragão", "207"),
            Pair("Os Vingadores", "31"),
            Pair("Thundercats", "24"),
        )
    }
}

class SortFilter :
    Filter.Select<String>(
        "Ordenar por",
        arrayOf("Mais Recentes", "Ordem Alfabética (A-Z)", "Ordem Alfabética (Z-A)", "Mais Antigos"),
    ) {
    fun toParams(): Pair<String, String> = when (state) {
        0 -> Pair("date", "desc")
        1 -> Pair("title", "asc")
        2 -> Pair("title", "desc")
        3 -> Pair("date", "asc")
        else -> Pair("date", "desc")
    }
}
