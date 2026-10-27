package eu.kanade.tachiyomi.extension.pt.mangaverde

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

object MangaVerdeFilters {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun get(data: JsonElement?, showNsfw: Boolean): FilterList {
        val genres = parseGenres(data)
        val filterList = mutableListOf<Filter<*>>(
            SortFilter(),
            OrderFilter(),
            TypeFilter(),
            StatusFilter(),
            CensorshipFilter(),
        )

        if (showNsfw) {
            filterList.add(AgeFilter())
        }

        filterList.add(GenreFilter(genres))
        return FilterList(filterList)
    }

    private fun parseGenres(data: JsonElement?): List<Pair<String, String>> {
        if (data != null) {
            try {
                val list = json.decodeFromJsonElement<List<MangaVerdeGenreDto>>(data)
                val parsed = list.mapNotNull {
                    val name = it.name?.trim()?.ifEmpty { null }
                    val slug = it.slug?.trim()?.ifEmpty { null }
                    if (name != null && slug != null) Pair(name, slug) else null
                }
                if (parsed.isNotEmpty()) {
                    return listOf(Pair("Todos os gêneros", "ALL")) + parsed
                }
            } catch (_: Exception) {}
        }
        return STATIC_GENRES
    }

    val STATIC_GENRES = listOf(
        Pair("Todos os gêneros", "ALL"),
        Pair("Ação", "action"),
        Pair("Adaptação", "adaptation"),
        Pair("Adulto (+18)", "adult"),
        Pair("Artes Marciais", "martial-arts"),
        Pair("Aventura", "adventure"),
        Pair("Comédia", "comedy"),
        Pair("Culinária", "cooking"),
        Pair("Demônios", "demons"),
        Pair("Drama", "drama"),
        Pair("Ecchi", "ecchi"),
        Pair("Escolar", "school-life"),
        Pair("Espaço", "space"),
        Pair("Esportes", "sports"),
        Pair("Fantasia", "fantasy"),
        Pair("Ficção Científica", "sci-fi"),
        Pair("Full Color", "full-color"),
        Pair("Gore", "gore"),
        Pair("Harém", "harem"),
        Pair("Histórico", "historical"),
        Pair("Horror", "horror"),
        Pair("Isekai", "isekai"),
        Pair("Jogo", "game"),
        Pair("Long Strip", "long-strip"),
        Pair("Magia", "magic"),
        Pair("Mecha", "mecha"),
        Pair("Militar", "military"),
        Pair("Mistério", "mystery"),
        Pair("Monstros", "monsters"),
        Pair("Música", "music"),
        Pair("Policial", "police"),
        Pair("Psicológico", "psychological"),
        Pair("Pós-Apocalíptico", "post-apocalyptic"),
        Pair("Reencarnação", "reincarnation"),
        Pair("Regressão", "regression"),
        Pair("Romance", "romance"),
        Pair("Samurai", "samurai"),
        Pair("Sci-Fi", "sci-fi"),
        Pair("Seinen", "seinen"),
        Pair("Shoujo", "shoujo"),
        Pair("Shounen", "shounen"),
        Pair("Slice of Life", "slice-of-life"),
        Pair("Sobrenatural", "supernatural"),
        Pair("Superpoderes", "super-power"),
        Pair("Supervivência", "survival"),
        Pair("Suspense", "thriller"),
        Pair("Tragédia", "tragedy"),
        Pair("Vampiros", "vampires"),
        Pair("Vida Escolar", "school-life"),
        Pair("Vilã", "villainess"),
        Pair("Web Comic", "web-comic"),
        Pair("Webtoon", "webtoon"),
        Pair("Zumbis", "zombies"),
    )
}

open class SelectFilter(
    name: String,
    private val options: List<Pair<String, String>>,
    defaultIndex: Int = 0,
) : Filter.Select<String>(name, options.map { it.first }.toTypedArray(), defaultIndex) {
    val selected: String
        get() = options.getOrNull(state)?.second ?: ""
}

class SortFilter :
    SelectFilter(
        "Ordenar por",
        listOf(
            Pair("Recentes", "recent"),
            Pair("Populares", "popular"),
            Pair("Melhor avaliados", "rating"),
            Pair("Título (A-Z)", "title"),
        ),
        0,
    )

class OrderFilter :
    SelectFilter(
        "Ordem",
        listOf(
            Pair("Decrescente (Maior ➔ Menor)", "desc"),
            Pair("Crescente (Menor ➔ Maior)", "asc"),
        ),
        0,
    )

class TypeFilter :
    SelectFilter(
        "Formato",
        listOf(
            Pair("Todos os formatos", "ALL"),
            Pair("Mangá", "MANGA"),
            Pair("Manhwa", "MANHWA"),
            Pair("Manhua", "MANHUA"),
            Pair("Comic / HQ", "COMIC"),
        ),
        0,
    )

class StatusFilter :
    SelectFilter(
        "Status",
        listOf(
            Pair("Todos os status", "ALL"),
            Pair("Em andamento", "ONGOING"),
            Pair("Completo", "COMPLETED"),
            Pair("Em hiato", "HIATUS"),
            Pair("Cancelado", "CANCELLED"),
        ),
        0,
    )

class CensorshipFilter :
    SelectFilter(
        "Censura",
        listOf(
            Pair("Todas as censuras", "ALL"),
            Pair("Sem censura", "UNCENSORED"),
            Pair("Com censura", "CENSORED"),
        ),
        0,
    )

class AgeFilter :
    SelectFilter(
        "Classificação indicativa",
        listOf(
            Pair("Todas as classificações", "ALL"),
            Pair("Apenas +18", "ONLY_NSFW"),
            Pair("Livre (+18 desativado)", "SAFE_ONLY"),
        ),
        0,
    )

class GenreFilter(options: List<Pair<String, String>>) :
    SelectFilter(
        "Gênero",
        options,
        0,
    )
