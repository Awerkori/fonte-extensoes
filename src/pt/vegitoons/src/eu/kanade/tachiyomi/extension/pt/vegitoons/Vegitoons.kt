package eu.kanade.tachiyomi.extension.pt.vegitoons

import eu.kanade.tachiyomi.multisrc.greenshit.GreenShit
import eu.kanade.tachiyomi.multisrc.greenshit.GreenShitMangaDto
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.annotation.Source
import keiyoushi.utils.parseAs
import rx.Observable

@Source
abstract class Vegitoons : GreenShit() {
    override val apiUrl = "https://api.vegitoons.black"
    override val cdnUrl = "https://cdn.vegitoons.black"
    override val cdnApiUrl = "https://api.vegitoons.black/cdn"
    override val scanId = "1"
    override val rateLimitPerSecond = 5

    private var cachedDetails: CachedDetails? = null
    private var inFlightDetails: InFlightDetails? = null

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = detailsDto(manga)
        .map { it.toSManga(cdnApiUrl, isDetails = true) }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = detailsDto(manga)
        .map {
            it.chapters.map { chapter -> chapter.toSChapter() }
                .distinctBy(SChapter::url)
                .sortedByDescending(SChapter::chapter_number)
        }

    @Synchronized
    private fun detailsDto(manga: SManga): Observable<GreenShitMangaDto> {
        val now = System.currentTimeMillis()
        cachedDetails?.takeIf { it.url == manga.url && now < it.expiresAt }?.let {
            return Observable.just(it.dto)
        }
        inFlightDetails?.takeIf { it.url == manga.url }?.let { return it.observable }

        val observable = client.newCall(mangaDetailsRequest(manga)).asObservableSuccess()
            .map { it.parseAs<GreenShitMangaDto>() }
            .doOnNext { dto -> cacheDetails(manga.url, dto) }
            .doOnCompleted { clearInFlight(manga.url) }
            .doOnError { clearInFlight(manga.url) }
            .replay(1)
            .refCount()
        inFlightDetails = InFlightDetails(manga.url, observable)
        return observable
    }

    @Synchronized
    private fun cacheDetails(url: String, dto: GreenShitMangaDto) {
        cachedDetails = CachedDetails(url, dto, System.currentTimeMillis() + DETAILS_CACHE_TTL)
    }

    @Synchronized
    private fun clearInFlight(url: String) {
        if (inFlightDetails?.url == url) inFlightDetails = null
    }

    private class CachedDetails(
        val url: String,
        val dto: GreenShitMangaDto,
        val expiresAt: Long,
    )

    private class InFlightDetails(
        val url: String,
        val observable: Observable<GreenShitMangaDto>,
    )

    private companion object {
        const val DETAILS_CACHE_TTL = 30_000L
    }
}
