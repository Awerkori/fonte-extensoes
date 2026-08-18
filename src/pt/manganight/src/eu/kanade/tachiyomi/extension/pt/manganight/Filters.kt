package eu.kanade.tachiyomi.extension.pt.manganight

import eu.kanade.tachiyomi.source.model.Filter

class SortFilter :
    Filter.Select<String>(
        "Ordenar por",
        SORT_OPTIONS.map { it.first }.toTypedArray(),
    ) {
    val selectedValue: String
        get() = SORT_OPTIONS[state].second

    companion object {
        val SORT_OPTIONS = arrayOf(
            Pair("Mais Populares", "POPULARITY_DESC"),
            Pair("Lançamentos Recentes", "START_DATE_DESC"),
            Pair("Melhor Avaliados", "SCORE_DESC"),
            Pair("Em Alta", "TRENDING_DESC"),
            Pair("Mais Favoritados", "FAVOURITES_DESC"),
            Pair("Mais Capítulos", "CHAPTERS_DESC"),
        )
    }
}

class GenreFilter :
    Filter.Select<String>(
        "Gênero",
        GENRES.map { it.first }.toTypedArray(),
    ) {
    val selectedValue: String
        get() = GENRES[state].second

    companion object {
        val GENRES = arrayOf(
            Pair("Todos", ""),
            Pair("Action", "Action"),
            Pair("Adventure", "Adventure"),
            Pair("Comedy", "Comedy"),
            Pair("Drama", "Drama"),
            Pair("Fantasy", "Fantasy"),
            Pair("Horror", "Horror"),
            Pair("Mystery", "Mystery"),
            Pair("Romance", "Romance"),
            Pair("Sci-Fi", "Sci-Fi"),
            Pair("Slice of Life", "Slice of Life"),
            Pair("Supernatural", "Supernatural"),
            Pair("Thriller", "Thriller"),
        )
    }
}

class StatusFilter :
    Filter.Select<String>(
        "Status",
        STATUSES.map { it.first }.toTypedArray(),
    ) {
    val selectedValue: String
        get() = STATUSES[state].second

    companion object {
        val STATUSES = arrayOf(
            Pair("Todos", ""),
            Pair("Em Lançamento", "RELEASING"),
            Pair("Completo", "FINISHED"),
            Pair("Não Lançado", "NOT_YET_RELEASED"),
            Pair("Cancelado", "CANCELLED"),
            Pair("Em Hiato", "HIATUS"),
        )
    }
}

class CountryFilter :
    Filter.Select<String>(
        "Origem",
        COUNTRIES.map { it.first }.toTypedArray(),
    ) {
    val selectedValue: String
        get() = COUNTRIES[state].second

    companion object {
        val COUNTRIES = arrayOf(
            Pair("Todas", ""),
            Pair("Japão (Mangá)", "JP"),
            Pair("Coreia do Sul (Manhwa)", "KR"),
            Pair("China (Manhua)", "CN"),
        )
    }
}
