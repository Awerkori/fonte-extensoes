package eu.kanade.tachiyomi.extension.pt.gomangas

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

object GoMangasFilters {
    fun get(): FilterList = FilterList(
        SortFilter(),
        TypeFilter(),
        StatusFilter(),
        GenreFilter(),
    )
}

class SortFilter : Filter.Select<String>("Ordenar por", SORT_OPTIONS.map { it.name }.toTypedArray(), 0) {
    val selected: String
        get() = SORT_OPTIONS[state].value

    companion object {
        val SORT_OPTIONS = listOf(
            FilterOption("Recentes", "recentes"),
            FilterOption("Populares", "popular"),
            FilterOption("A — Z", "az"),
            FilterOption("Mais capítulos", "capitulos"),
        )
    }
}

class TypeFilter : Filter.Select<String>("Tipo", TYPE_OPTIONS.map { it.name }.toTypedArray(), 0) {
    val selected: String
        get() = TYPE_OPTIONS[state].value

    companion object {
        val TYPE_OPTIONS = listOf(
            FilterOption("Todos os tipos", ""),
            FilterOption("Mangá", "MANGA"),
            FilterOption("Manhwa", "MANHWA"),
            FilterOption("Manhua", "MANHUA"),
        )
    }
}

class StatusFilter : Filter.Select<String>("Status", STATUS_OPTIONS.map { it.name }.toTypedArray(), 0) {
    val selected: String
        get() = STATUS_OPTIONS[state].value

    companion object {
        val STATUS_OPTIONS = listOf(
            FilterOption("Qualquer status", ""),
            FilterOption("Em andamento", "ONGOING"),
            FilterOption("Completo", "COMPLETED"),
            FilterOption("Hiato", "HIATUS"),
        )
    }
}

class GenreFilter : Filter.Select<String>("Gênero", GENRE_OPTIONS.map { it.name }.toTypedArray(), 0) {
    val selected: String
        get() = GENRE_OPTIONS[state].value

    companion object {
        val GENRE_OPTIONS = listOf(
            FilterOption("Todos os gêneros", ""),
            FilterOption("Ação", "acao"),
            FilterOption("Aventura", "aventura"),
            FilterOption("Boys' Love", "boys-love"),
            FilterOption("Comédia", "comedia"),
            FilterOption("Crime", "crime"),
            FilterOption("Drama", "drama"),
            FilterOption("Ecchi", "ecchi"),
            FilterOption("Esportes", "esportes"),
            FilterOption("Fantasia", "fantasia"),
            FilterOption("Ficção Científica", "ficcao-cientifica"),
            FilterOption("Garotas Mágicas", "mahou-shoujo"),
            FilterOption("Girls' Love", "girls-love"),
            FilterOption("Hentai", "hentai"),
            FilterOption("Histórico", "historico"),
            FilterOption("Isekai", "isekai"),
            FilterOption("Mecha", "mecha"),
            FilterOption("Médico", "medico"),
            FilterOption("Mistério", "misterio"),
            FilterOption("Música", "musica"),
            FilterOption("Psicológico", "psicologico"),
            FilterOption("Romance", "romance"),
            FilterOption("Slice of Life", "slice-of-life"),
            FilterOption("Sobrenatural", "sobrenatural"),
            FilterOption("Super-heróis", "super-herois"),
            FilterOption("Suspense", "suspense"),
            FilterOption("Terror", "terror"),
            FilterOption("Tragédia", "tragedia"),
        )
    }
}

class FilterOption(val name: String, val value: String)
