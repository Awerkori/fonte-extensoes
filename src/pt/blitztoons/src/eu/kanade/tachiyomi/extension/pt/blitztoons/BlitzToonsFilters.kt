package eu.kanade.tachiyomi.extension.pt.blitztoons

import eu.kanade.tachiyomi.source.model.Filter

class OrderFilter(
    defaultOrder: String = "recentes",
) : Filter.Select<String>(
    "Ordenar por",
    orderOptions.map { it.first }.toTypedArray(),
    orderOptions.indexOfFirst { it.second == defaultOrder }.coerceAtLeast(0),
) {
    val selected: String
        get() = orderOptions[state].second
}

private val orderOptions = arrayOf(
    Pair("Recentes", "recentes"),
    Pair("Populares", "populares"),
    Pair("Melhor nota", "nota"),
    Pair("A–Z", "alfabetica"),
)

class TypeFilter :
    Filter.Select<String>(
        "Tipo / Categoria",
        typeOptions.map { it.first }.toTypedArray(),
    ) {
    val selected: String
        get() = typeOptions[state].second
}

private val typeOptions = arrayOf(
    Pair("Todos", ""),
    Pair("Comics", "comic"),
    Pair("Novels", "novel"),
    Pair("Yaoi", "yaoi"),
    Pair("Yuri", "yuri"),
    Pair("Shoujo", "shoujo"),
)

class StatusFilter :
    Filter.Select<String>(
        "Status",
        statusOptions.map { it.first }.toTypedArray(),
    ) {
    val selected: String
        get() = statusOptions[state].second
}

private val statusOptions = arrayOf(
    Pair("Todos", ""),
    Pair("Em lançamento", "ongoing"),
    Pair("Completo", "completed"),
    Pair("Hiato", "hiatus"),
)

class GenreFilter :
    Filter.Select<String>(
        "Gênero",
        genreOptions.map { it.first }.toTypedArray(),
    ) {
    val selected: String
        get() = genreOptions[state].second
}

