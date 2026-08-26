package eu.kanade.tachiyomi.extension.pt.tomato

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.os.Build
import android.os.Handler
import android.text.InputType
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast

internal object Auth {

    private const val HCAPTCHA_SITE_KEY = "d0706611-1d89-4b8c-af79-3caf0f14feba"

    val deviceFingerprint: String
        get() {
            val release = Build.VERSION.RELEASE ?: "14"
            val manufacturer = Build.MANUFACTURER ?: "Samsung"
            val model = Build.MODEL ?: "SM-S911B"
            return "$release/$manufacturer/$model".replace("\\s+".toRegex(), "-")
        }

    private val HCAPTCHA_HTML = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <script src="https://js.hcaptcha.com/1/api.js" async defer></script>
            <style>
                body {
                    background-color: #1a1a1a;
                    color: #ffffff;
                    display: flex;
                    justify-content: center;
                    align-items: center;
                    height: 100vh;
                    margin: 0;
                }
            </style>
        </head>
        <body>
            <div class="h-captcha"
                 data-sitekey="$HCAPTCHA_SITE_KEY"
                 data-theme="dark"
                 data-callback="onCaptchaSolved">
            </div>
            <script>
                function onCaptchaSolved(token) {
                    if (window.AndroidBridge) {
                        window.AndroidBridge.onCaptchaResult(token);
                    }
                }
            </script>
        </body>
        </html>
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    fun showCaptchaDialog(context: Context, handler: Handler, onSolved: (String) -> Unit) {
        handler.post {
            var dialog: AlertDialog? = null
            val webView = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun onCaptchaResult(token: String) {
                            handler.post {
                                dialog?.dismiss()
                                if (token.isNotBlank()) {
                                    onSolved(token)
                                } else {
                                    Toast.makeText(context, "Captcha inválido ou vazio", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    "AndroidBridge",
                )
                loadDataWithBaseURL("https://prod-api.tomatoanimes.com", HCAPTCHA_HTML, "text/html", "UTF-8", null)
            }

            dialog = AlertDialog.Builder(context)
                .setTitle("Verificação de Segurança")
                .setView(webView)
                .setNegativeButton("Cancelar") { d, _ ->
                    webView.destroy()
                    d.dismiss()
                }
                .create()

            dialog.setOnDismissListener {
                webView.destroy()
            }

            dialog.show()
        }
    }

    fun showLoginInputDialog(context: Context, onConfirmed: (String, String) -> Unit) {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val emailInput = EditText(context).apply {
            hint = "E-mail"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val passInput = EditText(context).apply {
            hint = "Senha"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        layout.addView(emailInput)
        layout.addView(passInput)

        AlertDialog.Builder(context)
            .setTitle("Login Tomato")
            .setView(layout)
            .setPositiveButton("Continuar") { _, _ ->
                val email = emailInput.text.toString().trim()
                val pass = passInput.text.toString()
                if (email.isNotEmpty() && pass.isNotEmpty()) {
                    onConfirmed(email, pass)
                } else {
                    Toast.makeText(context, "Preencha todos os campos", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    fun showRegisterInputDialog(context: Context, onConfirmed: (String, String, String) -> Unit) {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val userInput = EditText(context).apply {
            hint = "Nome de Usuário"
        }
        val emailInput = EditText(context).apply {
            hint = "E-mail"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val passInput = EditText(context).apply {
            hint = "Senha"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        layout.addView(userInput)
        layout.addView(emailInput)
        layout.addView(passInput)

        AlertDialog.Builder(context)
            .setTitle("Criar Conta Tomato")
            .setView(layout)
            .setPositiveButton("Continuar") { _, _ ->
                val user = userInput.text.toString().trim()
                val email = emailInput.text.toString().trim()
                val pass = passInput.text.toString()
                if (user.isNotEmpty() && email.isNotEmpty() && pass.isNotEmpty()) {
                    onConfirmed(user, email, pass)
                } else {
                    Toast.makeText(context, "Preencha todos os campos", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
