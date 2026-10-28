package eu.kanade.tachiyomi.extension.pt.manhq

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

object ManHQFilters {

    open class SelectFilter(
        name: String,
        private val options: Array<Pair<String, String>>,
    ) : Filter.Select<String>(name, options.map { it.first }.toTypedArray()) {
        val selected: String
            get() = options[state].second
    }

    class FormatFilter :
        SelectFilter(
            "Formato",
            arrayOf(
                "Todos" to "",
                "Mangás" to "manga",
                "Manhwas" to "manhwa",
                "Webtoons" to "webtoon",
                "HQs / Quadrinhos" to "comic",
            ),
        )

    class StatusFilter :
        SelectFilter(
            "Status",
            arrayOf(
                "Todos" to "",
                "Em andamento" to "ongoing",
                "Concluído" to "completed",
            ),
        )

    class SortFilter :
        SelectFilter(
            "Ordenar por",
            arrayOf(
                "Últimas atualizações" to "updated",
                "Mais populares" to "popular",
                "Ordem alfabética (A-Z)" to "az",
            ),
        )

    class GenreChip(name: String, val value: String) : Filter.TriState(name)

    class GenreGroup(genres: List<Pair<String, String>>) : Filter.Group<GenreChip>("Gêneros", genres.map { GenreChip(it.first, it.second) })

    val GENRES = listOf(
        "Ação" to "Action",
        "Adulto" to "Adult",
        "Aventura" to "Adventure",
        "Comédia" to "Comedy",
        "Crime" to "Crime",
        "Drama" to "Drama",
        "Família" to "Family",
        "Fantasia" to "Fantasy",
        "Ficção Científica" to "Sci-Fi",
        "Histórico" to "Historical",
        "Horror" to "Horror",
        "Isekai" to "Isekai",
        "Jogos" to "Game",
        "Josei" to "Josei",
        "Magia" to "Magic",
        "Artes Marciais" to "Martial Arts",
        "Militar" to "Military",
        "Mistério" to "Mystery",
        "Monstros" to "Monsters",
        "Mitologia" to "Mythology",
        "Política" to "Politics",
        "Pós-Apocalíptico" to "Post-Apocalyptic",
        "Psicológico" to "Psychological",
        "Vingança" to "Revenge",
        "Romance" to "Romance",
        "Escolar" to "School",
        "Seinen" to "Seinen",
        "Shoujo" to "Shoujo",
        "Shounen" to "Shounen",
        "Super-herói" to "Superhero",
        "Sobrenatural" to "Supernatural",
        "Suspense" to "Thriller",
        "Viagem no Tempo" to "Time Travel",
        "Vida Diária" to "Slice of Life",
        "Zumbis" to "Zombies",
    )

    fun get(): FilterList = FilterList(
        FormatFilter(),
        StatusFilter(),
        SortFilter(),
        Filter.Separator(),
        GenreGroup(GENRES),
    )
}