private val genreOptions = arrayOf(
    Pair("Todos", ""),
    Pair("+18", "mais-18"),
    Pair("Absolute Cinema!!! 🙌", "absolute-cinema"),
    Pair("Academia", "academia"),
    Pair("Aconchegante", "aconchegante"),
    Pair("action", "action"),
    Pair("Adaptação", "adaptacao"),
    Pair("Adult", "adult"),
    Pair("Adulto", "adulto"),
    Pair("Adultério", "adulterio"),
    Pair("Adventure", "adventure"),
    Pair("Agentes", "agentes"),
    Pair("Alta Fantasia", "alta-fantasia"),
    Pair("Amadurecimento", "amadurecimento"),
    Pair("Animais", "animais"),
    Pair("Anti-Herói", "anti-heroi"),
    Pair("Apocalipse", "apocalipse"),
    Pair("Apocalíptico", "apocaliptico"),
    Pair("Arte Marcial", "arte-marcial"),
    Pair("Artes Marciais", "artes-marciais"),
    Pair("Aventura", "aventura"),
    Pair("Ação", "acao"),
    Pair("Banner", "banner"),
    Pair("Bullying", "bullying"),
    Pair("Colorido", "colorido"),
    Pair("Comédia", "comedia"),
    Pair("Construção", "construcao"),
    Pair("Cotidiano", "cotidiano"),
    Pair("Crime", "crime"),
    Pair("Culinária", "culinaria"),
    Pair("Cultivação", "cultivacao"),
    Pair("Cultivo", "cultivo"),
    Pair("Dança", "danca"),
    Pair("Delinquentes", "delinquentes"),
    Pair("Demons", "demons"),
    Pair("Demônio", "demonio"),
    Pair("Demônios", "demonios"),
    Pair("Drama", "drama"),
    Pair("Dungeon", "dungeon"),
    Pair("Ecchi", "ecchi"),
    Pair("Escola", "escola"),
    Pair("Escolar", "escolar"),
    Pair("Escritório", "escritorio"),
    Pair("Esporte", "esporte"),
    Pair("Esportes", "esportes"),
    Pair("Familia", "familia"),
    Pair("Fantasia", "fantasia"),
    Pair("Fantasia Sombria", "fantasia-sombria"),
    Pair("Fatia da Vida/Slice of Life", "fatia-da-vida-slice-of-life"),
    Pair("Fazenda", "fazenda"),
    Pair("Ficção Científica", "ficcao-cientifica"),
    Pair("Game Elements", "game-elements"),
    Pair("Gore", "gore"),
    Pair("Guerra", "guerra"),
    Pair("Harém", "harem"),
    Pair("Heterossexual", "heterossexual"),
    Pair("Histórico", "historico"),
    Pair("Horror", "horror"),
    Pair("Isekai", "isekai"),
    Pair("Jogo", "jogo"),
    Pair("Jogos", "jogos"),
    Pair("Josei", "josei"),
    Pair("Linha do Tempo", "linha-do-tempo"),
    Pair("Luta", "luta"),
    Pair("Maduro", "maduro"),
    Pair("Magia", "magia"),
    Pair("manhua", "manhua"),
    Pair("Manhwa", "manhwa"),
    Pair("martial arts", "martial-arts"),
    Pair("Masmorra", "masmorra"),
    Pair("Mecha", "mecha"),
    Pair("Medieval", "medieval"),
    Pair("Militar", "militar"),
    Pair("Mistério", "misterio"),
    Pair("Moderno", "moderno"),
    Pair("Monstro", "monstro"),
    Pair("Monstros", "monstros"),
    Pair("Murim", "murim"),
    Pair("Musculação", "musculacao"),
    Pair("máfia", "mafia"),
    Pair("Necromante", "necromante"),
    Pair("Novel", "novel"),
    Pair("One-shot", "one-shot"),
    Pair("Oneshot", "oneshot"),
    Pair("Overpower", "overpower"),
    Pair("overpowered", "overpowered"),
    Pair("Pets", "pets"),
    Pair("Psicológico", "psicologico"),
    Pair("Psicopata", "psicopata"),
    Pair("Realidade Virtual", "realidade-virtual"),
    Pair("Reencarnação", "reencarnacao"),
    Pair("Regresso em Busca de Vingança", "regresso-em-busca-de-vinganca"),
    Pair("Regressão", "regressao"),
    Pair("regressão de idade", "regressao-de-idade"),
    Pair("Retorno", "retorno"),
    Pair("revange", "revange"),
    Pair("Revenge", "revenge"),
    Pair("Revenge Quest", "revenge-quest"),
    Pair("Romance", "romance"),
    Pair("Seinen", "seinen"),
    Pair("Shoujo", "shoujo"),
    Pair("Shounen", "shounen"),
    Pair("Shounen Ai", "shounen-ai"),
    Pair("Sistema", "sistema"),
    Pair("Sistema de Jogo", "sistema-de-jogo"),
    Pair("Slice of Life", "slice-of-life"),
    Pair("Sobrenatura", "sobrenatura"),
    Pair("Sobrenatural", "sobrenatural"),
    Pair("Sobrevivência", "sobrevivencia"),
    Pair("Super Poderes", "super-poderes"),
    Pair("SuperPoder", "superpoder"),
    Pair("Suspense", "suspense"),
    Pair("Tela de Sistema", "tela-de-sistema"),
    Pair("Terror", "terror"),
    Pair("teste", "teste"),
    Pair("Thriller", "thriller"),
    Pair("Time Manipulation", "time-manipulation"),
    Pair("Tragédia", "tragedia"),
    Pair("Tragédias", "tragedias"),
    Pair("Transmigração", "transmigracao"),
    Pair("Viagem no Tempo", "viagem-no-tempo"),
    Pair("Vida escolar", "vida-escolar"),
    Pair("Vingança", "vinganca"),
    Pair("Violência", "violencia"),
    Pair("Vizinhos", "vizinhos"),
    Pair("Volta no Tempo", "volta-no-tempo"),
    Pair("Zumbi", "zumbi"),
)
