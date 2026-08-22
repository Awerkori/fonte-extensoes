package eu.kanade.tachiyomi.extension.all.hentaihand

import eu.kanade.tachiyomi.multisrc.hentaihand.HentaiHand
import keiyoushi.annotation.Source
import okhttp3.OkHttpClient
import okhttp3.Request

@Source
abstract class HentaiHand : HentaiHand() {

    override fun popularMangaRequest(page: Int): Request {
        val request = super.popularMangaRequest(page)
        return request.newBuilder().url(request.url.newBuilder().setQueryParameter("duration", "day").build()).build()
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val request = super.latestUpdatesRequest(page)
        return request.newBuilder().url(request.url.newBuilder().removeAllQueryParameters("duration").build()).build()
    }

    override fun searchMangaRequest(page: Int, query: String, filters: eu.kanade.tachiyomi.source.model.FilterList): Request {
        val request = super.searchMangaRequest(page, query, filters)
        return request.newBuilder().url(
            request.url.newBuilder()
                .setQueryParameter("sort", "uploaded_at")
                .setQueryParameter("order", "desc")
                .removeAllQueryParameters("duration")
                .build(),
        ).build()
    }

    override val chapters = false

    override val hhLangId = when (lang) {
        "all" -> emptyList()
        "ja" -> listOf(3, 29)
        "en" -> listOf(2, 27)
        "zh" -> listOf(1, 50)
        "bg" -> listOf(4)
        "ceb" -> listOf(5, 44)
        "other" -> listOf(6)
        "tl" -> listOf(7, 55)
        "ar" -> listOf(8, 49)
        "el" -> listOf(9)
        "sr" -> listOf(10)
        "jv" -> listOf(11, 51)
        "uk" -> listOf(12, 46)
        "tr" -> listOf(13, 41)
        "fi" -> listOf(14, 54)
        "la" -> listOf(15)
        "mn" -> listOf(16)
        "eo" -> listOf(17, 47)
        "sk" -> listOf(18)
        "cs" -> listOf(19, 52)
        "ko" -> listOf(30, 39)
        "ru" -> listOf(31)
        "it" -> listOf(32)
        "es" -> listOf(33, 37)
        "th" -> listOf(35, 40)
        "fr" -> listOf(36)
        "id" -> listOf(38)
        "vi" -> listOf(42)
        "de" -> listOf(43)
        "pl" -> listOf(45)
        "hu" -> listOf(48)
        "nl" -> listOf(53)
        "hi" -> listOf(56)
        else -> emptyList()
    }

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor { authIntercept(it) }
        .build()
}
