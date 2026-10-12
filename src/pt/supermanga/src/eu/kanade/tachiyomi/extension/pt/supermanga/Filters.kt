package eu.kanade.tachiyomi.extension.pt.supermanga

import eu.kanade.tachiyomi.source.model.Filter

internal class SortFilter :
    Filter.Select<String>(
        "Ordenar por",
        SORT_OPTIONS.map { it.first }.toTypedArray(),
    ) {
    val selectedValue: String
        get() = SORT_OPTIONS.getOrNull(state)?.second ?: "popular"

    private companion object {
        val SORT_OPTIONS = listOf(
            "Mais Popular" to "popular",
            "Mais Recentes" to "newest",
            "Em Alta" to "followed",
            "Mais Bem Avaliados" to "rating",
        )
    }
}

internal class StatusFilter :
    Filter.Select<String>(
        "Status",
        STATUS_OPTIONS.map { it.first }.toTypedArray(),
    ) {
    val selectedValue: String?
        get() = STATUS_OPTIONS.getOrNull(state)?.second?.takeIf(String::isNotBlank)

    private companion object {
        val STATUS_OPTIONS = listOf(
            "Todos" to "",
            "Em Lançamento" to "ongoing",
            "Completo" to "completed",
            "Cancelado" to "cancelled",
        )
    }
}

internal class TypeFilter :
    Filter.Select<String>(
        "Tipo",
        TYPE_OPTIONS.map { it.first }.toTypedArray(),
    ) {
    val selectedValue: String?
        get() = TYPE_OPTIONS.getOrNull(state)?.second?.takeIf(String::isNotBlank)

    private companion object {
        val TYPE_OPTIONS = listOf(
            "Todos" to "",
            "Mangá" to "manga",
            "Manhwa" to "manhwa",
            "Manhua" to "manhua",
            "Novel" to "novel",
        )
    }
}

internal class DemographicFilter :
    Filter.Select<String>(
        "Demografia",
        DEMOGRAPHIC_OPTIONS.map { it.first }.toTypedArray(),
    ) {
    val selectedValue: String?
        get() = DEMOGRAPHIC_OPTIONS.getOrNull(state)?.second?.takeIf(String::isNotBlank)

    private companion object {
        val DEMOGRAPHIC_OPTIONS = listOf(
            "Todas" to "",
            "Shounen" to "shounen",
            "Seinen" to "seinen",
            "Shoujo" to "shoujo",
            "Josei" to "josei",
        )
    }
}

internal class ContentRatingFilter :
    Filter.Select<String>(
        "Classificação Indicativa",
        RATING_OPTIONS.map { it.first }.toTypedArray(),
    ) {
    val selectedValue: String?
        get() = RATING_OPTIONS.getOrNull(state)?.second?.takeIf(String::isNotBlank)

    private companion object {
        val RATING_OPTIONS = listOf(
            "Todas" to "",
            "Livre" to "safe",
            "Sugestivo" to "suggestive",
            "Erótico" to "erotica",
            "Pornográfico" to "pornographic",
        )
    }
}

internal class GenreCheckBox(name: String) : Filter.CheckBox(name)

internal class GenreGroup(genres: List<String>) :
    Filter.Group<GenreCheckBox>(
        "Gêneros",
        genres.map { GenreCheckBox(it) },
    ) {
    val selectedGenres: List<String>
        get() = state.filter { it.state }.map { it.name }
}

internal val DEFAULT_GENRES = listOf(
    "4-Koma",
    "Action",
    "Adaptation",
    "Adventure",
    "Animals",
    "Anthology",
    "Aventura",
    "Award Winning",
    "Ação",
    "Boy's Love",
    "Boys' Love",
    "Comedy",
    "Comédia",
    "Cotidiano",
    "Crime",
    "Delinquents",
    "Demons",
    "Drama",
    "Ecchi",
    "Esportes",
    "Fantasia",
    "Fantasy",
    "Ficção científica",
    "Full Color",
    "Garotas Mágicas",
    "Ghosts",
    "Girl's Love",
    "Girls' Love",
    "Gore",
    "Harem",
    "Hentai",
    "Historical",
    "Histórico",
    "Horror",
    "Isekai",
    "Long Strip",
    "Magic",
    "Magical Girls",
    "Martial Arts",
    "Mecha",
    "Medical",
    "Mistério",
    "Monster Girls",
    "Monsters",
    "Murim",
    "Mystery",
    "Ninja",
    "Oneshot",
    "Philosophical",
    "Psicológico",
    "Psychological",
    "Reincarnation",
    "Romance",
    "School Life",
    "Sci-Fi",
    "Self-Published",
    "Shota",
    "Sistema de jogo",
    "Slice of Life",
    "Sports",
    "Super-herói",
    "Superhero",
    "Supernatural",
    "Thriller",
    "Tragedy",
    "Tragédia",
    "Video Games",
    "Virtual Reality",
    "Web Comic",
    "Wuxia",
)
