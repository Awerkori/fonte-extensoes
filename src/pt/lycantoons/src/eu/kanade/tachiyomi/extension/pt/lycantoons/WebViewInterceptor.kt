package eu.kanade.tachiyomi.extension.pt.lycantoons

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Base64
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
    private var mainFrameHttpError: Int? = null
    private var challengeDetected = false

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val url = req.url.toString()
        val isImage = url.contains("/cdn")

        val requestBody = if (req.method == "POST") {
            val buffer = Buffer()
            req.body!!.writeTo(buffer)
            buffer.readUtf8()
        } else {
            null
        }

        val resultData = fetchViaJs(url, req.method, req.headers, requestBody, isImage)
        if (!resultData.success) throw IOException(resultData.toErrorMessage())

        val resultConentType = resultData.contentType ?: "text/html"
        return if (isImage) {
            Base64.decode(resultData.result, Base64.DEFAULT).toResponse(req, resultConentType)
        } else {
            resultData.result.toResponse(req, resultConentType)
        }
    }

    private val bridgeName = "Lycan_Bridge"
    private var cachedWv: WebView? = null
    private var accessTime = 0L

    private val globalWebView: WebView
        get() {
            destroyWv?.let { mainHandler.removeCallbacks(it) }

            if (cachedWv == null) {
                cachedWv = WebView(applicationContext).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        userAgentString = userAgent
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
                            fun challengeDetected() {
                                this@WebViewInterceptor.challengeDetected = true
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
        isImage: Boolean,
    ): FetchResult {
        latch = CountDownLatch(1)
        result = null
        errorMessage = null
        mainFrameHttpError = null
        challengeDetected = false

        val isRsc = headers["RSC"] == "1"
        val isApiGet = method == "GET" && url.startsWith("$baseUrl/api/")

        mainHandler.post {
            try {
                val webView = globalWebView
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                        mainFrameHttpError = null
                    }

                    override fun onReceivedHttpError(
                        view: WebView,
                        request: WebResourceRequest,
                        errorResponse: WebResourceResponse,
                    ) {
                        if ((isRsc || isApiGet) && request.isForMainFrame) {
                            mainFrameHttpError = errorResponse.statusCode
                        }
                    }

                    override fun onPageFinished(view: WebView, pageUrl: String?) {
                        if (isRsc || isApiGet) {
                            if (result != null) return
                            view.evaluateJavascript(
                                """
                            (function() {
                                if (document.title === 'Just a moment...' ||
                                    document.querySelector('.main-wrapper #challenge-error-text')) {
                                    window.$bridgeName.challengeDetected();
                                    return;
                                }
                                ${mainFrameHttpError?.let { "window.$bridgeName.passError('HTTP $it'); return;" }.orEmpty()}
                                if (document.title.startsWith('Bloqueado')) {
                                    window.$bridgeName.passError('HTTP 403');
                                    return;
                                }
                                if (document.title.startsWith('404')) {
                                    window.$bridgeName.passError('HTTP 404');
                                    return;
                                }
                                const content = ${if (isApiGet) "document.body.innerText" else "document.documentElement.outerHTML"};
                                window.$bridgeName.passResult(content, document.contentType);
                            })();
                                """.trimIndent(),
                                null,
                            )
                            return
                        }

                        val jsScript = if (isImage) {
                            """
                            (function() {
                                const img = document.getElementById('_image');
                                const toBase64 = (data, type) => {
                                    if (data instanceof Blob) {
                                        const reader = new FileReader();
                                        reader.onload = () => window.$bridgeName.passResult(btoa(reader.result), type);
                                        reader.onerror = () => window.$bridgeName.passError('Reader error');
                                        reader.readAsBinaryString(data);
                                    } else {
                                        window.$bridgeName.passResult(data.toDataURL('image/jpeg', 0.8), 'image/jpeg');
                                    }
                                };
                                fetch(img.src, { cache: 'force-cache' })    // refech url to get compressed version
                                    .then(r => r.blob())
                                    .then(b => toBase64(b, b.type))
                                    .catch(() => {                         // Fallback to canvas just in case if webivew acts up
                                        const canvas = document.createElement('canvas');
                                        canvas.width = img.naturalWidth;
                                        canvas.height = img.naturalHeight;
                                        canvas.getContext('2d').drawImage(img, 0, 0);
                                        toBase64(canvas);
                                    });
                            })();
                            """.trimIndent()
                        } else {
                            val jsHeaders = buildMap {
                                headers.names().forEach { name ->
                                    put(name, headers[name])
                                }
                            }.toJsonString()

                            val jsRequestBody = if (requestBody != null) "body: `$requestBody`," else ""
                            """
                            (function() {
                                let contentType;

                                fetch('$url', {
                                    method: '$method',
                                    credentials: 'include',
                                    headers: $jsHeaders,
                                    $jsRequestBody
                                })
                                .then(res => {
                                if (!res.ok) throw new Error('HTTP ' + res.status);
                                contentType = res.headers.get('content-type')
                                return res.text();
                            })
                            .then(text => window.$bridgeName.passResult(text, contentType))
                                .catch(err => window.$bridgeName.passError(err.message));
                            })();
                            """.trimIndent()
                        }

                        view.evaluateJavascript(jsScript, null)
                    }
                }

                if (isRsc || isApiGet) {
                    webView.loadUrl(if (isRsc) url.substringBefore('?') else url)
                    return@post
                }

                val pageHtml = if (isImage) "<html><body><img id='_image' src='$url'/></body></html>" else " "
                webView.loadDataWithBaseURL(baseUrl, pageHtml, "text/html", "utf-8", null)
            } catch (e: Throwable) {
                errorMessage = e
                latch?.countDown()
            }
        }

        latch?.await(
            if (isImage) {
                10
            } else if (isRsc || isApiGet) {
                20
            } else {
                5
            },
            TimeUnit.SECONDS,
        )

        return result ?: FetchResult(false, if (challengeDetected) "CLOUDFLARE" else (errorMessage ?: "Timed out").toString())
    }

    private fun FetchResult.toErrorMessage(): String = when {
        result == "HTTP 401" -> "A Lycan Toons exige login para acessar este conteúdo. Entre pelo WebView e tente novamente."
        result == "CLOUDFLARE" -> "O site exige verificação. Abra no WebView, conclua a verificação e tente novamente."
        result == "HTTP 403" -> "O site bloqueou a requisição da extensão."
        result == "HTTP 404" -> "O conteúdo não foi encontrado. O site pode ter alterado ou removido o endereço."
        result == "HTTP 429" -> "A Lycan Toons limitou temporariamente as requisições. Tente novamente mais tarde."
        result.matches(Regex("HTTP 5\\d\\d")) -> "A Lycan Toons está com uma falha temporária ($result)."
        else -> "Falha ao acessar a Lycan Toons: $result"
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
