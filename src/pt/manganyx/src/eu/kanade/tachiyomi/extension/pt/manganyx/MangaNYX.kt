package eu.kanade.tachiyomi.extension.pt.manganyx

import eu.kanade.tachiyomi.multisrc.aurora.Aurora
import keiyoushi.annotation.Source
import keiyoushi.network.addCookie
import keiyoushi.network.rateLimit
import okhttp3.OkHttpClient
import kotlin.time.Duration.Companion.seconds

@Source
abstract class MangaNYX : Aurora() {
    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(3, 1.seconds)
        .addCookie("mnx_adulto" to "1")
        .addInterceptor(ReaderOriginInterceptor { baseUrl })
}
