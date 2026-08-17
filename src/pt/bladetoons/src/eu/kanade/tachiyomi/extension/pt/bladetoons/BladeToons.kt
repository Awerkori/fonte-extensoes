package eu.kanade.tachiyomi.extension.pt.bladetoons

import android.app.Dialog
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.multisrc.mangotheme.MangoTheme
import keiyoushi.annotation.Source
import keiyoushi.utils.parseAs
import kotlinx.coroutines.runBlocking

@Source
abstract class BladeToons : MangoTheme() {

    override val cdnUrl = "https://cdn.bladetoons.com"

    override val encryptionKey = "abmPisXlFjOLVTnYhbYQTpkWJtOGKwVttzLqstfjRBNVaEtQYG"

    override val webMangaPathSegment = "obra"

    override val webUrlSalt = "mango-secret-salt-2024"

    override fun buildTimedWebMangaReference(mangaId: String, hash: String): String = "$mangaId$hash${mangaId.firstOrNull()?.toString().orEmpty()}"

    override fun getStatusFilterOptions() = BladeToonsFilters.statusOptions

    override fun getFormatFilterOptions() = BladeToonsFilters.formatOptions

    override fun getTagFilterOptions() = BladeToonsFilters.tagOptions

    // Site enforces loginObrigatorio=true; all protected endpoints require Authorization: Bearer.
    override val requiresLogin = true

    // Token is stored in localStorage["token"] by the site's frontend after login.
    @Synchronized
    override fun getToken(): String {
        val saved = preferences.getString(tokenPreferenceKey, "").orEmpty()
        if (saved.isNotEmpty()) return saved

        val fromStorage = runCatching {
            runBlocking { keiyoushi.utils.getLocalStorage(baseUrl, "token") }
        }.getOrNull().orEmpty()

        if (fromStorage.isNotEmpty()) {
            preferences.edit().putString(tokenPreferenceKey, fromStorage).apply()
            return fromStorage
        }

        return ""
    }

    override fun clearToken() {
        preferences.edit().remove(tokenPreferenceKey).apply()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val hasToken = preferences.getString(tokenPreferenceKey, "").orEmpty().isNotEmpty()

        lateinit var statusPref: Preference
        lateinit var clearPref: Preference

        fun updateUi(loggedIn: Boolean) {
            statusPref.summary = if (loggedIn) "Conectado" else "Não conectado"
            runCatching {
                clearPref::class.java.methods
                    .firstOrNull { it.name == "setVisible" && it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType)) }
                    ?.invoke(clearPref, loggedIn)
            }
        }

        newPreference(screen.context).apply {
            key = "pref_bt_login"
            title = if (hasToken) "Abrir BladeToons" else "Entrar no BladeToons"
            summary = if (hasToken) "Abre o site BladeToons." else "Abre o login oficial no WebView."
            setOnPreferenceClickListener {
                showWebViewLogin(screen.context) {
                    updateUi(preferences.getString(tokenPreferenceKey, "").orEmpty().isNotEmpty())
                }
                true
            }
        }.let(screen::addPreference)

        statusPref = newPreference(screen.context).apply {
            key = "pref_bt_status"
            title = "Sessão BladeToons"
            summary = if (hasToken) "Conectado" else "Não conectado"
            setOnPreferenceClickListener { true }
            runCatching {
                this::class.java.methods
                    .firstOrNull { it.name == "setSelectable" && it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType)) }
                    ?.invoke(this, false)
            }
        }
        screen.addPreference(statusPref)

        clearPref = newPreference(screen.context).apply {
            key = "pref_bt_clear"
            title = "Sair da conta"
            summary = "Remove a sessão salva do BladeToons neste aplicativo."
            setOnPreferenceClickListener {
                clearToken()
                updateUi(false)
                true
            }
        }
        screen.addPreference(clearPref)
        updateUi(hasToken)
    }

    private fun newPreference(context: android.content.Context): Preference = runCatching {
        Preference::class.java.getConstructor(android.content.Context::class.java).newInstance(context)
    }.getOrElse { Preference() }

    private fun showWebViewLogin(context: android.content.Context, onDone: () -> Unit) {
        val dialog = Dialog(context)
        val webView = WebView(context)
        val handler = Handler(Looper.getMainLooper())
        var finished = false
        var destroyed = false

        fun close(autoClosed: Boolean = false) {
            if (destroyed) return
            destroyed = true
            handler.removeCallbacksAndMessages(null)
            webView.stopLoading()
            if (dialog.isShowing) dialog.dismiss()
            webView.destroy()
            if (autoClosed) onDone()
        }

        val density = context.resources.displayMetrics.density
        val root = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val topBar = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val p = (12 * density).toInt()
            setPadding(p, p, p, p)
            layoutParams = android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        topBar.addView(
            android.widget.TextView(context).apply {
                text = "←"
                textSize = 22f
                setPadding(0, 0, (16 * density).toInt(), 0)
                setOnClickListener { if (webView.canGoBack()) webView.goBack() else close() }
            },
        )
        topBar.addView(
            android.widget.TextView(context).apply {
                text = "BladeToons"
                textSize = 17f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                layoutParams = android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        topBar.addView(
            android.widget.TextView(context).apply {
                text = "✕"
                textSize = 20f
                setPadding((16 * density).toInt(), 0, 0, 0)
                setOnClickListener { close() }
            },
        )
        val progress = android.widget.ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            layoutParams = android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (3 * density).toInt().coerceAtLeast(1))
        }
        root.addView(topBar)
        root.addView(progress)
        root.addView(webView, android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        lateinit var checkToken: () -> Unit
        checkToken = {
            if (!finished && !destroyed && dialog.isShowing) {
                webView.evaluateJavascript("localStorage.getItem('token')") { raw ->
                    if (finished || destroyed) return@evaluateJavascript
                    val token = runCatching { raw.parseAs<String?>() }.getOrNull()
                    if (!token.isNullOrBlank() && token != "null") {
                        finished = true
                        preferences.edit().putString(tokenPreferenceKey, token).apply()
                        close(autoClosed = true)
                    } else if (!finished && !destroyed && dialog.isShowing) {
                        handler.postDelayed({ checkToken() }, 1_000L)
                    }
                }
            }
        }

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.visibility = if (newProgress >= 100) android.view.View.GONE else android.view.View.VISIBLE
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = android.view.View.GONE
                handler.removeCallbacksAndMessages(null)
                checkToken()
            }
        }
        dialog.setContentView(root)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.setOnShowListener { dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
                if (webView.canGoBack()) {
                    webView.goBack()
                    return@setOnKeyListener true
                }
            }
            false
        }
        dialog.setOnDismissListener {
            if (!destroyed) {
                destroyed = true
                handler.removeCallbacksAndMessages(null)
                webView.stopLoading()
                webView.destroy()
            }
        }
        dialog.show()
        webView.loadUrl(baseUrl)
    }
}
