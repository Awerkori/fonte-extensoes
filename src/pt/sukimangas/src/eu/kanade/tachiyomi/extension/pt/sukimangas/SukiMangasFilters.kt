package eu.kanade.tachiyomi.extension.pt.sukimangas

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

object SukiMangasFilters {

    fun get(): FilterList = FilterList(
        SortFilter(),
        StatusFilter(),
        TypeFilter(),
        SizeFilter(),
        Filter.Separator(),
        GenreGroup(genres.map { GenreTriState(it) }),
    )

    class SortFilter :
        Filter.Select<String>(
            "Ordenar por",
            sortOptions.map { it.first }.toTypedArray(),
            0,
        ) {
        val selected: String get() = sortOptions[state].second
    }

    class StatusFilter :
        Filter.Select<String>(
            "Status",
            statusOptions.map { it.first }.toTypedArray(),
            0,
        ) {
        val selected: String get() = statusOptions[state].second
    }

    class TypeFilter :
        Filter.Select<String>(
            "Tipo de conteúdo",
            typeOptions.map { it.first }.toTypedArray(),
            0,
        ) {
        val selected: String get() = typeOptions[state].second
    }

    class SizeFilter :
        Filter.Select<String>(
            "Tamanho da obra",
            sizeOptions.map { it.first }.toTypedArray(),
            0,
        ) {
        val selected: String get() = sizeOptions[state].second
    }

    class GenreTriState(name: String) : Filter.TriState(name)

    class GenreGroup(genres: List<GenreTriState>) :
        Filter.Group<GenreTriState>(
            "Gêneros (toque para incluir [+] ou excluir [-])",
            genres,
        )

    private val sortOptions = listOf(
        Pair("Em alta (Semanal)", "popular"),
        Pair("Recentes (Últimos lançamentos)", "recent"),
        Pair("Visualizações (Total)", "views"),
        Pair("Melhor avaliação", "rating"),
        Pair("Quantidade de capítulos", "chapters"),
        Pair("Título (A-Z)", "az"),
    )

    private val statusOptions = listOf(
        Pair("Todos", ""),
        Pair("Em andamento", "Em andamento"),
        Pair("Finalizado", "Finalizado"),
        Pair("Em Hiatus", "Em Hiatus"),
    )

    private val typeOptions = listOf(
        Pair("Apenas Mangás / Ilustrados", "manga_only"),
        Pair("Todos os formatos", "all"),
        Pair("Apenas Novels", "novel_only"),
    )

    private val sizeOptions = listOf(
        Pair("Todos", ""),
        Pair("Curta (0 - 20 capítulos)", "curta"),
        Pair("Média (21 - 60 capítulos)", "media"),
        Pair("Longa (61+ capítulos)", "longa"),
    )

    val genres = listOf(
        "Adulto (18+)",
        "Aventura",
        "Ação",
        "Comédia",
        "Drama",
        "Ecchi",
        "Escolar",
        "Fantasia",
        "Ficção Científica",
        "Futanari",
        "Harém",
        "Histórico",
        "Isekai",
        "Josei",
        "Light Novel",
        "Mangá",
        "Manhua",
        "Manhwa",
        "Mature",
        "Mistério",
        "Omegaverse",
        "Psicológico",
        "Romance",
        "Seinen",
        "Shounen",
        "Slice of Life",
        "Smut",
        "Sobrenatural",
        "Suspense",
        "Terror",
        "Webtoon",
        "Yaoi",
        "Yuri",
    )
}
