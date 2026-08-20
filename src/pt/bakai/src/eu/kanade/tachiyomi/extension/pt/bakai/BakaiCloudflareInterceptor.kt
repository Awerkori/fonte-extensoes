package eu.kanade.tachiyomi.extension.pt.bakai

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import keiyoushi.utils.applicationContext
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Intercepts 403/404 Cloudflare challenges and resolves them via WebView.
 * Mirrors the behaviour of the reference APK (class g / f).
 */
class BakaiCloudflareInterceptor(private val userAgent: String?) : Interceptor {

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val cookieManager by lazy { CookieManager.getInstance() }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        if (response.code == 403 && isCloudflarePage(response, null)) {
            response.close()
            bypassCloudflare(request)
            return chain.proceed(request)
        }

        if (response.code == 404 && isCloudflarePage(response, "The page could not be found or has been removed")) {
            val cookie = cookieManager.getCookie(request.url.toString()) ?: ""
            if (!cookie.contains("cf_clearance")) {
                response.close()
                bypassCloudflare(request)
                return chain.proceed(request)
            }
        }

        return response
    }

    private fun isCloudflarePage(response: Response, extraKeyword: String?): Boolean {
        val server = response.header("Server") ?: ""
        val cfMitigated = response.header("cf-mitigated") ?: ""
        if (!server.contains("cloudflare", ignoreCase = true) && cfMitigated != "challenge") {
            return false
        }
        val body = response.peekBody(2048).string()
        return body.contains("Just a moment", ignoreCase = true) ||
            body.contains("challenge-running", ignoreCase = true) ||
            body.contains("cf-browser-verification", ignoreCase = true) ||
            (extraKeyword != null && body.contains(extraKeyword, ignoreCase = true))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun bypassCloudflare(request: Request) {
        val latch = CountDownLatch(1)
        val success = booleanArrayOf(false)
        val url = request.url.toString()

        mainHandler.post {
            val webView = WebView(applicationContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                if (!userAgent.isNullOrBlank()) {
                    settings.userAgentString = userAgent
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, pageUrl: String?) {
                        val cookie = cookieManager.getCookie(pageUrl ?: url) ?: ""
                        if (cookie.contains("cf_clearance")) {
                            success[0] = true
                            latch.countDown()
                            view.stopLoading()
                            view.destroy()
                        }
                    }

                    override fun onReceivedHttpError(
                        view: WebView,
                        resourceRequest: WebResourceRequest,
                        errorResponse: WebResourceResponse,
                    ) {
                        // ignore non-main-frame errors
                    }
                }
                loadUrl(url)
            }
        }

        latch.await(30, TimeUnit.SECONDS)
        if (!success[0]) {
            throw IOException("Cloudflare bypass falhou para Bakai. Tente 'Abrir no WebView' para resolver manualmente.")
        }
    }
}
