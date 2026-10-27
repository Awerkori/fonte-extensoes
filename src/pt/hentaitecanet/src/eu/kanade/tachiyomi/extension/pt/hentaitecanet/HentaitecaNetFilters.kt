package eu.kanade.tachiyomi.extension.pt.hentaitecanet

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import keiyoushi.utils.firstInstanceOrNull

open class ChoiceFilter(
    name: String,
    private val entries: Array<Pair<String, String>>,
    defaultIndex: Int = 0,
) : Filter.Select<String>(
    name,
    entries.map { it.second }.toTypedArray(),
    defaultIndex,
) {
    fun getValue(): String = entries[state].first
}

class SortFilter(
    defaultIndex: Int = 0,
) : ChoiceFilter(
    "Ordenar por",
    arrayOf(
        "views" to "Mais visualizados",
        "latest" to "Atualizações recentes",
        "new-manga" to "Novos títulos",
        "alphabet" to "Ordem alfabética",
        "rating" to "Melhor avaliados",
    ),
    defaultIndex,
)

class StatusFilter :
    ChoiceFilter(
        "Status",
        arrayOf(
            "" to "Todos os status",
            "on-going" to "Em Lançamento",
            "end" to "Completa",
            "canceled" to "Cancelada",
            "on-hold" to "Hiatus",
            "upcoming" to "A Caminho",
        ),
    )

class GenreConditionFilter :
    ChoiceFilter(
        "Filtrar por gênero",
        arrayOf(
            "" to "OU (ao menos um selecionado)",
            "1" to "E (todos os selecionados)",
        ),
    )

class GenreCheckBox(
    name: String,
    val value: String = name,
) : Filter.CheckBox(name)

class GenreFilter(genres: List<Pair<String, String>>) :
    Filter.Group<GenreCheckBox>(
        "Gêneros",
        genres.map { GenreCheckBox(it.second, it.first) },
    )

inline fun <reified T : ChoiceFilter> FilterList.valueOrEmpty(): String = firstInstanceOrNull<T>()?.getValue().orEmpty()

fun FilterList.selectedGenres(): List<String> = firstInstanceOrNull<GenreFilter>()?.state
    ?.filter { it.state }
    ?.map { it.value }
    .orEmpty()

val GENRES_LIST = listOf(
    "acao" to "Ação",
    "ahegao" to "Ahegao",
    "anal" to "Anal",
    "aventura" to "Aventura",
    "bbw" to "BBW",
    "bdsm" to "BDSM",
    "biquini" to "Biquíni",
    "bondage" to "Bondage",
    "boquete" to "Boquete",
    "brinquedos" to "Brinquedos",
    "bunda-grande" to "Bunda Grande",
    "camisinha" to "Camisinha",
    "chantagem" to "Chantagem",
    "colegial" to "Colegial",
    "comedia" to "Comédia",
    "creampie" to "Creampie",
    "dark-skin" to "Dark Skin",
    "demonios" to "Demônios",
    "dormindo" to "Dormindo",
    "drama" to "Drama",
    "elfa" to "Elfa",
    "empregada" to "Empregada",
    "esporte" to "Esporte",
    "estupro" to "Estupro",
    "exibicionismo" to "Exibicionismo",
    "fantasia" to "Fantasia",
    "femdom" to "Femdom",
    "filmando" to "Filmando",
    "futanari" to "Futanari",
    "grupal" to "Grupal",
    "gyaru" to "Gyaru",
    "harem" to "Harém",
    "historico" to "Histórico",
    "horror" to "Horror",
    "incesto" to "Incesto",
    "irma" to "Irmã",
    "lactacao" to "Lactação",
    "latex" to "Latex",
    "lingerie" to "Lingerie",
    "lolicon" to "Lolicon",
    "mae" to "Mãe",
    "magia" to "Magia",
    "manhwa-hentai" to "Manhwa Hentai",
    "masturbacao" to "Masturbação",
    "milf" to "Milf",
    "mind-break" to "Mind Break",
    "mind-control" to "Mind Control",
    "netorare" to "Netorare",
    "netori" to "Netori",
    "oculos" to "Óculos",
    "oral" to "Oral",
    "paizuri" to "Paizuri",
    "peitinhos" to "Peitinhos",
    "peitoes" to "Peitões",
    "pelos-pubianos" to "Pelos Pubianos",
    "pornhwa" to "Pornhwa",
    "professora" to "Professora",
    "prostituicao" to "Prostituição",
    "raio-x" to "Raio-X",
    "romance" to "Romance",
    "sci-fi" to "Sci-Fi",
    "seinen" to "Seinen",
    "shotacon" to "Shotacon",
    "sistema" to "Sistema",
    "sobrenatural" to "Sobrenatural",
    "tia" to "Tia",
    "tomboy" to "Tomboy",
    "tomgirl" to "Tomgirl",
    "tragedia" to "Tragédia",
    "traicao" to "Traição",
    "vida-cotidiana" to "Vida Cotidiana",
    "vida-escolar" to "Vida Escolar",
    "vinganca" to "Vingança",
    "virgem" to "Virgem",
    "vizinha" to "Vizinha",
    "voyeur" to "Voyeur",
    "webtoon" to "Webtoon",
    "yandere" to "Yandere",
    "yuri" to "Yuri",
)

object HentaitecaNetFilters {
    fun get(): FilterList = FilterList(
        SortFilter(),
        StatusFilter(),
        GenreConditionFilter(),
        GenreFilter(GENRES_LIST),
    )
}
