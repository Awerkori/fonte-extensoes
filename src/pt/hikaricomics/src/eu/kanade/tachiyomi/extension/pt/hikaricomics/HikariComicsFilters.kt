package eu.kanade.tachiyomi.extension.pt.hikaricomics

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

class SortFilter(
    defaultIndex: Int = 0,
) : Filter.Select<String>(
    "Ordenar por",
    SORT_OPTIONS.map { it.first }.toTypedArray(),
    defaultIndex,
) {
    val selected: String
        get() = SORT_OPTIONS[state].second

    companion object {
        val SORT_OPTIONS = arrayOf(
            Pair("Atualizados", "updated"),
            Pair("Mais vistos", "views"),
            Pair("Mais recentes", "newest"),
            Pair("Mais favoritados", "favorites"),
            Pair("Melhor avaliados", "rating"),
            Pair("Mais capítulos", "chapters"),
            Pair("A-Z", "az"),
        )
    }
}

class StatusFilter :
    Filter.Select<String>(
        "Status",
        STATUS_OPTIONS.map { it.first }.toTypedArray(),
    ) {
    val selected: String
        get() = STATUS_OPTIONS[state].second

    companion object {
        val STATUS_OPTIONS = arrayOf(
            Pair("Todos os status", ""),
            Pair("Em lançamento", "Em lançamento"),
            Pair("Completo", "Completo"),
            Pair("Pausado", "Pausado"),
            Pair("Cancelado", "Cancelado"),
        )
    }
}

class GenreFilter :
    Filter.Select<String>(
        "Gênero",
        GENRE_OPTIONS.map { it.first }.toTypedArray(),
    ) {
    val selected: String
        get() = GENRE_OPTIONS[state].second

    companion object {
        val GENRE_OPTIONS = arrayOf(
            Pair("Todos os gêneros", ""),
            Pair("Ahegao", "ahegao"),
            Pair("Aventura", "aventura"),
            Pair("Ação", "acao"),
            Pair("Casada", "casada"),
            Pair("Comédia", "comedia"),
            Pair("Creampie", "creampie"),
            Pair("Drama", "drama"),
            Pair("Esporte", "esporte"),
            Pair("Fantasia", "fantasia"),
            Pair("Harém", "harem"),
            Pair("História", "historia"),
            Pair("Horror", "horror"),
            Pair("Incesto", "incesto"),
            Pair("Isekai", "isekai"),
            Pair("Massagem", "massagem"),
            Pair("Milf", "milf"),
            Pair("Netorare", "netorare"),
            Pair("Netori", "netori"),
            Pair("Romance", "romance"),
            Pair("Traição", "traicao"),
        )
    }
}

object HikariComicsFilters {
    fun get(): FilterList = FilterList(
        SortFilter(),
        StatusFilter(),
        GenreFilter(),
    )
}
