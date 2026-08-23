package eu.kanade.tachiyomi.extension.pt.tomato

import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

internal class TomatoServerBootstrap(
    private val preferences: SharedPreferences,
) {
    @Volatile
    private var completed = false
    private val lock = Any()

    fun selectedHost(): String {
        if (completed) return persistedHost()
        synchronized(lock) {
            if (completed) return persistedHost()
            val (prod, edge, connectivity) = probeServers()
            if (!prod.available && !edge.available && !connectivity.available) {
                throw IOException("Nenhum servidor Tomato está acessível")
            }
            val selected = if (
                (prod.latencyMs <= edge.latencyMs || !edge.available) && prod.available
            ) {
                PROD_HOST
            } else {
                EDGE_HOST
            }
            Log.d(TAG, "TOMATO_DEBUG SERVER selected=${selected.label()}")
            preferences.edit().putString(PREF_SELECTED_API_HOST, selected).apply()
            checkUpdate(selected)
            completed = true
            return selected
        }
    }

    fun persistedHost(): String = preferences.getString(PREF_SELECTED_API_HOST, PROD_HOST)
        ?.takeIf { it == PROD_HOST || it == EDGE_HOST }
        ?: PROD_HOST

    private fun probeServers(): Triple<ProbeResult, ProbeResult, ProbeResult> {
        val executor = Executors.newFixedThreadPool(3)
        try {
            val prod = executor.submit<ProbeResult> { probe(PROD_HOST, "prod") }
            val edge = executor.submit<ProbeResult> { probe(EDGE_HOST, "edge") }
            val connectivity = executor.submit<ProbeResult> { probe(CONNECTIVITY_HOST, "connectivity") }
            executor.shutdown()
            executor.awaitTermination(PROBE_WAIT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            return Triple(
                prod.takeIf { it.isDone }?.get() ?: ProbeResult.unavailable(),
                edge.takeIf { it.isDone }?.get() ?: ProbeResult.unavailable(),
                connectivity.takeIf { it.isDone }?.get() ?: ProbeResult.unavailable(),
            )
        } finally {
            executor.shutdownNow()
        }
    }

    private fun probe(host: String, label: String): ProbeResult {
        val startedAt = System.currentTimeMillis()
        return try {
            val connection = (URL(host).openConnection() as HttpsURLConnection).apply {
                connectTimeout = PROBE_CONNECT_TIMEOUT_MS
                setRequestProperty("Accept-Encoding", "gzip, deflate, br")
            }
            try {
                connection.connect()
                val latency = System.currentTimeMillis() - startedAt
                Log.d(TAG, "TOMATO_DEBUG SERVER probe=$label connected=true latencyMs=$latency")
                ProbeResult(latency, true)
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            val latency = System.currentTimeMillis() - startedAt
            Log.d(TAG, "TOMATO_DEBUG SERVER probe=$label connected=false latencyMs=$latency")
            ProbeResult.unavailable()
        }
    }

    private fun checkUpdate(host: String) {
        var timeoutMs = CHECK_UPDATE_TIMEOUT_MS
        repeat(CHECK_UPDATE_MAX_ATTEMPTS) { attempt ->
            try {
                checkUpdateOnce(host, timeoutMs)
                return
            } catch (error: IOException) {
                val retryable = error is SocketTimeoutException || error is ConnectException
                if (!retryable || attempt == CHECK_UPDATE_MAX_ATTEMPTS - 1) throw error
                timeoutMs *= 2
            }
        }
    }

    private fun checkUpdateOnce(host: String, timeoutMs: Int) {
        val connection = (URL("$host/checkupdate/").openConnection() as HttpsURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use {
                it.write(JSONObject().put("app_version", OFFICIAL_APP_VERSION).toString())
            }
            val httpStatus = connection.responseCode
            val rawBody = (if (httpStatus >= 400) connection.errorStream else connection.inputStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val body = runCatching { JSONObject(rawBody) }.getOrNull()
            val statusCode = body?.optString("status_code")?.toIntOrNull()
            val serverVersion = body?.optString("server_version")?.takeIf(String::isNotBlank)
            val remoteAppVersion = body?.optString("app_version")?.takeIf(String::isNotBlank)
            val checkCountry = body?.optBoolean("check_country", false) ?: false
            val requireCaptcha = body?.optBoolean("require_captcha", false) ?: false
            val syncPlayheads = body?.optBoolean("sync_playheads", false) ?: false
            Log.d(TAG, "TOMATO_DEBUG BOOTSTRAP HTTP=$httpStatus apiStatus=${statusCode ?: "none"}")
            if (httpStatus !in 200..299 || statusCode != SUCCESS_STATUS_CODE) {
                throw IOException("Bootstrap Tomato indisponível (HTTP $httpStatus)")
            }
            if (serverVersion == null || remoteAppVersion == null) throw IOException("Bootstrap Tomato incompleto")
            val localVersionNumber = OFFICIAL_APP_VERSION.versionNumber()
            if (localVersionNumber < serverVersion.versionNumber()) throw IOException("O aplicativo oficial Tomato exige atualização")
            if (localVersionNumber > remoteAppVersion.versionNumber()) throw IOException("Versão Tomato não aceita pelo servidor")
            preferences.edit()
                .putString(PREF_SERVER_VERSION, serverVersion)
                .putString(PREF_REMOTE_APP_VERSION, remoteAppVersion)
                .putBoolean(PREF_CHECK_COUNTRY, checkCountry)
                .putBoolean(PREF_REQUIRE_CAPTCHA, requireCaptcha)
                .putBoolean(PREF_SYNC_PLAYHEADS, syncPlayheads)
                .apply()
            if (checkCountry) checkCountry()
        } finally {
            connection.disconnect()
        }
    }

    private fun checkCountry() {
        val connection = (URL(COUNTRY_CHECK_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CHECK_UPDATE_TIMEOUT_MS
            readTimeout = CHECK_UPDATE_TIMEOUT_MS
        }
        try {
            if (connection.responseCode !in 200..299) return
            val country = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                runCatching { JSONObject(reader.readText()).optString("countryCode") }.getOrNull()
            }
            if (country != null && country !in ALLOWED_COUNTRIES) throw IOException("País não autorizado pelo Tomato")
        } catch (error: IOException) {
            if (error.message == "País não autorizado pelo Tomato") throw error
        } finally {
            connection.disconnect()
        }
    }

    private class ProbeResult(val latencyMs: Long, val available: Boolean) {
        companion object {
            fun unavailable() = ProbeResult(Long.MAX_VALUE, false)
        }
    }

    private fun String.label() = if (this == PROD_HOST) "prod" else "edge"
    private fun String.versionNumber() = filter(Char::isDigit).toIntOrNull() ?: 0

    companion object {
        private const val TAG = "Tomato"
        private const val PROD_HOST = "https://prod-api.tomatoanimes.com"
        private const val EDGE_HOST = "https://edge.betomato.com"
        private const val CONNECTIVITY_HOST = "https://connectivitycheck.gstatic.com"
        private const val COUNTRY_CHECK_URL = "http://ip-api.com/json/"
        private const val PREF_SELECTED_API_HOST = "tomato_selected_api_host"
        private const val PREF_SERVER_VERSION = "tomato_server_version"
        private const val PREF_REMOTE_APP_VERSION = "tomato_remote_app_version"
        private const val PREF_CHECK_COUNTRY = "tomato_check_country"
        private const val PREF_REQUIRE_CAPTCHA = "tomato_require_captcha"
        private const val PREF_SYNC_PLAYHEADS = "tomato_sync_playheads"
        private const val PROBE_CONNECT_TIMEOUT_MS = 6_000
        private const val PROBE_WAIT_TIMEOUT_MS = 10_000
        private const val CHECK_UPDATE_TIMEOUT_MS = 10_000
        private const val CHECK_UPDATE_MAX_ATTEMPTS = 2
        private const val SUCCESS_STATUS_CODE = 4
        private const val OFFICIAL_APP_VERSION = "1.4.3"
        private val ALLOWED_COUNTRIES = setOf("BR", "PT", "PY", "MZ", "AO", "CV", "GF")
    }
}
