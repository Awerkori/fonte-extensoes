package eu.kanade.tachiyomi.extension.pt.geasscomics

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import java.io.IOException

internal class ImageInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        val response = chain.proceed(request)
        if (url.host != "cdn.geasscomics.xyz" || !url.encodedPath.startsWith("/pages/")) return response
        val metadata = url.fragment?.takeIf { it.startsWith("geass:") }?.removePrefix("geass:")
        val scramble = Scramble.parse(metadata) ?: return response
        if (!response.isSuccessful || response.body.contentType()?.type != "image") return response

        return response.use {
            val bitmap = BitmapFactory.decodeStream(
                response.body.byteStream(),
                null,
                BitmapFactory.Options().apply { inMutable = true },
            ) ?: throw IOException("Não foi possível decodificar a página da Geass Comics")
            val output = Buffer()
            try {
                restore(bitmap, scramble)
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output.outputStream())) {
                    throw IOException("Não foi possível reconstruir a página da Geass Comics")
                }
            } finally {
                bitmap.recycle()
            }
            response.newBuilder()
                .removeHeader("Content-Encoding")
                .removeHeader("Content-Length")
                .header("Content-Type", "image/png")
                .body(output.asResponseBody("image/png".toMediaType(), output.size))
                .build()
        }
    }

    private fun restore(bitmap: Bitmap, scramble: Scramble) {
        val tile = scramble.tile
        val columns = bitmap.width / tile
        scramble.restore(
            bitmap.width,
            bitmap.height,
            read = { index, pixels ->
                bitmap.getPixels(pixels, 0, tile, index % columns * tile, index / columns * tile, tile, tile)
            },
            write = { index, pixels ->
                bitmap.setPixels(pixels, 0, tile, index % columns * tile, index / columns * tile, tile, tile)
            },
        )
    }
}
