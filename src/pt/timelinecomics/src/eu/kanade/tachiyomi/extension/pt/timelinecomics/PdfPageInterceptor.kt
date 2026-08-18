package eu.kanade.tachiyomi.extension.pt.timelinecomics

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import java.io.File
import java.io.IOException

class PdfPageInterceptor(private val cacheDir: File) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url

        if (url.host == "127.0.0.1" && url.pathSegments.firstOrNull() == "pdf-page") {
            val cacheKey = url.pathSegments.getOrNull(1)
                ?: throw IOException("Chave de cache do PDF não fornecida")
            val pageIndex = url.pathSegments.getOrNull(2)?.toIntOrNull()
                ?: throw IOException("Índice de página inválido")

            val file = File(cacheDir, "$cacheKey.pdf")
            if (!file.exists() || file.length() == 0L) {
                throw IOException("Arquivo PDF não encontrado no cache")
            }

            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    if (pageIndex < 0 || pageIndex >= renderer.pageCount) {
                        throw IOException("Índice de página fora dos limites: $pageIndex (total: ${renderer.pageCount})")
                    }

                    renderer.openPage(pageIndex).use { page ->
                        val scale = minOf(2.5f, 1600f / page.width)
                        val width = (page.width * scale).toInt()
                        val height = (page.height * scale).toInt()

                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        try {
                            val canvas = Canvas(bitmap)
                            canvas.drawColor(Color.WHITE)

                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                            val buffer = Buffer()
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, buffer.outputStream())

                            val body = buffer.asResponseBody("image/jpeg".toMediaType())

                            Response.Builder()
                                .code(200)
                                .protocol(Protocol.HTTP_1_1)
                                .request(request)
                                .message("OK")
                                .body(body)
                                .build()
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
        }

        return chain.proceed(request)
    }
}
