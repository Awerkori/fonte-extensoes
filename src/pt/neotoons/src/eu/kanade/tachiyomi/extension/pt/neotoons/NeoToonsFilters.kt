package eu.kanade.tachiyomi.extension.pt.neotoons

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
            Pair("Mais Recentes", "newest"),
            Pair("Mais Populares", "views"),
            Pair("Melhor Avaliados", "rating"),
            Pair("Ordem A-Z", "az"),
            Pair("Mais Favoritados", "bookmarks"),
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
            Pair("Todos os Status", "ALL"),
            Pair("Em Lançamento", "ONGOING"),
            Pair("Concluído", "COMPLETED"),
            Pair("Em Hiato", "HIATUS"),
        )
    }
}

class DemographicFilter :
    Filter.Select<String>(
        "Demografia",
        DEMOGRAPHIC_OPTIONS.map { it.first }.toTypedArray(),
    ) {
    val selected: String
        get() = DEMOGRAPHIC_OPTIONS[state].second

    companion object {
        val DEMOGRAPHIC_OPTIONS = arrayOf(
            Pair("Todas as Demografias", "ALL"),
            Pair("Shōnen", "SHONEN"),
            Pair("Seinen", "SEINEN"),
            Pair("Shōujo", "SHOUJO"),
            Pair("Josei", "JOSEI"),
            Pair("Geral", "NONE"),
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
            Pair("Todos os Gêneros", ""),
            Pair("Drama", "drama"),
            Pair("Romance", "romance"),
            Pair("Ação", "acao"),
            Pair("Fantasia", "fantasia"),
            Pair("Comédia", "comedia"),
            Pair("Aventura", "aventura"),
            Pair("Sobrenatural", "sobrenatural"),
            Pair("Shounen", "shounen"),
            Pair("Cotidiano", "cotidiano"),
            Pair("Shoujo", "shoujo"),
            Pair("Artes Marciais", "artes-marciais"),
            Pair("Livres", "livres"),
            Pair("Psicológico", "psicologico"),
            Pair("Mistério", "misterio"),
            Pair("Slice of Life", "slice-of-life"),
            Pair("Magia", "magia"),
            Pair("Escolar", "escolar"),
            Pair("Histórico", "historico"),
            Pair("Seinen", "seinen"),
            Pair("Isekai", "isekai"),
            Pair("Reencarnação", "reencarnacao"),
            Pair("Shoujo / Romances", "shoujo-romances"),
            Pair("Ficção Científica", "ficcao-cientifica"),
            Pair("Terror", "terror"),
            Pair("Josei", "josei"),
            Pair("Vida Escolar", "vida-escolar"),
            Pair("Yaoi", "yaoi"),
            Pair("Yuri", "yuri"),
            Pair("Superpoderes", "superpoderes"),
            Pair("Vingança", "vinganca"),
            Pair("Harem", "harem"),
            Pair("Esportes", "esportes"),
            Pair("Monstros", "monstros"),
            Pair("Tragédia", "tragedia"),
        )
    }
}

object NeoToonsFilters {
    fun get(): FilterList = FilterList(
        SortFilter(),
        StatusFilter(),
        DemographicFilter(),
        GenreFilter(),
    )
}
