package eu.kanade.tachiyomi.extension.pt.mangasbrasuka

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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

const val REUSE_TIMEOUT_MS = 30 * 1000L // 30s

// proxy Request through WebView since OkHttp gets 403 and fails Cloudflare TLS signature checks
class WebViewInterceptor(val baseUrl: String, private val userAgent: String?) : Interceptor {

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var destroyWv: Runnable? = null
    private val requestCount = AtomicInteger(0)
    private val latches = ConcurrentHashMap<Int, CountDownLatch>()
    private val results = ConcurrentHashMap<Int, FetchResult>()
    private val errors = ConcurrentHashMap<Int, Throwable>()

    var hasErrored = false

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val url = req.url.toString()
        if (url.contains("app.mangasbrasuka.com.br") || url.contains("snipercache.com") || url.contains("/cdn/") || url.endsWith(".jpg") || url.endsWith(".png") || url.endsWith(".webp") || url.endsWith(".jpeg")) {
            return chain.proceed(req)
        }

        val isImage = false

        val requestBody = if (req.method == "POST") {
            val buffer = Buffer()
            req.body!!.writeTo(buffer)
            buffer.readUtf8()
        } else {
            null
        }

        var resultData = fetchViaJs(url, req.method, req.headers, requestBody, isImage)

        if (resultData.result == "HTTP 403") {
            hasErrored = !hasErrored
            resultData = fetchViaJs(url, req.method, req.headers, requestBody, isImage)
        }
        if (!resultData.success) throw IOException("[WebView]: " + resultData.result)

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
                            fun passResult(reqId: Int, data: String, contentType: String?) {
                                results[reqId] = FetchResult(true, data, contentType)
                                latches[reqId]?.countDown()
                            }

                            @JavascriptInterface
                            fun passError(reqId: Int, error: String) {
                                results[reqId] = FetchResult(false, error)
                                latches[reqId]?.countDown()
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
        val reqId = requestCount.incrementAndGet()
        val latch = CountDownLatch(1)
        latches[reqId] = latch
        results.remove(reqId)
        errors.remove(reqId)

        val isRsc = !isImage && !url.contains("/api/atfield/key")

        mainHandler.post {
            try {
                val webView = globalWebView
                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? = null

                    override fun onReceivedHttpError(
                        view: WebView,
                        request: WebResourceRequest,
                        errorResponse: WebResourceResponse,
                    ) {
                        if (request.isForMainFrame) {
                            results[reqId] = FetchResult(false, "HTTP ${errorResponse.statusCode}")
                            latch.countDown()
                        }
                    }

                    override fun onPageFinished(view: WebView, pageUrl: String?) {
                        if (isRsc) {
                            if (results[reqId] != null) return
                            view.evaluateJavascript(
                                """
                            (function() {
                                window.$bridgeName.passResult($reqId, document.documentElement.outerHTML, document.contentType);
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
                                        reader.onload = () => window.$bridgeName.passResult($reqId, btoa(reader.result), type);
                                        reader.onerror = () => window.$bridgeName.passError($reqId, 'Reader error');
                                        reader.readAsBinaryString(data);
                                    } else {
                                        window.$bridgeName.passResult($reqId, data.toDataURL('image/jpeg', 0.8), 'image/jpeg');
                                    }
                                };
                                fetch(img.src, { cache: 'force-cache' })
                                    .then(r => r.blob())
                                    .then(b => toBase64(b, b.type))
                                    .catch(() => {
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
                                    contentType = res.headers.get('content-type');
                                    return res.text();
                                })
                                .then(text => window.$bridgeName.passResult($reqId, text, contentType))
                                .catch(err => window.$bridgeName.passError($reqId, err.message));
                            })();
                            """.trimIndent()
                        }

                        view.evaluateJavascript(jsScript, null)
                    }
                }

                if (isRsc && !hasErrored) {
                    val urlToLoad = url.substringBefore("&_rsc=").substringBefore("?_rsc=")
                    webView.loadUrl(urlToLoad) // document fetch-dest and drop rsc
                    return@post
                }

                val pageHtml = if (isImage) "<html><body><img id='_image' src='$url'/></body></html>" else " "
                webView.loadDataWithBaseURL(baseUrl, pageHtml, "text/html", "utf-8", null)
            } catch (e: Throwable) {
                errors[reqId] = e
                latch.countDown()
            }
        }

        latch.await(if (isImage) 10 else 5, TimeUnit.SECONDS)

        return results[reqId] ?: FetchResult(false, (errors[reqId] ?: "Timed out").toString())
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

class FetchResult(val success: Boolean, val result: String, val contentType: String? = null)
