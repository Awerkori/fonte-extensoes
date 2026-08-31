package eu.kanade.tachiyomi.extension.pt.lycantoons

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import keiyoushi.utils.applicationContext
import keiyoushi.utils.toJsonString
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

const val REUSE_TIMEOUT_MS = 30 * 1000L // 30s

// proxy Request through WebView since OkHttp gets 403 and fails Cloudflare TLS signature checks
class WebViewInterceptor(val baseUrl: String, private val userAgent: String?) : Interceptor {

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var destroyWv: Runnable? = null
    private var latch: CountDownLatch? = null
    private var result: FetchResult? = null
    private var errorMessage: Throwable? = null
    private var sawCloudflareChallenge = false

    var hasErrored = false

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val url = req.url.toString()
        if (url.contains("cdn.lycantoons.com")) {
            return chain.proceed(req)
        }
        val requestBody = if (req.method == "POST") {
            val buffer = Buffer()
            req.body!!.writeTo(buffer)
            buffer.readUtf8()
        } else {
            null
        }

        var resultData = fetchViaJs(url, req.method, req.headers, requestBody)
        var challengeDetected = resultData.result.hasCloudflareChallenge()

        if (resultData.result == "HTTP 403" || challengeDetected) {
            hasErrored = true
            resultData = fetchViaJs(url, req.method, req.headers, requestBody)
            challengeDetected = challengeDetected || resultData.result.hasCloudflareChallenge()
        }

        if (resultData.success) {
            hasErrored = false
        }

        if (!resultData.success) {
            if (challengeDetected) throw CloudflareBlockedException()
            throw IOException("[WebView]: " + resultData.result)
        }

