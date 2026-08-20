package eu.kanade.tachiyomi.extension.pt.lycantoons

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
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

private const val TAG = "LycanToons"
private const val REUSE_TIMEOUT_MS = 60 * 1000L
private const val API_BRIDGE_NAME = "Lycan_Api_Bridge"
private const val IMG_BRIDGE_NAME = "Lycan_Img_Bridge"

class WebViewInterceptor(
    val baseUrl: String,
    private val userAgent: String?,
) : Interceptor {

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private var cachedApiWebView: WebView? = null
    private var destroyApiWvTask: Runnable? = null
    private var isApiPageReady = false

    private var cachedImgWebView: WebView? = null
    private var destroyImgWvTask: Runnable? = null
    private var isImgPageReady = false

    private val apiLock = Any()
    private val imgLock = Any()

    private var apiLatch: CountDownLatch? = null
    private var apiResult: FetchResult? = null
    private var apiError: Throwable? = null

    private var imgLatch: CountDownLatch? = null
    private var imgResult: FetchResult? = null
    private var imgError: Throwable? = null

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val url = req.url.toString()
        val isImage = url.contains("cdn.") || url.contains("/covers/") || url.contains("/capitulos/") ||
            url.endsWith(".webp") || url.endsWith(".png") || url.endsWith(".jpg") || url.endsWith(".jpeg")

        Log.d(TAG, "==> [Interceptor] ${req.method} $url (isImage=$isImage)")

        val requestBody = if (req.method == "POST" && req.body != null) {
            val buffer = Buffer()
            req.body!!.writeTo(buffer)
            buffer.readUtf8()
        } else {
            null
        }

        val resultData = if (isImage) {
            fetchImageViaWebView(url)
        } else {
            fetchApiViaWebView(url, req.method, req.headers, requestBody)
        }

        if (!resultData.success) {
            Log.e(TAG, "<== [Interceptor] Fetch failed for $url: ${resultData.result}")
            throw IOException(resultData.result)
        }

        val contentType = resultData.contentType ?: if (isImage) "image/jpeg" else "text/html; charset=UTF-8"
        Log.d(TAG, "<== [Interceptor] Fetch success for $url ($contentType, size=${resultData.result.length})")

        return if (isImage) {
            Base64.decode(resultData.result, Base64.DEFAULT).toResponse(req, contentType)
        } else {
            resultData.result.toResponse(req, contentType)
        }
    }

    private fun fetchApiViaWebView(
        url: String,
        method: String,
        headers: Headers,
        requestBody: String?,
    ): FetchResult = synchronized(apiLock) {
        apiLatch = CountDownLatch(1)
        apiResult = null
        apiError = null

        val isPageNavigation = method == "GET" && !url.contains("/api/")

        mainHandler.post {
            try {
                val webView = getOrCreateApiWebView()

                if (isPageNavigation) {
                    Log.d(TAG, "[ApiWebView] Direct page navigation for $url...")
                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, pageUrl: String?) {
                            Log.d(TAG, "[ApiWebView] onPageFinished: $pageUrl, extracting HTML...")
                            isApiPageReady = true
                            view.evaluateJavascript(
                                """
                                (function() {
                                    window.$API_BRIDGE_NAME.passResult(document.documentElement.outerHTML, 'text/html; charset=UTF-8');
                                })();
                                """.trimIndent(),
                                null,
                            )
                        }
                    }
                    webView.loadUrl(url)
                    return@post
                }

                val jsHeaders = headers.toSanitizedMap().toJsonString()
                val jsBody = if (requestBody != null) "body: ${requestBody.toJsonString()}," else ""

                val jsScript = """
                    (function() {
                        console.log('[LycanApi] Starting fetch: $url');
                        fetch('$url', {
                            method: '$method',
                            headers: $jsHeaders,
                            $jsBody
                        })
                        .then(async function(res) {
                            var ct = res.headers.get('content-type') || '';
                            var text = await res.text();
                            window.$API_BRIDGE_NAME.passResult(text, ct);
                        })
                        .catch(function(err) {
                            window.$API_BRIDGE_NAME.passError(err.message || 'Fetch error');
                        });
                    })();
                """.trimIndent()

                if (isApiPageReady) {
                    Log.d(TAG, "[ApiWebView] Reusing page context, evaluating JS fetch for $url...")
                    webView.evaluateJavascript(jsScript, null)
                } else {
                    Log.d(TAG, "[ApiWebView] Initializing with loadUrl($baseUrl)...")
                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, pageUrl: String?) {
                            Log.d(TAG, "[ApiWebView] onPageFinished: $pageUrl, evaluating JS fetch for $url...")
                            isApiPageReady = true
                            view.evaluateJavascript(jsScript, null)
                        }
                    }
                    webView.loadUrl(baseUrl)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "[ApiWebView] Exception during fetch setup: ${e.message}", e)
                apiError = e
                apiLatch?.countDown()
            }
        }

        val completed = apiLatch?.await(15, TimeUnit.SECONDS) == true
        if (!completed) {
            Log.e(TAG, "[ApiWebView] Timeout waiting for fetch: $url")
            return FetchResult(false, "Timeout ao carregar dados pela WebView: $url")
        }

        return apiResult ?: FetchResult(false, (apiError?.message ?: "Erro desconhecido na WebView"))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun getOrCreateApiWebView(): WebView {
        destroyApiWvTask?.let { mainHandler.removeCallbacks(it) }

        if (cachedApiWebView == null) {
            Log.d(TAG, "[ApiWebView] Creating new instance...")
            cachedApiWebView = WebView(applicationContext).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    if (!userAgent.isNullOrBlank()) {
                        userAgentString = userAgent
                    }
                }

                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun passResult(data: String, contentType: String?) {
                            Log.d(TAG, "[ApiBridge] passResult: contentType=$contentType, size=${data.length}, preview=${data.take(150)}")
                            apiResult = FetchResult(true, data, contentType)
                            apiLatch?.countDown()
                        }

                        @JavascriptInterface
                        fun passError(error: String) {
                            Log.e(TAG, "[ApiBridge] passError: $error")
                            apiResult = FetchResult(false, error)
                            apiLatch?.countDown()
                        }
                    },
                    API_BRIDGE_NAME,
                )
            }
            isApiPageReady = false
        }

        destroyApiWvTask = Runnable {
            Log.d(TAG, "[ApiWebView] Destroying idle instance.")
            cachedApiWebView?.destroy()
            cachedApiWebView = null
            destroyApiWvTask = null
            isApiPageReady = false
        }.also {
            mainHandler.postDelayed(it, REUSE_TIMEOUT_MS)
        }

        return cachedApiWebView!!
    }

    private fun fetchImageViaWebView(url: String): FetchResult = synchronized(imgLock) {
        imgLatch = CountDownLatch(1)
        imgResult = null
        imgError = null

        val cdnBaseUrl = "https://cdn.lycantoons.com"

        mainHandler.post {
            try {
                val webView = getOrCreateImgWebView()

                val jsScript = """
                    (function() {
                        var img = new Image();
                        img.onload = function() {
                            try {
                                var canvas = document.createElement('canvas');
                                canvas.width = img.naturalWidth;
                                canvas.height = img.naturalHeight;
                                var ctx = canvas.getContext('2d');
                                ctx.drawImage(img, 0, 0);
                                var dataUrl = canvas.toDataURL('image/jpeg', 0.9);
                                var base64 = (dataUrl || '').split(',')[1] || '';
                                window.$IMG_BRIDGE_NAME.passResult(base64, 'image/jpeg');
                            } catch(e) {
                                window.$IMG_BRIDGE_NAME.passError('Canvas error: ' + e.message);
                            }
                        };
                        img.onerror = function() {
                            window.$IMG_BRIDGE_NAME.passError('Image element failed to load');
                        };
                        img.src = '$url';
                    })();
                """.trimIndent()

                if (isImgPageReady) {
                    Log.d(TAG, "[ImgWebView] Reusing context, loading image in DOM for $url...")
                    webView.evaluateJavascript(jsScript, null)
                } else {
                    Log.d(TAG, "[ImgWebView] Initializing with loadDataWithBaseURL($cdnBaseUrl)...")
                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, pageUrl: String?) {
                            Log.d(TAG, "[ImgWebView] onPageFinished: $pageUrl, loading image $url...")
                            isImgPageReady = true
                            view.evaluateJavascript(jsScript, null)
                        }
                    }
                    webView.loadDataWithBaseURL(cdnBaseUrl, "<html><body></body></html>", "text/html", "utf-8", null)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "[ImgWebView] Exception: ${e.message}", e)
                imgError = e
                imgLatch?.countDown()
            }
        }

        val completed = imgLatch?.await(15, TimeUnit.SECONDS) == true
        if (!completed) {
            Log.e(TAG, "[ImgWebView] Timeout waiting for image fetch: $url")
            return FetchResult(false, "Timeout ao carregar imagem pela WebView: $url")
        }

        return imgResult ?: FetchResult(false, (imgError?.message ?: "Erro desconhecido na WebView"))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun getOrCreateImgWebView(): WebView {
        destroyImgWvTask?.let { mainHandler.removeCallbacks(it) }

        if (cachedImgWebView == null) {
            Log.d(TAG, "[ImgWebView] Creating new instance...")
            cachedImgWebView = WebView(applicationContext).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = true
                    allowContentAccess = true
                    if (!userAgent.isNullOrBlank()) {
                        userAgentString = userAgent
                    }
                }

                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun passResult(data: String, contentType: String?) {
                            Log.d(TAG, "[ImgBridge] passResult: contentType=$contentType, base64Length=${data.length}")
                            imgResult = FetchResult(true, data, contentType)
                            imgLatch?.countDown()
                        }

                        @JavascriptInterface
                        fun passError(error: String) {
                            Log.e(TAG, "[ImgBridge] passError: $error")
                            imgResult = FetchResult(false, error)
                            imgLatch?.countDown()
                        }
                    },
                    IMG_BRIDGE_NAME,
                )
            }
            isImgPageReady = false
        }

        destroyImgWvTask = Runnable {
            Log.d(TAG, "[ImgWebView] Destroying idle instance.")
            cachedImgWebView?.destroy()
            cachedImgWebView = null
            destroyImgWvTask = null
            isImgPageReady = false
        }.also {
            mainHandler.postDelayed(it, REUSE_TIMEOUT_MS)
        }

        return cachedImgWebView!!
    }

    private fun Headers.toSanitizedMap(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (i in 0 until size) {
            val name = name(i)
            val value = value(i)
            if (name.lowercase() !in FORBIDDEN_FETCH_HEADERS) {
                map[name] = value
            }
        }
        return map
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

    companion object {
        private val FORBIDDEN_FETCH_HEADERS = setOf(
            "user-agent", "referer", "host", "connection", "content-length",
            "origin", "sec-fetch-dest", "sec-fetch-mode", "sec-fetch-site",
            "sec-fetch-user", "sec-ch-ua", "sec-ch-ua-mobile", "sec-ch-ua-platform",
            "cookie", "accept-encoding", "keep-alive", "cache-control",
        )
    }
}

class FetchResult(
    val success: Boolean,
    val result: String,
    val contentType: String? = null,
)
