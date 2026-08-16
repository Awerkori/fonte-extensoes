package eu.kanade.tachiyomi.extension.pt.mugiwarasoficial

import eu.kanade.tachiyomi.source.model.Filter

class SortFilter :
    Filter.Select<String>(
        "Ordenar por",
        arrayOf("Popular", "A-Z"),
    ) {
    fun toUriPart(): String = when (state) {
        1 -> "title"
        else -> ""
    }
}

class TypeFilter :
    Filter.Select<String>(
        "Tipo",
        arrayOf("Todos", "Mangá", "Manhwa", "Manhua"),
    ) {
    fun toUriPart(): String = when (state) {
        1 -> "manga"
        2 -> "manhwa"
        3 -> "manhua"
        else -> ""
    }
}

class GenreFilter :
    Filter.Select<String>(
        "Gênero",
        GENRES.map { it.first }.toTypedArray(),
    ) {
    fun toUriPart(): String = GENRES[state].second

    companion object {
        private val GENRES = arrayOf(
            "Todos" to "",
            "Ação" to "acao",
            "Aventura" to "aventura",
            "Comédia" to "comedia",
            "Drama" to "drama",
            "Esportes" to "esportes",
            "Fantasia" to "fantasia",
            "Histórico" to "historico",
            "Horror" to "horror",
            "Isekai" to "isekai",
            "Josei" to "josei",
            "Mecha" to "mecha",
            "Mistério" to "misterio",
            "Psicológico" to "psicologico",
            "Romance" to "romance",
            "Sci-Fi" to "sci-fi",
            "Seinen" to "seinen",
            "Shoujo" to "shoujo",
            "Shounen" to "shounen",
            "Slice of Life" to "slice-of-life",
            "Sobrenatural" to "sobrenatural",
            "Thriller" to "thriller",
            "Yaoi" to "yaoi",
            "Yuri" to "yuri",
        )
    }
}
