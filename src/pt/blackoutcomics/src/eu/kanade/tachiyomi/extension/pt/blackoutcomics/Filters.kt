package eu.kanade.tachiyomi.extension.pt.blackoutcomics

import eu.kanade.tachiyomi.source.model.Filter

class StatusFilter :
    Filter.Select<String>(
        "Status",
        STATUS_LIST.map { it.first }.toTypedArray(),
    ) {
    fun toUriPart(): String = STATUS_LIST[state].second

    companion object {
        private val STATUS_LIST = arrayOf(
            "Todos os Status" to "",
            "Completos" to "completed",
        )
    }
}

class GenreFilter :
    Filter.Select<String>(
        "Gênero",
        GENRE_LIST.map { it.first }.toTypedArray(),
    ) {
    fun toUriPart(): String = GENRE_LIST[state].second

    companion object {
        private val GENRE_LIST = arrayOf(
            "Todos" to "",
            "Ação" to "Ação",
            "Adaptação" to "Adaptação",
            "Adulto" to "Adulto",
            "Altamente Explícito" to "Altamente Explícito",
            "Amante" to "Amante",
            "Amigos de Infância" to "Amigos de Infância",
            "Animais" to "Animais",
            "Anti-Herói" to "Anti-Herói",
            "Ao ar livre" to "Ao ar livre",
            "Artes Marciais" to "Artes Marciais",
            "Assédio" to "Assédio",
            "Atleta" to "Atleta",
            "Aventura" to "Aventura",
            "Beisebol" to "Beisebol",
            "Casada" to "Casada",
            "Caso Secreto" to "Caso Secreto",
            "Comédia" to "Comédia",
            "Comic" to "Comic",
            "Conquista" to "Conquista",
            "Cozinha" to "Cozinha",
            "Crime" to "Crime",
            "Cripto" to "Cripto",
            "Cunhada" to "Cunhada",
            "Delinquentes" to "Delinquentes",
            "Desejo" to "Desejo",
            "Desejos ocultos" to "Desejos ocultos",
            "Diferença de Idade" to "Diferença de Idade",
            "Domesticação" to "Domesticação",
            "Dominação" to "Dominação",
            "Drama" to "Drama",
            "Ecchi" to "Ecchi",
            "Empregada" to "Empregada",
            "Escrava" to "Escrava",
            "Escritório" to "Escritório",
            "Esportes" to "Esportes",
            "Estratégia" to "Estratégia",
            "Estudante universitário" to "Estudante universitário",
            "Faculdade" to "Faculdade",
            "Família real" to "Família real",
            "Fantasia" to "Fantasia",
            "Fantasia Moderna" to "Fantasia Moderna",
            "Fantasmas" to "Fantasmas",
            "Fetiche" to "Fetiche",
            "Ficção científica" to "Ficção científica",
            "Filha" to "Filha",
            "Forçado" to "Forçado",
            "Garotada" to "Garotada",
            "Garotas Mágicas" to "Garotas Mágicas",
            "Habilidades especiais" to "Habilidades especiais",
            "Hardcore" to "Hardcore",
            "Harém" to "Harém",
            "Harém reverso" to "Harém reverso",
            "Histórico" to "Histórico",
            "Humilhação" to "Humilhação",
            "Hypnose" to "Hypnose",
            "Incesto" to "Incesto",
            "Instinto" to "Instinto",
            "Irmã" to "Irmã",
            "Isekai" to "Isekai",
            "Jogo" to "Jogo",
            "Josei" to "Josei",
            "Maduro" to "Maduro",
            "Mãe" to "Mãe",
            "Magia" to "Magia",
            "Mangá" to "Mangá",
            "Manhwa" to "Manhwa",
            "Médico" to "Médico",
            "Meninas Monstro" to "Meninas Monstro",
            "Militar" to "Militar",
            "Milf" to "Milf",
            "Mistério" to "Mistério",
            "Moderno" to "Moderno",
            "Mulher casada" to "Mulher casada",
            "Música" to "Música",
            "Netorare/NTR" to "Netorare/NTR",
            "NTL" to "NTL",
            "NTR" to "NTR",
            "NTRLésbico" to "NTRLésbico",
            "Obsessão" to "Obsessão",
            "Obscenidade" to "Obscenidade",
            "Olympus" to "Olympus",
            "Outro Mundo" to "Outro Mundo",
            "Peitões" to "Peitões",
            "Pirocão" to "Pirocão",
            "Pornhwa" to "Pornhwa",
            "Preferência Sexual" to "Preferência Sexual",
            "Primeiro Amor" to "Primeiro Amor",
            "Professor" to "Professor",
            "Professora" to "Professora",
            "Prostituição" to "Prostituição",
            "Psicológico" to "Psicológico",
            "Regressão" to "Regressão",
            "Relacao Mestre e Servo" to "Relacao Mestre e Servo",
            "Relação armada" to "Relação armada",
            "Romance" to "Romance",
            "Segredo" to "Segredo",
            "Segredos" to "Segredos",
            "Seinen" to "Seinen",
            "Sistema" to "Sistema",
            "SM/BDSM/SUB-DOM" to "SM/BDSM/SUB-DOM",
            "Sobrenatural" to "Sobrenatural",
            "Sobrevivência" to "Sobrevivência",
            "Sogra" to "Sogra",
            "Super Poder" to "Super Poder",
            "Tia" to "Tia",
            "Trabalhadores de Escritório" to "Trabalhadores de Escritório",
            "Traição" to "Traição",
            "Treinamento" to "Treinamento",
            "Triângulo Amoroso" to "Triângulo Amoroso",
            "Vaidade" to "Vaidade",
            "Vampiros" to "Vampiros",
            "Viagem no Tempo" to "Viagem no Tempo",
            "Vida Cotidiana" to "Vida Cotidiana",
            "Vida escolar" to "Vida escolar",
            "Vida Universitária" to "Vida Universitária",
            "Vingança" to "Vingança",
            "Violência" to "Violência",
            "Virada De Vida" to "Virada De Vida",
            "Virgem" to "Virgem",
            "Vôlei feminino" to "Vôlei feminino",
            "Webtoon" to "Webtoon",
        )
    }
}

class OrderFilter :
    Filter.Select<String>(
        "Ordem",
        ORDER_LIST.map { it.first }.toTypedArray(),
    ) {
    fun toUriPart(): String = ORDER_LIST[state].second

    companion object {
        private val ORDER_LIST = arrayOf("A-Z" to "") + ('A'..'Z').map { it.toString() to it.toString() }.toTypedArray()
    }
}