        val resultConentType = resultData.contentType ?: "text/html"
        return resultData.result.toResponse(req, resultConentType)
    }

    private val bridgeName = "Lycan_Bridge"
    private var cachedWv: WebView? = null

    private val globalWebView: WebView
        get() {
            destroyWv?.let { mainHandler.removeCallbacks(it) }

            if (cachedWv == null) {
                cachedWv = WebView(applicationContext).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        userAgentString = userAgent
                        loadsImagesAutomatically = true
                        blockNetworkImage = false
                    }

                    addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun passResult(data: String, contentType: String?) {
                                result = FetchResult(true, data, contentType)
                                latch?.countDown()
                            }

                            @JavascriptInterface
                            fun passError(error: String) {
                                result = FetchResult(false, error)
                                latch?.countDown()
                            }

                            @JavascriptInterface
                            fun passChallenge() {
                                sawCloudflareChallenge = true
                            }
                        },
                        bridgeName,
                    )
                }
            }

            destroyWv = Runnable {
                cachedWv?.destroy()
                cachedWv = null
                destroyWv = null
            }.also {
                mainHandler.postDelayed(it, REUSE_TIMEOUT_MS)
            }

            return cachedWv!!
        }

    @Synchronized
    private fun fetchViaJs(
        url: String,
        method: String,
        headers: Headers,
        requestBody: String?,
    ): FetchResult {
        latch = CountDownLatch(1)
        result = null
        errorMessage = null
        sawCloudflareChallenge = false

        val isRsc = "/series/" in url

        mainHandler.post {
            try {
                val webView = globalWebView
                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? = if (!isRsc || "/series/" in request.url.toString()) {
                        null
                    } else {
                        WebResourceResponse(null, null, null)
                    }

                    override fun onReceivedHttpError(
                        view: WebView,
                        request: WebResourceRequest,
                        errorResponse: WebResourceResponse,
                    ) {
                        if (request.isForMainFrame && errorResponse.statusCode != 403) {
                            result = FetchResult(false, "HTTP ${errorResponse.statusCode}")
                            latch?.countDown()
                        }
                    }

                    override fun onPageFinished(view: WebView, pageUrl: String?) {
                        if (isRsc) {
                            if (result != null) return
                            view.evaluateJavascript(
                                """
                            (function() {
                                const isCloudflare = document.title === 'Just a moment...' || document.querySelector('span#challenge-error-text') != null || document.querySelector('div#cf-please-wait') != null || document.querySelector('div#challenge-spinner') != null;
                                if (isCloudflare) {
                                    window.$bridgeName.passChallenge();
                                    return;
                                }
                                window.$bridgeName.passResult(document.documentElement.outerHTML, document.contentType);
                            })();
                                """.trimIndent(),
                                null,
                            )
                            return
                        }

                        val jsScript = run {
                            val jsHeaders = buildMap {
                                headers.names().forEach { name ->
                                    put(name, headers[name])
                                }
                            }.toJsonString()

                            val jsRequestBody = if (requestBody != null) "body: `$requestBody`," else ""
                            """
                            (function() {
                                const isCloudflare = document.title === 'Just a moment...' || document.querySelector('span#challenge-error-text') != null || document.querySelector('div#cf-please-wait') != null || document.querySelector('div#challenge-spinner') != null;
                                if (isCloudflare) {
                                    window.$bridgeName.passChallenge();
                                    return;
                                }

                                let contentType;

                                fetch('$url', {
                                    method: '$method',
                                    credentials: 'include',
                                    headers: $jsHeaders,
                                    $jsRequestBody
                                })
                                .then(async res => {
                                    contentType = res.headers.get('content-type');
                                    const text = await res.text();
                                    if (!res.ok) {
                                        const challengeStatus = res.status === 403 || res.status === 429 || res.status === 503;
                                        const challengeBody = /Just a moment|cf-chl|challenge-platform|cf-turnstile|__cf_chl|Checking your browser|Verify you are human/i.test(text);
                                        throw new Error(challengeStatus && challengeBody ? 'CLOUDFLARE_CHALLENGE' : 'HTTP ' + res.status);
                                    }
                                    return text;
                                })
                            .then(text => window.$bridgeName.passResult(text, contentType))
                                .catch(err => window.$bridgeName.passError(err.message));
                            })();
                            """.trimIndent()
                        }

                        view.evaluateJavascript(jsScript, null)
                    }
                }

                if (isRsc) {
                    val cleanUrl = url.substringBefore("&_rsc=").substringBefore("?_rsc=")
                    webView.loadUrl(cleanUrl)
                    return@post
                }

                if (hasErrored) {
                    webView.loadUrl(baseUrl)
                    return@post
                }

                val pageHtml = " "
                webView.loadDataWithBaseURL(baseUrl, pageHtml, "text/html", "utf-8", null)
            } catch (e: Throwable) {
                errorMessage = e
                latch?.countDown()
            }
        }

        latch?.await(15, TimeUnit.SECONDS)

        return result ?: if (sawCloudflareChallenge) {
            FetchResult(false, "CLOUDFLARE_CHALLENGE")
        } else {
            FetchResult(false, (errorMessage ?: "Timed out").toString())
        }
    }

    private fun String.toResponse(request: Request, contentType: String): Response = this.toByteArray(Charsets.UTF_8).toResponse(request, contentType)

    private fun ByteArray.toResponse(request: Request, contentType: String): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .header("Content-Type", contentType)
        .body(this.toResponseBody(contentType.toMediaTypeOrNull()))
        .build()
}

class FetchResult(
    val success: Boolean,
    val result: String,
    val contentType: String? = null,
)

internal const val CLOUDFLARE_MESSAGE =
    "Cloudflare bloqueou o acesso.\n\n" +
        "Abra esta obra pela WebView (ícone do globo), conclua a verificação do Cloudflare e depois tente novamente."

internal class CloudflareBlockedException(cause: Throwable? = null) : IOException(CLOUDFLARE_MESSAGE, cause)

internal fun String.hasCloudflareChallenge(): Boolean = CLOUDFLARE_MARKERS.any { contains(it, ignoreCase = true) }

internal fun Throwable.isCloudflareFailure(): Boolean = generateSequence(this) { it.cause }
    .mapNotNull(Throwable::message)
    .any(String::hasCloudflareChallenge)

private val CLOUDFLARE_MARKERS = listOf(
    "CLOUDFLARE_CHALLENGE",
    "Just a moment",
    "cf-chl",
    "challenge-platform",
    "cf-turnstile",
    "__cf_chl",
    "Checking your browser",
    "Verify you are human",
)
