package eu.kanade.tachiyomi.extension.pt.karikari

import eu.kanade.tachiyomi.source.model.Filter

internal class GenreFilter(options: List<GenreOption>) :
    Filter.Select<String>(
        "Gênero",
        (listOf(GenreOption("", "Todos")) + options).map(GenreOption::nome).toTypedArray(),
    ) {
    private val options = listOf(GenreOption("", "Todos")) + options
    val selectedId: String?
        get() = options.getOrNull(state)?.id?.takeIf(String::isNotBlank)
}

internal class StatusFilter :
    Filter.Select<String>(
        "Status",
        STATUS.map { it.first }.toTypedArray(),
    ) {
    val selectedValue: String?
        get() = STATUS.getOrNull(state)?.second?.takeIf(String::isNotBlank)

    private companion object {
        val STATUS = listOf(
            "Todos" to "",
            "Em produção" to "Em produção",
            "Finalizado" to "Finalizado",
            "Hiato" to "Hiato",
            "Cancelado" to "Cancelado",
        )
    }
}

internal class AgeFilter :
    Filter.Select<String>(
        "Classificação",
        AGE.map { it.first }.toTypedArray(),
    ) {
    val selectedValues: List<String>
        get() = AGE.getOrNull(state)?.second.orEmpty()

    private companion object {
        val AGE = listOf(
            "Todas" to emptyList(),
            "L/6" to listOf("L/6"),
            "10+" to listOf("10"),
            "12/14" to listOf("12/14"),
            "16+" to listOf("16"),
            "+18" to listOf("18+"),
        )
    }
}

internal class TypeFilter :
    Filter.Select<String>(
        "Tipo",
        TYPE.map { it.first }.toTypedArray(),
    ) {
    val selectedValue: String?
        get() = TYPE.getOrNull(state)?.second?.takeIf(String::isNotBlank)

    private companion object {
        val TYPE = listOf(
            "Todos" to "",
            "Autorais" to "autoral",
            "Traduções" to "traducao",
        )
    }
}

internal class RatingFilter :
    Filter.Select<String>(
        "Nota mínima",
        RATING.map { it.first }.toTypedArray(),
    ) {
    val selectedValue: Double
        get() = RATING.getOrNull(state)?.second ?: 0.0

    private companion object {
        val RATING = listOf(
            "Qualquer" to 0.0,
            "3+" to 3.0,
            "4+" to 4.0,
            "4,5+" to 4.5,
        )
    }
}

internal class SortFilter :
    Filter.Select<String>(
        "Ordenar por",
        SORT.map { it.first }.toTypedArray(),
    ) {
    val selectedValue: String
        get() = SORT.getOrNull(state)?.second ?: "visualizacoes"

    private companion object {
        val SORT = listOf(
            "Mais vistas" to "visualizacoes",
            "Mais bem avaliadas" to "nota",
            "Atualizadas recentemente" to "atualizado",
            "Mais recentes" to "criado",
        )
    }
}
