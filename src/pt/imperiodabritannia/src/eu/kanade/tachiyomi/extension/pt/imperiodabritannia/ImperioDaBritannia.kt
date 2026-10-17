package eu.kanade.tachiyomi.extension.pt.imperiodabritannia

import eu.kanade.tachiyomi.multisrc.mangotheme.MangoTheme
import keiyoushi.annotation.Source
import okhttp3.Headers
import okhttp3.OkHttpClient

@Source
abstract class ImperioDaBritannia : MangoTheme() {

    override val cdnUrl = "https://cdn.imperiodabritannia.net"

    override val encryptionKey = "mangotoons_encryption_key_2025"

    override val webMangaPathSegment = "manga"

    override val apiUrl = "https://api.${baseUrl.substringAfterLast("/")}/api"

    override fun Headers.Builder.configureHeaders(): Headers.Builder = addMangoThemeHeaders()
        .set("X-Brit-Cache", "true")
        .set("X-Noencryptionbritta", "1")

    private val apiTokenInterceptor by lazy {
        ImperioDaBritanniaTokenInterceptor(
            baseUrlProvider = { baseUrl },
            apiUrlProvider = { apiUrl },
            clientProvider = { client },
        )
    }

    override fun OkHttpClient.Builder.configureSubclassClient(): OkHttpClient.Builder = addInterceptor(apiTokenInterceptor)

    override fun getStatusFilterOptions() = ImperioDaBritanniaFilters.statusOptions

    override fun getFormatFilterOptions() = ImperioDaBritanniaFilters.formatOptions

    override fun getTagFilterOptions() = ImperioDaBritanniaFilters.tagOptions
}
