package eu.kanade.tachiyomi.extension.pt.manganyx

import eu.kanade.tachiyomi.multisrc.aurora.Aurora
import keiyoushi.annotation.Source
import okhttp3.OkHttpClient

@Source
abstract class MangaNYX : Aurora() {
    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = defaultClient()
        .addInterceptor(ReaderOriginInterceptor { baseUrl })
}
