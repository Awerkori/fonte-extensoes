package eu.kanade.tachiyomi.extension.pt.karikari

import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

internal enum class SessionState {
    NOT_AUTHENTICATED,
    AUTHENTICATED,
    EXPIRED,
}

internal data class AuthSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val email: String?,
)

@Serializable
private data class LoginRequest(
    val email: String,
    val password: String,
)

@Serializable
private data class RefreshRequest(
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("grant_type") val grantType: String = "refresh_token",
)

@Serializable
private data class AuthResponse(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
    @SerialName("expires_at") val expiresAt: Long? = null,
    @SerialName("expires_in") val expiresIn: Long = 0,
    val user: AuthUser? = null,
)

@Serializable
private data class AuthUser(
    val id: String = "",
    val email: String? = null,
)

@Serializable
private data class AuthError(
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
    val code: String? = null,
    val msg: String? = null,
    val message: String? = null,
)

internal class KariKariAuth(
    private val preferences: android.content.SharedPreferences,
    networkClient: OkHttpClient,
    private val authUrl: String,
    private val anonKey: String,
) {
    private val authClient = networkClient.newBuilder().cookieJar(okhttp3.CookieJar.NO_COOKIES).build()
    private val lock = Any()

    fun setupPreferenceScreen(screen: PreferenceScreen) {
        val handler = Handler(Looper.getMainLooper())
        lateinit var status: Preference
        lateinit var login: Preference
        lateinit var logoutPreference: Preference

        fun show(context: android.content.Context, message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()

        fun update(state: SessionState) {
            status.summary = when (state) {
                SessionState.NOT_AUTHENTICATED -> "Não autenticado"
                SessionState.AUTHENTICATED -> "Logado"
                SessionState.EXPIRED -> "Sessão expirada"
            }
            setPreferenceEnabled(logoutPreference, state == SessionState.AUTHENTICATED)
        }

        EditTextPreference(screen.context).apply {
            key = PREF_EMAIL
            title = "E-mail"
            summary = "E-mail da conta KariKari"
            setDefaultValue("")
            setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS }
            setOnPreferenceChangeListener { _, _ ->
                invalidateSession()
                true
            }
        }.let(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_PASSWORD
            title = "Senha"
            summary = "Senha usada somente para entrar"
            setDefaultValue("")
            setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
            setOnPreferenceChangeListener { _, _ ->
                invalidateSession()
                true
            }
        }.let(screen::addPreference)

        status = createPreference(screen.context).apply {
            key = PREF_STATUS
            title = "Status da sessão"
            summary = "Verificando sessão..."
        }.also(screen::addPreference)
        setPreferenceSelectable(status, false)

        login = createPreference(screen.context).apply {
            key = PREF_LOGIN
            title = "Entrar / Atualizar sessão"
            summary = "Validar a conta KariKari"
            setOnPreferenceClickListener {
                val email = preferences.getString(PREF_EMAIL, "").orEmpty().trim()
                val password = preferences.getString(PREF_PASSWORD, "").orEmpty()
                if (email.isBlank() || password.isBlank()) {
                    show(screen.context, "Informe e-mail e senha da KariKari.")
                    return@setOnPreferenceClickListener true
                }
                setPreferenceEnabled(login, false)
                Thread {
                    val result = runCatching { performLogin(email, password) }
                    handler.post {
                        setPreferenceEnabled(login, true)
                        if (result.isSuccess) {
                            update(SessionState.AUTHENTICATED)
                            show(screen.context, "Login realizado com sucesso.")
                        } else {
                            update(SessionState.NOT_AUTHENTICATED)
                            show(screen.context, result.exceptionOrNull()?.message ?: LOGIN_ERROR)
                        }
                    }
                }.start()
                true
            }
        }.also(screen::addPreference)

        logoutPreference = createPreference(screen.context).apply {
            key = PREF_LOGOUT
            title = "Sair"
            summary = "Encerrar a sessão KariKari neste aplicativo"
            setOnPreferenceClickListener {
                setPreferenceEnabled(logoutPreference, false)
                Thread {
                    this@KariKariAuth.logout()
                    handler.post {
                        update(SessionState.NOT_AUTHENTICATED)
                        show(screen.context, "Sessão encerrada.")
                    }
                }.start()
                true
            }
        }.also(screen::addPreference)

        val hadSession = hasStoredSession()
        status.summary = if (hadSession) "Verificando sessão..." else "Não autenticado"
        setPreferenceEnabled(logoutPreference, hadSession)
        Thread {
            val state = runCatching { sessionState() }.getOrDefault(SessionState.EXPIRED)
            handler.post { update(state) }
        }.start()
    }

    fun hasStoredSession(): Boolean = synchronized(lock) { loadSession() != null }

    fun sessionState(): SessionState = synchronized(lock) {
        if (loadSession() == null) return@synchronized SessionState.NOT_AUTHENTICATED
        if (getValidSessionLocked() != null) SessionState.AUTHENTICATED else SessionState.EXPIRED
    }

    fun getValidSession(): AuthSession? = synchronized(lock) { getValidSessionLocked() }

    fun performLogin(email: String, password: String) = synchronized(lock) {
        clearTokensLocked()
        val session = authClient.newCall(
            POST(
                "$authUrl/token?grant_type=password",
                authHeaders(contentType = true),
                LoginRequest(email, password).toJsonRequestBody(),
            ),
        ).execute().use { response ->
            if (!response.isSuccessful) throw loginError(response)
            response.parseAs<AuthResponse>().toSession()
        }
        val user = validateSession(session) ?: throw IOException("A KariKari não aceitou a sessão recebida.")
        saveSession(session.copy(email = user.email ?: email))
        preferences.edit().remove(PREF_PASSWORD).apply()
    }

    fun logout() = synchronized(lock) {
        loadSession()?.let { session ->
            runCatching {
                authClient.newCall(
                    POST(
                        "$authUrl/logout",
                        authHeaders(session.accessToken),
                        "".toRequestBody(),
                    ),
                ).execute().use { }
            }
        }
        clearTokensLocked()
    }

    fun invalidateSession() = synchronized(lock) { clearTokensLocked() }

    private fun getValidSessionLocked(): AuthSession? {
        val stored = loadSession() ?: return null
        val user = validateSession(stored)
        if (user != null) {
            val session = stored.copy(email = user.email ?: stored.email)
            saveSession(session)
            return session
        }

        val refreshed = refreshSession(stored.refreshToken)
        val refreshedUser = refreshed?.let(::validateSession)
        if (refreshed != null && refreshedUser != null) {
            val session = refreshed.copy(email = refreshedUser.email ?: stored.email)
            saveSession(session)
            return session
        }

        clearTokensLocked()
        return null
    }

    private fun refreshSession(refreshToken: String): AuthSession? {
        if (refreshToken.isBlank()) return null
        return authClient.newCall(
            POST(
                "$authUrl/token?grant_type=refresh_token",
                authHeaders(contentType = true),
                RefreshRequest(refreshToken).toJsonRequestBody(),
            ),
        ).execute().use { response ->
            when {
                response.code == 400 || response.code == 401 -> null
                !response.isSuccessful -> throw IOException("Não foi possível atualizar a sessão KariKari.")
                else -> response.parseAs<AuthResponse>().toSession()
            }
        }
    }

    private fun validateSession(session: AuthSession): AuthUser? = authClient.newCall(
        GET("$authUrl/user", authHeaders(session.accessToken)),
    ).execute().use { response ->
        when {
            response.code == 401 || response.code == 403 -> null
            !response.isSuccessful -> throw IOException("Não foi possível verificar a sessão KariKari.")
            else -> runCatching { response.parseAs<AuthUser>() }.getOrNull()?.takeIf { it.id.isNotBlank() }
        }
    }

    private fun AuthResponse.toSession(): AuthSession {
        if (accessToken.isBlank() || refreshToken.isBlank()) throw IOException("A KariKari não retornou uma sessão válida.")
        val expires = expiresAt ?: (System.currentTimeMillis() / 1000L + expiresIn)
        return AuthSession(accessToken, refreshToken, expires, user?.email)
    }

    private fun loginError(response: okhttp3.Response): IOException {
        val error = runCatching { response.parseAs<AuthError>() }.getOrNull()
        val text = listOf(error?.code, error?.error, error?.errorDescription, error?.msg, error?.message)
            .filterNotNull().joinToString(" ").lowercase()
        return when {
            text.contains("email_not_confirmed") || text.contains("email not confirmed") -> IOException("Confirme seu e-mail da KariKari antes de entrar.")
            response.code == 400 && (text.contains("invalid") || text.contains("credential") || text.contains("password")) -> IOException("E-mail ou senha incorretos.")
            else -> IOException("Falha ao entrar na KariKari.")
        }
    }

    private fun authHeaders(accessToken: String? = null, contentType: Boolean = false): Headers = Headers.Builder()
        .set("apikey", anonKey)
        .set("Authorization", "Bearer ${accessToken ?: anonKey}")
        .set("Accept", "application/json")
        .set("X-Client-Info", "supabase-js/2.103.3")
        .apply { if (contentType) set("Content-Type", "application/json") }
        .build()

    private fun loadSession(): AuthSession? {
        val access = preferences.getString(PREF_ACCESS_TOKEN, "").orEmpty()
        val refresh = preferences.getString(PREF_REFRESH_TOKEN, "").orEmpty()
        if (access.isBlank() || refresh.isBlank()) return null
        return AuthSession(
            accessToken = access,
            refreshToken = refresh,
            expiresAt = preferences.getLong(PREF_EXPIRES_AT, 0L),
            email = preferences.getString(PREF_USER_EMAIL, null),
        )
    }

    private fun saveSession(session: AuthSession) {
        preferences.edit()
            .putString(PREF_ACCESS_TOKEN, session.accessToken)
            .putString(PREF_REFRESH_TOKEN, session.refreshToken)
            .putLong(PREF_EXPIRES_AT, session.expiresAt)
            .putString(PREF_USER_EMAIL, session.email.orEmpty())
            .apply()
    }

    private fun clearTokensLocked() {
        preferences.edit()
            .remove(PREF_ACCESS_TOKEN)
            .remove(PREF_REFRESH_TOKEN)
            .remove(PREF_EXPIRES_AT)
            .remove(PREF_USER_EMAIL)
            .apply()
    }

    private fun createPreference(context: android.content.Context): Preference = runCatching {
        Preference::class.java.getConstructor(android.content.Context::class.java).newInstance(context)
    }.getOrElse { Preference() }

    private fun setPreferenceEnabled(preference: Preference, enabled: Boolean) {
        runCatching {
            preference::class.java.methods.firstOrNull { method ->
                method.name == "setEnabled" && method.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType))
            }?.invoke(preference, enabled)
        }
    }

    private fun setPreferenceSelectable(preference: Preference, selectable: Boolean) {
        runCatching {
            preference::class.java.methods.firstOrNull { method ->
                method.name == "setSelectable" && method.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType))
            }?.invoke(preference, selectable)
        }
    }

    companion object {
        private const val PREF_EMAIL = "karikari_email"
        private const val PREF_PASSWORD = "karikari_password"
        private const val PREF_ACCESS_TOKEN = "karikari_access_token"
        private const val PREF_REFRESH_TOKEN = "karikari_refresh_token"
        private const val PREF_EXPIRES_AT = "karikari_expires_at"
        private const val PREF_USER_EMAIL = "karikari_user_email"
        private const val PREF_STATUS = "karikari_session_status"
        private const val PREF_LOGIN = "karikari_login"
        private const val PREF_LOGOUT = "karikari_logout"
        private const val LOGIN_ERROR = "Falha ao entrar na KariKari."
    }
}
